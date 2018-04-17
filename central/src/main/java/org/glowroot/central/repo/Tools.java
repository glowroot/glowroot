/*
 * Copyright 2018 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.glowroot.central.repo;

import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Future;

import com.datastax.driver.core.BoundStatement;
import com.datastax.driver.core.PreparedStatement;
import com.datastax.driver.core.ResultSet;
import com.datastax.driver.core.Row;
import com.google.common.collect.HashMultimap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Multimap;
import org.immutables.value.Value;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.glowroot.central.util.MoreFutures;
import org.glowroot.central.util.Session;
import org.glowroot.common2.config.ImmutableUserConfig;
import org.glowroot.ui.PasswordHash;

import static com.google.common.base.Preconditions.checkNotNull;
import static java.util.concurrent.TimeUnit.HOURS;

public class Tools {

    private static final Logger startupLogger = LoggerFactory.getLogger("org.glowroot");

    private static final Set<String> keepTableNames = ImmutableSet.of("schema_version",
            "central_config", "agent_config", "user", "role", "agent", "environment",
            "v09_agent_rollup");

    private final Session session;
    private final CentralRepoModule repos;

    public Tools(Session session, CentralRepoModule repos) {
        this.session = session;
        this.repos = repos;
    }

    public boolean setupAdminUser(List<String> args) throws Exception {
        String username = args.get(0);
        String password = args.get(1);
        if (repos.getRoleDao().read("Administrator") == null) {
            startupLogger.error("Administrator role does not exist, exiting");
            return false;
        }
        // not using insertIfNotExists in case this command fails on the next line for some reason
        // (while deleting anonymous user) and the command needs to be re-run
        repos.getUserDao().insert(ImmutableUserConfig.builder()
                .username(username)
                .passwordHash(PasswordHash.createHash(password))
                .addRoles("Administrator")
                .build());
        repos.getUserDao().delete("anonymous");
        return true;
    }

    public boolean truncateAllData(@SuppressWarnings("unused") List<String> args) throws Exception {
        for (String tableName : session.getAllTableNames()) {
            if (!keepTableNames.contains(tableName)) {
                startupLogger.info("truncating {} ...", tableName);
                session.updateSchemaWithRetry("truncate table " + tableName);
            }
        }
        // no longer need v09 data checks (but still need v09_agent_rollup mappings for 0.9 agents)
        session.updateSchemaWithRetry("drop table if exists v09_agent_check");
        session.updateSchemaWithRetry("drop table if exists v09_last_capture_time");
        startupLogger.info("NOTE: by default, Cassandra snapshots tables when they are truncated,"
                + " so in order to free up disk space you will need to clear those snapshots, e.g."
                + " with \"nodetool clearsnapshot {}\"", session.getKeyspaceName());
        return true;
    }

    public boolean executeDeletes(List<String> args) throws Exception {
        startupLogger.info("this could take several minutes on large data sets ...");
        String partialTableName = args.get(0);
        int rollupLevel = Integer.parseInt(args.get(1));
        List<Integer> expirationHours;
        if (partialTableName.equals("query") || partialTableName.equals("service_call")) {
            expirationHours = repos.getConfigRepository().getCentralStorageConfig()
                    .queryAndServiceCallRollupExpirationHours();
        } else if (partialTableName.equals("profile")) {
            expirationHours = repos.getConfigRepository().getCentralStorageConfig()
                    .profileRollupExpirationHours();
        } else {
            throw new Exception("Unexpected partial table name: " + partialTableName);
        }
        Date expirationThreshold = new Date(
                System.currentTimeMillis() - HOURS.toMillis(expirationHours.get(rollupLevel)));
        Set<TtPartitionKey> ttPartitionKeys = getPartitionKeys(expirationThreshold);
        Set<TnPartitionKey> tnPartitionKeys =
                getPartitionKeys(ttPartitionKeys, expirationThreshold);
        if (partialTableName.equals("profile")) {
            executeDeletesTt(rollupLevel, "main_thread_profile", expirationThreshold,
                    ttPartitionKeys);
            executeDeletesTn(rollupLevel, "main_thread_profile", expirationThreshold,
                    tnPartitionKeys);
            executeDeletesTt(rollupLevel, "aux_thread_profile", expirationThreshold,
                    ttPartitionKeys);
            executeDeletesTn(rollupLevel, "aux_thread_profile", expirationThreshold,
                    tnPartitionKeys);
            startupLogger.info("NOTE: in order for the deletes just issued to free up disk space,"
                    + " you need to force full compactions on"
                    + " aggregate_tt_main_thread_profile_rollup_" + rollupLevel
                    + ", aggregate_tn_main_thread_profile_rollup_" + rollupLevel
                    + ", aggregate_tt_aux_thread_profile_rollup_" + rollupLevel
                    + " and aggregate_tn_aux_thread_profile_rollup_" + rollupLevel);
        } else {
            executeDeletesTt(rollupLevel, partialTableName, expirationThreshold, ttPartitionKeys);
            executeDeletesTn(rollupLevel, partialTableName, expirationThreshold, tnPartitionKeys);
            startupLogger.info("NOTE: in order for the range deletes just issued to free up disk"
                    + " space, you need to force full compactions on aggregate_tt_"
                    + partialTableName + "_rollup_" + rollupLevel + " and aggregate_tn_"
                    + partialTableName + "_rollup_" + rollupLevel);
        }
        startupLogger.info("ADVANCED NOTE: if you want to avoid full compactions and you are using"
                + " Cassandra 3.4 or later, you can use \"nodetool flush ...\" to flush the range"
                + " tombstones and then use \"nodetool compaction --user-defined ...\" to compact"
                + " the new sstable(s) that contain the range tombstones with the old sstables that"
                + " have data matching the range tombstones");
        return true;
    }

    private Set<TtPartitionKey> getPartitionKeys(Date expirationThreshold) throws Exception {
        ResultSet results = session.execute("select agent_rollup, transaction_type, capture_time"
                + " from aggregate_tt_summary_rollup_3");
        Multimap<String, String> transactionTypes = HashMultimap.create();
        for (Row row : results) {
            int i = 0;
            String agentRollupId = checkNotNull(row.getString(i++));
            String transactionType = checkNotNull(row.getString(i++));
            Date captureTime = checkNotNull(row.getTimestamp(i++));
            if (captureTime.getTime() < expirationThreshold.getTime()) {
                transactionTypes.put(agentRollupId, transactionType);
            }
        }
        Set<TtPartitionKey> ttPartitionKeys = new HashSet<>();
        for (Map.Entry<String, String> entry : transactionTypes.entries()) {
            ttPartitionKeys.add(ImmutableTtPartitionKey.builder()
                    .agentRollupId(entry.getKey())
                    .transactionType(entry.getValue())
                    .build());
        }
        return ttPartitionKeys;
    }

    private Set<TnPartitionKey> getPartitionKeys(Set<TtPartitionKey> ttPartitionKeys,
            Date expirationThreshold) throws Exception {
        Set<TnPartitionKey> tnPartitionKeys = new HashSet<>();
        PreparedStatement readPS = session.prepare("select transaction_name from"
                + " aggregate_tn_summary_rollup_3 where agent_rollup = ? and transaction_type = ?"
                + " and capture_time < ?");
        for (TtPartitionKey ttPartitionKey : ttPartitionKeys) {
            BoundStatement boundStatement = readPS.bind();
            int i = 0;
            boundStatement.setString(i++, ttPartitionKey.agentRollupId());
            boundStatement.setString(i++, ttPartitionKey.transactionType());
            boundStatement.setTimestamp(i++, expirationThreshold);
            ResultSet results = session.execute(boundStatement);
            Set<String> transactionNames = new HashSet<>();
            for (Row row : results) {
                transactionNames.add(checkNotNull(row.getString(0)));
            }
            for (String transactionName : transactionNames) {
                tnPartitionKeys.add(ImmutableTnPartitionKey.builder()
                        .agentRollupId(ttPartitionKey.agentRollupId())
                        .transactionType(ttPartitionKey.transactionType())
                        .transactionName(transactionName)
                        .build());
            }
        }
        return tnPartitionKeys;
    }

    private void executeDeletesTt(int rollupLevel, String partialName, Date expirationThreshold,
            Set<TtPartitionKey> partitionKeys) throws Exception {
        String tableName = "aggregate_tt_" + partialName + "_rollup_" + rollupLevel;
        PreparedStatement deletePS = session.prepare("delete from " + tableName
                + " where agent_rollup = ? and transaction_type = ? and capture_time < ?");
        int count = 0;
        List<Future<?>> futures = new ArrayList<>();
        for (TtPartitionKey partitionKey : partitionKeys) {
            BoundStatement boundStatement = deletePS.bind();
            int i = 0;
            boundStatement.setString(i++, partitionKey.agentRollupId());
            boundStatement.setString(i++, partitionKey.transactionType());
            boundStatement.setTimestamp(i++, expirationThreshold);
            futures.add(session.executeAsync(boundStatement));
            count++;
        }
        MoreFutures.waitForAll(futures);
        startupLogger.info("{} range deletes executed against {}", count, tableName);
    }

    private void executeDeletesTn(int rollupLevel, String partialName, Date expirationThreshold,
            Set<TnPartitionKey> partitionKeys) throws Exception {
        String tableName = "aggregate_tn_" + partialName + "_rollup_" + rollupLevel;
        PreparedStatement deletePS = session.prepare("delete from " + tableName
                + " where agent_rollup = ? and transaction_type = ? and transaction_name = ? and"
                + " capture_time < ?");
        int count = 0;
        List<Future<?>> futures = new ArrayList<>();
        for (TnPartitionKey partitionKey : partitionKeys) {
            BoundStatement boundStatement = deletePS.bind();
            int i = 0;
            boundStatement.setString(i++, partitionKey.agentRollupId());
            boundStatement.setString(i++, partitionKey.transactionType());
            boundStatement.setString(i++, partitionKey.transactionName());
            boundStatement.setTimestamp(i++, expirationThreshold);
            futures.add(session.executeAsync(boundStatement));
            count++;
        }
        MoreFutures.waitForAll(futures);
        startupLogger.info("{} range deletes executed against {}", count, tableName);
    }

    @Value.Immutable
    interface TtPartitionKey {
        String agentRollupId();
        String transactionType();
    }

    @Value.Immutable
    interface TnPartitionKey {
        String agentRollupId();
        String transactionType();
        String transactionName();
    }
}
