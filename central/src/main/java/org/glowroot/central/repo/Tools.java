/*
 * Copyright 2018-2023 the original author or authors.
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

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

import com.datastax.oss.driver.api.core.cql.*;
import com.google.common.collect.HashMultimap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Multimap;
import org.immutables.value.Value;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.glowroot.central.util.MoreFutures;
import org.glowroot.central.util.Session;
import org.glowroot.common2.config.ImmutableUserConfig;
import org.glowroot.common2.repo.PasswordHash;

import static com.google.common.base.Preconditions.checkNotNull;
import static java.util.concurrent.TimeUnit.DAYS;
import static java.util.concurrent.TimeUnit.HOURS;

public class Tools {

    private static final Logger startupLogger = LoggerFactory.getLogger("org.glowroot");

    // agent_config and environment are needed to support agents prior to 0.10.10 which don't
    // re-send that data when requested
    private static final Set<String> keepTableNames = ImmutableSet.of("schema_version",
            "central_config", "agent_config", "user", "role", "environment", "v09_agent_rollup");

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
                startupLogger.info("truncating {}...", tableName);
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

    public boolean deleteOldData(List<String> args) throws Exception {
        String partialTableName = args.get(0);
        int rollupLevel = Integer.parseInt(args.get(1));
        List<Integer> expirationHours;
        if (partialTableName.equals("query") || partialTableName.equals("service_call")) {
            expirationHours = repos.getConfigRepository().getCentralStorageConfig()
                    .queryAndServiceCallRollupExpirationHours();
        } else if (partialTableName.equals("profile")) {
            expirationHours = repos.getConfigRepository().getCentralStorageConfig()
                    .profileRollupExpirationHours();
        } else if (partialTableName.equals("overview")
                || partialTableName.equals("histogram")
                || partialTableName.equals("throughput")
                || partialTableName.equals("summary")
                || partialTableName.equals("error_summary")
                || partialTableName.equals("gauge_value")) {
            expirationHours = repos.getConfigRepository().getCentralStorageConfig()
                    .rollupExpirationHours();
        } else {
            throw new Exception("Unexpected partial table name: " + partialTableName);
        }
        Instant threshold = Instant.ofEpochMilli(
                System.currentTimeMillis() - HOURS.toMillis(expirationHours.get(rollupLevel)));
        if (partialTableName.equals("gauge_value")) {
            return executeGaugeValueRangeDeletes(rollupLevel, "<", threshold);
        } else if (partialTableName.equals("summary") || partialTableName.equals("error_summary")) {
            return executeAggregateSummaryRangeDeletes(partialTableName, rollupLevel, "<",
                    threshold);
        } else {
            return executeAggregateRangeDeletes(partialTableName, rollupLevel, "<", threshold);
        }
    }

    public boolean deleteBadFutureData(List<String> args) throws Exception {
        String partialTableName = args.get(0);
        int rollupLevel = Integer.parseInt(args.get(1));
        Instant threshold = Instant.ofEpochMilli(System.currentTimeMillis() + DAYS.toMillis(1));
        if (partialTableName.equals("gauge_value")) {
            return executeGaugeValueRangeDeletes(rollupLevel, ">", threshold);
        } else if (partialTableName.equals("summary") || partialTableName.equals("error_summary")) {
            return executeAggregateSummaryRangeDeletes(partialTableName, rollupLevel, ">",
                    threshold);
        } else {
            return executeAggregateRangeDeletes(partialTableName, rollupLevel, ">", threshold);
        }
    }

    private boolean executeAggregateRangeDeletes(String partialTableName, int rollupLevel,
            String thresholdComparator, Instant threshold) throws Exception {
        startupLogger.info("this could take several minutes on large data sets...");
        Set<TtPartitionKey> ttPartitionKeys =
                getPartitionKeys(rollupLevel, thresholdComparator, threshold);
        Set<TnPartitionKey> tnPartitionKeys =
                getPartitionKeys(rollupLevel, ttPartitionKeys, thresholdComparator, threshold);
        if (partialTableName.equals("profile")) {
            executeDeletesTt(rollupLevel, "main_thread_profile", thresholdComparator, threshold,
                    ttPartitionKeys);
            executeDeletesTn(rollupLevel, "main_thread_profile", thresholdComparator, threshold,
                    tnPartitionKeys);
            executeDeletesTt(rollupLevel, "aux_thread_profile", thresholdComparator, threshold,
                    ttPartitionKeys);
            executeDeletesTn(rollupLevel, "aux_thread_profile", thresholdComparator, threshold,
                    tnPartitionKeys);
            startupLogger.info("NOTE: in order for the deletes just issued to free up disk space,"
                    + " you need to force full compactions on"
                    + " aggregate_tt_main_thread_profile_rollup_" + rollupLevel
                    + ", aggregate_tn_main_thread_profile_rollup_" + rollupLevel
                    + ", aggregate_tt_aux_thread_profile_rollup_" + rollupLevel
                    + " and aggregate_tn_aux_thread_profile_rollup_" + rollupLevel);
        } else {
            executeDeletesTt(rollupLevel, partialTableName, thresholdComparator, threshold,
                    ttPartitionKeys);
            executeDeletesTn(rollupLevel, partialTableName, thresholdComparator, threshold,
                    tnPartitionKeys);
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

    private boolean executeAggregateSummaryRangeDeletes(String partialTableName, int rollupLevel,
            String thresholdComparator, Instant threshold) throws Exception {
        startupLogger.info("this could take several minutes on large data sets...");
        Set<TtPartitionKey> ttPartitionKeys =
                getPartitionKeys(rollupLevel, thresholdComparator, threshold);
        executeDeletesTt(rollupLevel, partialTableName, thresholdComparator, threshold,
                ttPartitionKeys);
        startupLogger.info("NOTE: in order for the range deletes just issued to free up disk space,"
                + " you need to force full compactions on aggregate_tt_summary_rollup_"
                + partialTableName + "_rollup_" + rollupLevel);
        startupLogger.info("ADVANCED NOTE: if you want to avoid full compactions and you are using"
                + " Cassandra 3.4 or later, you can use \"nodetool flush ...\" to flush the range"
                + " tombstones and then use \"nodetool compaction --user-defined ...\" to compact"
                + " the new sstable(s) that contain the range tombstones with the old sstables that"
                + " have data matching the range tombstones");
        return true;
    }

    private boolean executeGaugeValueRangeDeletes(int rollupLevel, String thresholdComparator,
            Instant threshold) throws Exception {
        startupLogger.info("this could take several minutes on large data sets...");
        Set<GaugeValuePartitionKey> partitionKeys =
                getGaugeValuePartitionKeys(rollupLevel, thresholdComparator, threshold);
        executeGaugeValueDeletes(rollupLevel, thresholdComparator, threshold, partitionKeys);
        startupLogger.info("NOTE: in order for the range deletes just issued to free up disk space,"
                + " you need to force full compaction on gauge_value_rollup_" + rollupLevel);
        startupLogger.info("ADVANCED NOTE: if you want to avoid full compaction and you are using"
                + " Cassandra 3.4 or later, you can use \"nodetool flush ...\" to flush the range"
                + " tombstones and then use \"nodetool compaction --user-defined ...\" to compact"
                + " the new sstable(s) that contain the range tombstones with the old sstables that"
                + " have data matching the range tombstones");
        return true;
    }

    private Set<TtPartitionKey> getPartitionKeys(int rollupLevel, String thresholdComparator,
            Instant threshold) throws Exception {
        ResultSet results = session.read("select agent_rollup, transaction_type, capture_time"
                + " from aggregate_tt_summary_rollup_" + rollupLevel);
        Multimap<String, String> transactionTypes = HashMultimap.create();
        for (Row row : results) {
            int i = 0;
            String agentRollupId = checkNotNull(row.getString(i++));
            String transactionType = checkNotNull(row.getString(i++));
            Instant captureTime = checkNotNull(row.getInstant(i++));
            if (thresholdComparator.equals("<")) {
                if (captureTime.toEpochMilli() < threshold.toEpochMilli()) {
                    transactionTypes.put(agentRollupId, transactionType);
                }
            } else if (thresholdComparator.equals(">")) {
                if (captureTime.toEpochMilli() > threshold.toEpochMilli()) {
                    transactionTypes.put(agentRollupId, transactionType);
                }
            } else {
                throw new IllegalStateException(
                        "Unexpected threshold comparator: " + thresholdComparator);
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

    private Set<TnPartitionKey> getPartitionKeys(int rollupLevel,
            Set<TtPartitionKey> ttPartitionKeys, String thresholdComparator, Instant threshold)
            throws Exception {
        Set<TnPartitionKey> tnPartitionKeys = new HashSet<>();
        PreparedStatement readPS = session.prepare("select transaction_name from"
                + " aggregate_tn_summary_rollup_" + rollupLevel + " where agent_rollup = ? and"
                + " transaction_type = ? and capture_time " + thresholdComparator + " ?");
        for (TtPartitionKey ttPartitionKey : ttPartitionKeys) {
            int i = 0;
            BoundStatement boundStatement = readPS.bind()
                .setString(i++, ttPartitionKey.agentRollupId())
                .setString(i++, ttPartitionKey.transactionType())
                .setInstant(i++, threshold);
            ResultSet results = session.read(boundStatement);
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

    private Set<GaugeValuePartitionKey> getGaugeValuePartitionKeys(int rollupLevel,
            String thresholdComparator, Instant threshold) throws Exception {
        ResultSet results = session.read("select agent_rollup, gauge_name, capture_time from"
                + " gauge_value_rollup_" + rollupLevel);
        Multimap<String, String> gaugeNames = HashMultimap.create();
        for (Row row : results) {
            int i = 0;
            String agentRollupId = checkNotNull(row.getString(i++));
            String gaugeName = checkNotNull(row.getString(i++));
            Instant captureTime = checkNotNull(row.getInstant(i++));
            if (thresholdComparator.equals("<")) {
                if (captureTime.toEpochMilli() < threshold.toEpochMilli()) {
                    gaugeNames.put(agentRollupId, gaugeName);
                }
            } else if (thresholdComparator.equals(">")) {
                if (captureTime.toEpochMilli() > threshold.toEpochMilli()) {
                    gaugeNames.put(agentRollupId, gaugeName);
                }
            } else {
                throw new IllegalStateException(
                        "Unexpected threshold comparator: " + thresholdComparator);
            }
        }
        Set<GaugeValuePartitionKey> partitionKeys = new HashSet<>();
        for (Map.Entry<String, String> entry : gaugeNames.entries()) {
            partitionKeys.add(ImmutableGaugeValuePartitionKey.builder()
                    .agentRollupId(entry.getKey())
                    .gaugeName(entry.getValue())
                    .build());
        }
        return partitionKeys;
    }

    private void executeDeletesTt(int rollupLevel, String partialName, String thresholdComparator,
            Instant threshold, Set<TtPartitionKey> partitionKeys) throws Exception {
        String tableName = "aggregate_tt_" + partialName + "_rollup_" + rollupLevel;
        PreparedStatement deletePS = session.prepare("delete from " + tableName
                + " where agent_rollup = ? and transaction_type = ? and capture_time "
                + thresholdComparator + " ?");
        int count = 0;
        List<CompletableFuture<?>> futures = new ArrayList<>();
        for (TtPartitionKey partitionKey : partitionKeys) {
            int i = 0;
            BoundStatement boundStatement = deletePS.bind()
                .setString(i++, partitionKey.agentRollupId())
                .setString(i++, partitionKey.transactionType())
                .setInstant(i++, threshold);
            futures.add(session.writeAsync(boundStatement).toCompletableFuture());
            count++;
        }
        MoreFutures.waitForAll(futures);
        startupLogger.info("{} range deletes executed against {}", count, tableName);
    }

    private void executeDeletesTn(int rollupLevel, String partialName, String thresholdComparator,
            Instant threshold, Set<TnPartitionKey> partitionKeys) throws Exception {
        String tableName = "aggregate_tn_" + partialName + "_rollup_" + rollupLevel;
        PreparedStatement deletePS = session.prepare("delete from " + tableName
                + " where agent_rollup = ? and transaction_type = ? and transaction_name = ? and"
                + " capture_time " + thresholdComparator + " ?");
        int count = 0;
        List<CompletableFuture<?>> futures = new ArrayList<>();
        for (TnPartitionKey partitionKey : partitionKeys) {
            int i = 0;
            BoundStatement boundStatement = deletePS.bind()
                .setString(i++, partitionKey.agentRollupId())
                .setString(i++, partitionKey.transactionType())
                .setString(i++, partitionKey.transactionName())
                .setInstant(i++, threshold);
            futures.add(session.writeAsync(boundStatement).toCompletableFuture());
            count++;
        }
        MoreFutures.waitForAll(futures);
        startupLogger.info("{} range deletes executed against {}", count, tableName);
    }

    private void executeGaugeValueDeletes(int rollupLevel, String thresholdComparator,
            Instant threshold, Set<GaugeValuePartitionKey> partitionKeys) throws Exception {
        String tableName = "gauge_value_rollup_" + rollupLevel;
        PreparedStatement deletePS = session.prepare("delete from " + tableName
                + " where agent_rollup = ? and gauge_name = ? and capture_time "
                + thresholdComparator + " ?");
        int count = 0;
        List<CompletableFuture<?>> futures = new ArrayList<>();
        for (GaugeValuePartitionKey partitionKey : partitionKeys) {
            int i = 0;
            BoundStatement boundStatement = deletePS.bind()
                .setString(i++, partitionKey.agentRollupId())
                .setString(i++, partitionKey.gaugeName())
                .setInstant(i++, threshold);
            futures.add(session.writeAsync(boundStatement).toCompletableFuture());
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

    @Value.Immutable
    interface GaugeValuePartitionKey {
        String agentRollupId();
        String gaugeName();
    }
}
