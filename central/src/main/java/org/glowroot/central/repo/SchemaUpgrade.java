/*
 * Copyright 2016-2017 the original author or authors.
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

import java.util.List;
import java.util.Map.Entry;
import java.util.Set;

import javax.annotation.Nullable;

import com.datastax.driver.core.BoundStatement;
import com.datastax.driver.core.KeyspaceMetadata;
import com.datastax.driver.core.PreparedStatement;
import com.datastax.driver.core.ResultSet;
import com.datastax.driver.core.Row;
import com.datastax.driver.core.Session;
import com.datastax.driver.core.TableMetadata;
import com.datastax.driver.core.exceptions.InvalidConfigurationInQueryException;
import com.datastax.driver.core.exceptions.NoHostAvailableException;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Stopwatch;
import com.google.common.collect.ArrayListMultimap;
import com.google.common.collect.ListMultimap;
import com.google.common.collect.Lists;
import com.google.common.collect.Multimaps;
import com.google.common.collect.Sets;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.glowroot.common.config.CentralStorageConfig;
import org.glowroot.common.config.PermissionParser;

import static com.google.common.base.Preconditions.checkNotNull;
import static java.util.concurrent.TimeUnit.DAYS;
import static java.util.concurrent.TimeUnit.HOURS;
import static java.util.concurrent.TimeUnit.SECONDS;

public class SchemaUpgrade {

    private static final Logger logger = LoggerFactory.getLogger(SchemaUpgrade.class);

    // log startup messages using logger name "org.glowroot"
    private static final Logger startupLogger = LoggerFactory.getLogger("org.glowroot");

    private static final int CURR_SCHEMA_VERSION = 14;

    private static final String WITH_LCS =
            "with compaction = { 'class' : 'LeveledCompactionStrategy' }";

    private final Session session;
    private final KeyspaceMetadata keyspace;

    private final PreparedStatement insertPS;
    private final @Nullable Integer initialSchemaVersion;

    public SchemaUpgrade(Session session, KeyspaceMetadata keyspace) {
        this.session = session;
        this.keyspace = keyspace;

        session.execute("create table if not exists schema_version (one int, schema_version int,"
                + " primary key (one)) " + WITH_LCS);
        insertPS =
                session.prepare("insert into schema_version (one, schema_version) values (?, ?)");
        initialSchemaVersion = getSchemaVersion(session, keyspace);
    }

    public @Nullable Integer getInitialSchemaVersion() {
        return initialSchemaVersion;
    }

    public void upgrade() throws InterruptedException {
        checkNotNull(initialSchemaVersion);
        if (initialSchemaVersion == CURR_SCHEMA_VERSION) {
            return;
        }
        startupLogger.info("upgrading cassandra schema from version {}...", initialSchemaVersion);
        // 0.9.1 to 0.9.2
        if (initialSchemaVersion < 2) {
            renameAgentColumnFromSystemInfoToEnvironment();
            updateSchemaVersion(2);
        }
        if (initialSchemaVersion < 3) {
            updateRoles();
            updateSchemaVersion(3);
        }
        if (initialSchemaVersion < 4) {
            addConfigUpdateColumns();
            updateSchemaVersion(4);
        }
        // 0.9.2 to 0.9.3
        if (initialSchemaVersion < 6) {
            revertCompressionChunkLength();
            addTraceEntryColumns();
            updateSchemaVersion(6);
        }
        // 0.9.5 to 0.9.6
        if (initialSchemaVersion < 7) {
            renameServerConfigTable();
            updateSchemaVersion(7);
        }
        if (initialSchemaVersion < 8) {
            addAgentOneTable();
            updateSchemaVersion(8);
        }
        if (initialSchemaVersion < 9) {
            addAgentRollupColumn();
            updateSchemaVersion(9);
        }
        // 0.9.6 to 0.9.7
        if (initialSchemaVersion < 11) {
            updateDtcsTwcsGcSeconds();
            updateSchemaVersion(11);
        }
        if (initialSchemaVersion < 12) {
            updateGcSeconds();
            updateSchemaVersion(12);
        }
        if (initialSchemaVersion < 13) {
            updateAgentRollup();
            updateSchemaVersion(13);
        }
        if (initialSchemaVersion < 14) {
            addTracePointPartialColumn();
            updateSchemaVersion(14);
        }

        // when adding new schema upgrade, make sure to update CURR_SCHEMA_VERSION above
        startupLogger.info("upgraded cassandra schema to version {}", CURR_SCHEMA_VERSION);
    }

    public void updateSchemaVersionToCurent() {
        updateSchemaVersion(CURR_SCHEMA_VERSION);
    }

    public void updateToMoreRecentCassandraOptions(CentralStorageConfig storageConfig) {
        List<String> snappyTableNames = Lists.newArrayList();
        List<String> dtcsTableNames = Lists.newArrayList();
        List<String> twcsTableNames = Lists.newArrayList();
        for (TableMetadata table : keyspace.getTables()) {
            String compression = table.getOptions().getCompression().get("class");
            if (compression != null
                    && compression.equals("org.apache.cassandra.io.compress.SnappyCompressor")) {
                snappyTableNames.add(compression);
            }
            String compaction = table.getOptions().getCompaction().get("class");
            if (compaction != null && compaction
                    .equals("org.apache.cassandra.db.compaction.DateTieredCompactionStrategy")) {
                dtcsTableNames.add(table.getName());
            }
            if (compaction != null && compaction
                    .equals("org.apache.cassandra.db.compaction.TimeWindowCompactionStrategy")) {
                String windowUnit =
                        table.getOptions().getCompaction().get("compaction_window_unit");
                String windowSize =
                        table.getOptions().getCompaction().get("compaction_window_size");

                int expirationHours = getExpirationHoursForTable(table.getName(), storageConfig);
                if (expirationHours == -1) {
                    // warning already logged above inside getExpirationHoursForTable()
                } else {
                    // this calculation is done to match same calculation below
                    int windowSizeHours = expirationHours / 24;
                    if (!"HOURS".equals(windowUnit)
                            || !Integer.toString(windowSizeHours).equals(windowSize)) {
                        twcsTableNames.add(table.getName());
                    }
                }
            }
        }

        int snappyUpdatedCount = 0;
        for (String tableName : snappyTableNames) {
            session.execute("alter table " + tableName
                    + " with compression = { 'class' : 'LZ4Compressor' }");
            if (snappyUpdatedCount++ == 0) {
                startupLogger.info("upgrading from Snappy to LZ4 compression...");
            }
        }
        if (snappyUpdatedCount > 0) {
            startupLogger.info("upgraded {} tables from Snappy to LZ4 compression",
                    snappyUpdatedCount);
        }

        int dtcsUpdatedCount = 0;
        for (String tableName : dtcsTableNames) {
            try {
                int expirationHours = getExpirationHoursForTable(tableName, storageConfig);
                if (expirationHours == -1) {
                    // warning already logged above inside getExpirationHoursForTable()
                    continue;
                }
                // "Ideally, operators should select a compaction_window_unit and
                // compaction_window_size pair that produces approximately 20-30 windows"
                // (http://cassandra.apache.org/doc/latest/operating/compaction.html)
                int windowSizeHours = expirationHours / 24;
                session.execute("alter table " + tableName + " with compaction = { 'class' :"
                        + " 'TimeWindowCompactionStrategy', 'compaction_window_unit' : 'HOURS',"
                        + " 'compaction_window_size' : '" + windowSizeHours + "' }");
                if (dtcsUpdatedCount++ == 0) {
                    startupLogger.info("upgrading from DateTieredCompactionStrategy to"
                            + " TimeWindowCompactionStrategy compression...");
                }
            } catch (InvalidConfigurationInQueryException e) {
                logger.debug(e.getMessage(), e);
                // TimeWindowCompactionStrategy is only supported by Cassandra 3.8+
                break;
            }
        }
        int twcsUpdatedCount = 0;
        for (String tableName : twcsTableNames) {
            int expirationHours = getExpirationHoursForTable(tableName, storageConfig);
            if (expirationHours == -1) {
                // warning already logged above inside getExpirationHoursForTable()
                continue;
            }
            // "Ideally, operators should select a compaction_window_unit and
            // compaction_window_size pair that produces approximately 20-30 windows"
            // (http://cassandra.apache.org/doc/latest/operating/compaction.html)
            int windowSizeHours = expirationHours / 24;
            session.execute("alter table " + tableName + " with compaction = { 'class' :"
                    + " 'TimeWindowCompactionStrategy', 'compaction_window_unit' : 'HOURS',"
                    + " 'compaction_window_size' : '" + windowSizeHours + "' }");
            if (twcsUpdatedCount++ == 0) {
                startupLogger.info("updating TimeWindowCompactionStrategy compaction windows...");
            }
        }
        if (dtcsUpdatedCount > 0) {
            startupLogger.info("upgraded {} tables from DateTieredCompactionStrategy to"
                    + " TimeWindowCompactionStrategy compaction", dtcsUpdatedCount);
        }
        if (twcsUpdatedCount > 0) {
            startupLogger.info(
                    "updated TimeWindowCompactionStrategy compaction window on {} tables",
                    twcsUpdatedCount);
        }
    }

    private void updateSchemaVersion(int schemaVersion) {
        BoundStatement boundStatement = insertPS.bind();
        boundStatement.setInt(0, 1);
        boundStatement.setInt(1, schemaVersion);
        session.execute(boundStatement);
    }

    private void renameAgentColumnFromSystemInfoToEnvironment() {
        if (!columnExists("agent", "system_info")) {
            // previously failed mid-upgrade prior to updating schema version
            return;
        }
        addColumnIfNotExists("agent", "environment", "blob");
        ResultSet results = session.execute("select agent_id, system_info from agent");
        PreparedStatement preparedStatement =
                session.prepare("insert into agent (agent_id, environment) values (?, ?)");
        for (Row row : results) {
            BoundStatement boundStatement = preparedStatement.bind();
            boundStatement.setString(0, row.getString(0));
            boundStatement.setBytes(1, row.getBytes(1));
            session.execute(boundStatement);
        }
        session.execute("alter table agent drop system_info");
    }

    private void updateRoles() {
        PreparedStatement insertPS =
                session.prepare("insert into role (name, permissions) values (?, ?)");
        ResultSet results = session.execute("select name, permissions from role");
        for (Row row : results) {
            String name = row.getString(0);
            Set<String> permissions = row.getSet(1, String.class);
            Set<String> upgradedPermissions = upgradePermissions(permissions);
            if (upgradedPermissions == null) {
                continue;
            }
            BoundStatement boundStatement = insertPS.bind();
            boundStatement.setString(0, name);
            boundStatement.setSet(1, upgradedPermissions, String.class);
            session.execute(boundStatement);
        }
    }

    private void addConfigUpdateColumns() {
        addColumnIfNotExists("agent", "config_update", "boolean");
        addColumnIfNotExists("agent", "config_update_token", "uuid");
    }

    private void revertCompressionChunkLength() {
        try {
            // try with compression options for Cassandra 3.x
            // see https://docs.datastax.com/en/cql/3.3/cql/cql_reference/compressSubprop.html
            session.execute("alter table trace_entry with compression = {'class':"
                    + " 'org.apache.cassandra.io.compress.LZ4Compressor', 'chunk_length_kb' :"
                    + " 64};");
        } catch (InvalidConfigurationInQueryException e) {
            logger.debug(e.getMessage(), e);
            // try with compression options for Cassandra 2.x
            // see https://docs.datastax.com/en/cql/3.1/cql/cql_reference/compressSubprop.html
            session.execute("alter table trace_entry with compression = {'sstable_compression':"
                    + " 'SnappyCompressor', 'chunk_length_kb' : 64};");
        }
    }

    private void addTraceEntryColumns() {
        addColumnIfNotExists("trace_entry", "shared_query_text_index", "int");
        addColumnIfNotExists("trace_entry", "query_message_prefix", "varchar");
        addColumnIfNotExists("trace_entry", "query_message_suffix", "varchar");
    }

    private void renameServerConfigTable() throws InterruptedException {
        if (!tableExists("server_config")) {
            // previously failed mid-upgrade prior to updating schema version
            return;
        }
        session.execute("create table if not exists central_config (key varchar, value varchar,"
                + " primary key (key)) " + WITH_LCS);
        ResultSet results = session.execute("select key, value from server_config");
        PreparedStatement insertPS =
                session.prepare("insert into central_config (key, value) values (?, ?)");
        for (Row row : results) {
            BoundStatement boundStatement = insertPS.bind();
            boundStatement.setString(0, row.getString(0));
            boundStatement.setString(1, row.getString(1));
            session.execute(boundStatement);
        }
        dropTable("server_config");
    }

    private void addAgentOneTable() throws InterruptedException {
        if (!tableExists("agent_rollup")) {
            // previously failed mid-upgrade prior to updating schema version
            return;
        }
        session.execute("create table if not exists agent_one (one int, agent_id varchar,"
                + " agent_rollup varchar, primary key (one, agent_id)) " + WITH_LCS);
        ResultSet results = session.execute("select agent_rollup from agent_rollup");
        PreparedStatement insertPS =
                session.prepare("insert into agent_one (one, agent_id) values (1, ?)");
        for (Row row : results) {
            BoundStatement boundStatement = insertPS.bind();
            boundStatement.setString(0, row.getString(0));
            session.execute(boundStatement);
        }
        dropTable("agent_rollup");
    }

    private void addAgentRollupColumn() {
        addColumnIfNotExists("agent", "agent_rollup", "varchar");
    }

    private void updateDtcsTwcsGcSeconds() {
        for (TableMetadata table : keyspace.getTables()) {
            String compaction = table.getOptions().getCompaction().get("class");
            if (compaction == null) {
                continue;
            }
            if (compaction.equals("org.apache.cassandra.db.compaction.DateTieredCompactionStrategy")
                    || compaction.equals(
                            "org.apache.cassandra.db.compaction.TimeWindowCompactionStrategy")) {
                // see gc_grace_seconds related comments in Sessions.createTableWithTWCS()
                // for reasoning behind the value of 1 day
                session.execute("alter table " + table.getName() + " with gc_grace_seconds = "
                        + DAYS.toSeconds(1));
            }
        }
    }

    private void updateGcSeconds() {
        // reduce from default 10 days to 3 hours
        //
        // since aggregate and gauge rollup operations are idempotent, any records resurrected after
        // gc_grace_seconds would just create extra work, but not have any other effect
        //
        // 3 hours is chosen to match default max_hint_window_in_ms since hints are stored
        // with a TTL of gc_grace_seconds
        // (see http://www.uberobert.com/cassandra_gc_grace_disables_hinted_handoff)
        long gcGraceSeconds = HOURS.toSeconds(3);

        if (tableExists("aggregate_needs_rollup_from_child")) {
            session.execute("alter table aggregate_needs_rollup_from_child with gc_grace_seconds = "
                    + gcGraceSeconds);
        }
        session.execute(
                "alter table aggregate_needs_rollup_1 with gc_grace_seconds = " + gcGraceSeconds);
        session.execute(
                "alter table aggregate_needs_rollup_2 with gc_grace_seconds = " + gcGraceSeconds);
        session.execute(
                "alter table aggregate_needs_rollup_3 with gc_grace_seconds = " + gcGraceSeconds);
        if (tableExists("gauge_needs_rollup_from_child")) {
            session.execute("alter table gauge_needs_rollup_from_child with gc_grace_seconds = "
                    + gcGraceSeconds);
        }
        session.execute(
                "alter table gauge_needs_rollup_1 with gc_grace_seconds = " + gcGraceSeconds);
        session.execute(
                "alter table gauge_needs_rollup_2 with gc_grace_seconds = " + gcGraceSeconds);
        session.execute(
                "alter table gauge_needs_rollup_3 with gc_grace_seconds = " + gcGraceSeconds);
        session.execute(
                "alter table gauge_needs_rollup_4 with gc_grace_seconds = " + gcGraceSeconds);
    }

    private void updateAgentRollup() throws InterruptedException {
        if (!tableExists("agent_one")) {
            // previously failed mid-upgrade prior to updating schema version
            return;
        }
        session.execute("create table if not exists agent_rollup (one int, agent_rollup_id varchar,"
                + " parent_agent_rollup_id varchar, agent boolean, display varchar,"
                + " last_capture_time timestamp, primary key (one, agent_rollup_id)) " + WITH_LCS);
        ResultSet results = session.execute("select agent_id, agent_rollup from agent_one");
        PreparedStatement insertPS = session.prepare("insert into agent_rollup (one,"
                + " agent_rollup_id, parent_agent_rollup_id, agent) values (1, ?, ?, ?)");
        Set<String> parentAgentRollupIds = Sets.newHashSet();
        for (Row row : results) {
            String agentRollupId = row.getString(0);
            String parentAgentRollupId = row.getString(1);
            BoundStatement boundStatement = insertPS.bind();
            int i = 0;
            boundStatement.setString(i++, agentRollupId);
            boundStatement.setString(i++, parentAgentRollupId);
            boundStatement.setBool(i++, true);
            session.execute(boundStatement);
            if (parentAgentRollupId != null) {
                parentAgentRollupIds.addAll(AgentDao.getAgentRollupIds(parentAgentRollupId));
            }
        }
        for (String parentAgentRollupId : parentAgentRollupIds) {
            int index = parentAgentRollupId.lastIndexOf('/');
            String parentOfParentAgentRollupId =
                    index == -1 ? null : parentAgentRollupId.substring(0, index);
            BoundStatement boundStatement = insertPS.bind();
            int i = 0;
            boundStatement.setString(i++, parentAgentRollupId);
            boundStatement.setString(i++, parentOfParentAgentRollupId);
            boundStatement.setBool(i++, false);
            session.execute(boundStatement);
        }
        session.execute("alter table agent drop agent_rollup");
        dropTable("agent_one");
    }

    private void addTracePointPartialColumn() {
        addColumnIfNotExists("trace_tt_slow_point", "partial", "boolean");
        addColumnIfNotExists("trace_tn_slow_point", "partial", "boolean");
        addColumnIfNotExists("trace_tt_error_point", "partial", "boolean");
        addColumnIfNotExists("trace_tn_error_point", "partial", "boolean");
    }

    private void addColumnIfNotExists(String tableName, String columnName, String cqlType) {
        if (!columnExists(tableName, columnName)) {
            session.execute("alter table " + tableName + " add " + columnName + " " + cqlType);
        }
    }

    private boolean tableExists(String tableName) {
        return keyspace.getTable(tableName) != null;
    }

    private boolean columnExists(String tableName, String columnName) {
        return keyspace.getTable(tableName).getColumn(columnName) != null;
    }

    // drop table can timeout, throwing NoHostAvailableException
    // (see https://github.com/glowroot/glowroot/issues/125)
    private void dropTable(String tableName) throws InterruptedException {
        Stopwatch stopwatch = Stopwatch.createStarted();
        while (stopwatch.elapsed(SECONDS) < 30) {
            try {
                session.execute("drop table if exists " + tableName);
                return;
            } catch (NoHostAvailableException e) {
                logger.debug(e.getMessage(), e);
            }
            Thread.sleep(1000);
        }
        // try one last time and let exception bubble up
        session.execute("drop table if exists " + tableName);
    }

    private static int getExpirationHoursForTable(String tableName,
            CentralStorageConfig storageConfig) {
        if (tableName.startsWith("trace_")) {
            return storageConfig.traceExpirationHours();
        } else if (tableName.startsWith("gauge_value_rollup_")) {
            int rollupLevel = Integer.parseInt(tableName.substring(tableName.lastIndexOf('_') + 1));
            if (rollupLevel == 0) {
                return storageConfig.rollupExpirationHours().get(rollupLevel);
            } else {
                return storageConfig.rollupExpirationHours().get(rollupLevel - 1);
            }
        } else if (tableName.startsWith("aggregate_")) {
            int rollupLevel = Integer.parseInt(tableName.substring(tableName.lastIndexOf('_') + 1));
            return storageConfig.rollupExpirationHours().get(rollupLevel);
        } else if (tableName.equals("heartbeat")) {
            return HeartbeatDao.EXPIRATION_HOURS;
        } else {
            logger.warn("unexpected table: {}", tableName);
            return -1;
        }
    }

    @VisibleForTesting
    static @Nullable Set<String> upgradePermissions(Set<String> permissions) {
        Set<String> updatedPermissions = Sets.newHashSet();
        ListMultimap<String, String> agentPermissions = ArrayListMultimap.create();
        boolean needsUpgrade = false;
        for (String permission : permissions) {
            if (permission.startsWith("agent:")) {
                PermissionParser parser = new PermissionParser(permission);
                parser.parse();
                String perm = parser.getPermission();
                agentPermissions.put(
                        PermissionParser.quoteIfNecessaryAndJoin(parser.getAgentRollupIds()), perm);
                if (perm.equals("agent:view")) {
                    needsUpgrade = true;
                }
            } else if (permission.equals("admin") || permission.startsWith("admin:")) {
                updatedPermissions.add(permission);
            } else {
                logger.error("unexpected permission: {}", permission);
            }
        }
        if (!needsUpgrade) {
            return null;
        }
        for (Entry<String, List<String>> entry : Multimaps.asMap(agentPermissions).entrySet()) {
            List<String> perms = entry.getValue();
            PermissionParser.upgradeAgentPermissions(perms);
            for (String perm : perms) {
                updatedPermissions
                        .add("agent:" + entry.getKey() + ":" + perm.substring("agent:".length()));
            }
        }
        if (updatedPermissions.contains("admin:view")
                && updatedPermissions.contains("admin:edit")) {
            updatedPermissions.remove("admin:view");
            updatedPermissions.remove("admin:edit");
            updatedPermissions.add("admin");
        }
        return updatedPermissions;
    }

    private static @Nullable Integer getSchemaVersion(Session session, KeyspaceMetadata keyspace) {
        ResultSet results =
                session.execute("select schema_version from schema_version where one = 1");
        Row row = results.one();
        if (row != null) {
            return row.getInt(0);
        }
        TableMetadata agentTable = keyspace.getTable("agent");
        if (agentTable == null) {
            // new installation, tables haven't been created yet
            return null;
        }
        if (agentTable.getColumn("system_info") != null) {
            // special case, this is glowroot version 0.9.1, the only version supporting upgrades
            // prior to schema_version table
            return 1;
        }
        // new installation, agent table was created tables haven't been created yet
        return null;
    }
}
