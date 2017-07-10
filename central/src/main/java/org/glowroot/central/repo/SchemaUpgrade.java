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

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Iterator;
import java.util.List;
import java.util.Map.Entry;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;

import javax.annotation.Nullable;

import com.datastax.driver.core.BoundStatement;
import com.datastax.driver.core.KeyspaceMetadata;
import com.datastax.driver.core.PreparedStatement;
import com.datastax.driver.core.ResultSet;
import com.datastax.driver.core.Row;
import com.datastax.driver.core.TableMetadata;
import com.datastax.driver.core.exceptions.InvalidConfigurationInQueryException;
import com.datastax.driver.core.exceptions.NoHostAvailableException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Stopwatch;
import com.google.common.collect.ArrayListMultimap;
import com.google.common.collect.ListMultimap;
import com.google.common.collect.Lists;
import com.google.common.collect.Multimap;
import com.google.common.collect.Multimaps;
import com.google.common.collect.Sets;
import com.google.protobuf.ByteString;
import com.google.protobuf.InvalidProtocolBufferException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.glowroot.central.util.Session;
import org.glowroot.common.config.CentralStorageConfig;
import org.glowroot.common.config.ConfigDefaults;
import org.glowroot.common.config.ImmutableCentralWebConfig;
import org.glowroot.common.config.PermissionParser;
import org.glowroot.common.util.ObjectMappers;
import org.glowroot.common.util.PropertiesFiles;
import org.glowroot.wire.api.model.AgentConfigOuterClass.AgentConfig;
import org.glowroot.wire.api.model.AgentConfigOuterClass.AgentConfig.AdvancedConfig;
import org.glowroot.wire.api.model.AgentConfigOuterClass.AgentConfig.AlertConfig;
import org.glowroot.wire.api.model.AgentConfigOuterClass.AgentConfig.AlertConfig.AlertCondition.HeartbeatCondition;
import org.glowroot.wire.api.model.AgentConfigOuterClass.AgentConfig.AlertConfig.AlertCondition.MetricCondition;
import org.glowroot.wire.api.model.AgentConfigOuterClass.AgentConfig.AlertConfig.AlertCondition.SyntheticMonitorCondition;
import org.glowroot.wire.api.model.AgentConfigOuterClass.AgentConfig.OldAlertConfig;
import org.glowroot.wire.api.model.AgentConfigOuterClass.AgentConfig.UiConfig;
import org.glowroot.wire.api.model.Proto.OptionalInt32;

import static com.google.common.base.Preconditions.checkNotNull;
import static java.util.concurrent.TimeUnit.DAYS;
import static java.util.concurrent.TimeUnit.HOURS;
import static java.util.concurrent.TimeUnit.SECONDS;

public class SchemaUpgrade {

    private static final Logger logger = LoggerFactory.getLogger(SchemaUpgrade.class);

    // log startup messages using logger name "org.glowroot"
    private static final Logger startupLogger = LoggerFactory.getLogger("org.glowroot");

    private static final ObjectMapper mapper = ObjectMappers.create();

    private static final int CURR_SCHEMA_VERSION = 28;

    private static final String WITH_LCS =
            "with compaction = { 'class' : 'LeveledCompactionStrategy' }";

    private final Session session;
    private final KeyspaceMetadata keyspaceMetadata;
    private final boolean servlet;

    private final PreparedStatement insertPS;
    private final @Nullable Integer initialSchemaVersion;

    private boolean reloadCentralConfiguration;

    public SchemaUpgrade(Session session, KeyspaceMetadata keyspaceMetadata, boolean servlet)
            throws Exception {
        this.session = session;
        this.keyspaceMetadata = keyspaceMetadata;
        this.servlet = servlet;

        session.execute("create table if not exists schema_version (one int,"
                + " schema_version int, primary key (one)) " + WITH_LCS);
        insertPS =
                session.prepare("insert into schema_version (one, schema_version) values (?, ?)");
        initialSchemaVersion = getSchemaVersion(session, keyspaceMetadata);
    }

    public @Nullable Integer getInitialSchemaVersion() {
        return initialSchemaVersion;
    }

    public void upgrade() throws Exception {
        checkNotNull(initialSchemaVersion);
        if (initialSchemaVersion == CURR_SCHEMA_VERSION) {
            return;
        }
        if (initialSchemaVersion > CURR_SCHEMA_VERSION) {
            startupLogger.warn("running an older version of glowroot central on a newer glowroot"
                    + " central schema (expecting glowroot central schema version <= {} but found"
                    + " version {}), this could be problematic", CURR_SCHEMA_VERSION,
                    initialSchemaVersion);
            return;
        }
        startupLogger.info("upgrading glowroot central schema from version {} to version {} ...",
                initialSchemaVersion, CURR_SCHEMA_VERSION);
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
        // 0.9.9 to 0.9.10
        if (initialSchemaVersion < 15) {
            splitUpAgentTable();
            updateSchemaVersion(15);
        }
        if (initialSchemaVersion < 16) {
            initialPopulationOfConfigForRollups();
            updateSchemaVersion(16);
        }
        if (initialSchemaVersion < 17) {
            redoOnTriggeredAlertTable();
            updateSchemaVersion(17);
        }
        // 0.9.10 to 0.9.11
        if (initialSchemaVersion < 18) {
            addSyntheticMonitorAndAlertPermissions();
            updateSchemaVersion(18);
        }
        if (initialSchemaVersion < 19) {
            anotherRedoOnTriggeredAlertTable();
            updateSchemaVersion(19);
        }
        // 0.9.15 to 0.9.16
        if (initialSchemaVersion < 20) {
            yetAnotherRedoOnTriggeredAlertTable();
            updateSchemaVersion(20);
        }
        if (initialSchemaVersion < 21) {
            updateWebConfig();
            updateSchemaVersion(21);
        }
        // 0.9.16 to 0.9.17
        if (initialSchemaVersion < 22) {
            removeInvalidAgentRollupRows();
            updateSchemaVersion(22);
        }
        // 0.9.17 to 0.9.18
        if (initialSchemaVersion < 23) {
            renameConfigTable();
            updateSchemaVersion(23);
        }
        if (initialSchemaVersion < 24) {
            upgradeAlertConfigs();
            updateSchemaVersion(24);
        }
        if (initialSchemaVersion < 25) {
            addAggregateThroughputColumn();
            updateSchemaVersion(25);
        }
        if (initialSchemaVersion < 26) {
            // this is needed due to change from OldAlertConfig to AlertConfig in schema version 24
            yetAnotherRedoOnTriggeredAlertTable();
            updateSchemaVersion(26);
        }
        // 0.9.19 to 0.9.20
        if (initialSchemaVersion < 27) {
            updateRolePermissionName();
            updateSchemaVersion(27);
        }
        if (initialSchemaVersion < 28) {
            updateSmtpConfig();
            updateSchemaVersion(28);
        }

        // when adding new schema upgrade, make sure to update CURR_SCHEMA_VERSION above
        startupLogger.info("upgraded glowroot central schema from version {} to version {}",
                initialSchemaVersion, CURR_SCHEMA_VERSION);
    }

    public boolean reloadCentralConfiguration() {
        return reloadCentralConfiguration;
    }

    public void updateSchemaVersionToCurent() throws Exception {
        updateSchemaVersion(CURR_SCHEMA_VERSION);
    }

    public int getCurrentSchemaVersion() {
        return CURR_SCHEMA_VERSION;
    }

    public void updateToMoreRecentCassandraOptions(CentralStorageConfig storageConfig)
            throws Exception {
        List<String> snappyTableNames = Lists.newArrayList();
        List<String> dtcsTableNames = Lists.newArrayList();
        List<String> twcsTableNames = Lists.newArrayList();
        for (TableMetadata table : keyspaceMetadata.getTables()) {
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
                startupLogger.info("upgrading from Snappy to LZ4 compression ...");
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
                session.execute("alter table " + tableName + " with compaction"
                        + " = { 'class' : 'TimeWindowCompactionStrategy', 'compaction_window_unit'"
                        + " : 'HOURS', 'compaction_window_size' : '" + windowSizeHours + "' }");
                if (dtcsUpdatedCount++ == 0) {
                    startupLogger.info("upgrading from DateTieredCompactionStrategy to"
                            + " TimeWindowCompactionStrategy compression ...");
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
                startupLogger.info("updating TimeWindowCompactionStrategy compaction windows ...");
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

    private void updateSchemaVersion(int schemaVersion) throws Exception {
        BoundStatement boundStatement = insertPS.bind();
        boundStatement.setInt(0, 1);
        boundStatement.setInt(1, schemaVersion);
        session.execute(boundStatement);
    }

    private void renameAgentColumnFromSystemInfoToEnvironment() throws Exception {
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

    private void updateRoles() throws Exception {
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

    private void addConfigUpdateColumns() throws Exception {
        addColumnIfNotExists("agent", "config_update", "boolean");
        addColumnIfNotExists("agent", "config_update_token", "uuid");
    }

    private void revertCompressionChunkLength() throws Exception {
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
            session.execute("alter table trace_entry with compression"
                    + " = {'sstable_compression': 'SnappyCompressor', 'chunk_length_kb' : 64};");
        }
    }

    private void addTraceEntryColumns() throws Exception {
        addColumnIfNotExists("trace_entry", "shared_query_text_index", "int");
        addColumnIfNotExists("trace_entry", "query_message_prefix", "varchar");
        addColumnIfNotExists("trace_entry", "query_message_suffix", "varchar");
    }

    private void renameServerConfigTable() throws Exception {
        if (!tableExists("server_config")) {
            // previously failed mid-upgrade prior to updating schema version
            return;
        }
        session.execute("create table if not exists central_config (key varchar,"
                + " value varchar, primary key (key)) " + WITH_LCS);
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

    private void addAgentOneTable() throws Exception {
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

    private void addAgentRollupColumn() throws Exception {
        addColumnIfNotExists("agent", "agent_rollup", "varchar");
    }

    private void updateDtcsTwcsGcSeconds() throws Exception {
        for (TableMetadata table : keyspaceMetadata.getTables()) {
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

    private void updateGcSeconds() throws Exception {
        // reduce from default 10 days to 3 hours
        //
        // since rollup operations are idempotent, any records resurrected after gc_grace_seconds
        // would just create extra work, but not have any other effect
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

    private void updateAgentRollup() throws Exception {
        if (!tableExists("agent_one")) {
            // previously failed mid-upgrade prior to updating schema version
            return;
        }
        session.execute("create table if not exists agent_rollup (one int,"
                + " agent_rollup_id varchar, parent_agent_rollup_id varchar, agent boolean,"
                + " display varchar, last_capture_time timestamp, primary key (one,"
                + " agent_rollup_id)) " + WITH_LCS);
        ResultSet results =
                session.execute("select agent_id, agent_rollup from agent_one");
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
                parentAgentRollupIds.addAll(AgentRollupDao.getAgentRollupIds(parentAgentRollupId));
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

    private void addTracePointPartialColumn() throws Exception {
        addColumnIfNotExists("trace_tt_slow_point", "partial", "boolean");
        addColumnIfNotExists("trace_tn_slow_point", "partial", "boolean");
        addColumnIfNotExists("trace_tt_error_point", "partial", "boolean");
        addColumnIfNotExists("trace_tn_error_point", "partial", "boolean");
    }

    private void splitUpAgentTable() throws Exception {
        session.execute("create table if not exists config (agent_rollup_id varchar,"
                + " config blob, config_update boolean, config_update_token uuid, primary key"
                + " (agent_rollup_id)) " + WITH_LCS);
        session.execute("create table if not exists environment (agent_id varchar,"
                + " environment blob, primary key (agent_id)) " + WITH_LCS);

        ResultSet results =
                session.execute("select agent_rollup_id, agent from agent_rollup where one = 1");
        List<String> agentIds = Lists.newArrayList();
        for (Row row : results) {
            if (row.getBool(1)) {
                agentIds.add(checkNotNull(row.getString(0)));
            }
        }
        PreparedStatement readPS = session.prepare("select environment, config, config_update,"
                + " config_update_token from agent where agent_id = ?");
        PreparedStatement insertEnvironmentPS =
                session.prepare("insert into environment (agent_id, environment) values (?, ?)");
        PreparedStatement insertConfigPS = session.prepare("insert into config (agent_rollup_id,"
                + " config, config_update, config_update_token) values (?, ?, ?, ?)");
        for (String agentId : agentIds) {
            BoundStatement boundStatement = readPS.bind();
            boundStatement.setString(0, agentId);
            results = session.execute(boundStatement);
            Row row = results.one();
            if (row == null) {
                logger.warn("agent record not found for agent id: {}", agentId);
                continue;
            }
            int i = 0;
            ByteBuffer environmentBytes = checkNotNull(row.getBytes(i++));
            ByteBuffer configBytes = checkNotNull(row.getBytes(i++));
            boolean configUpdate = row.getBool(i++);
            UUID configUpdateToken = row.getUUID(i++);

            boundStatement = insertEnvironmentPS.bind();
            boundStatement.setString(0, agentId);
            boundStatement.setBytes(1, environmentBytes);
            session.execute(boundStatement);

            boundStatement = insertConfigPS.bind();
            i = 0;
            boundStatement.setString(i++, agentId);
            boundStatement.setBytes(i++, configBytes);
            boundStatement.setBool(i++, configUpdate);
            boundStatement.setUUID(i++, configUpdateToken);
            session.execute(boundStatement);
        }
        dropTable("agent");
    }

    private void initialPopulationOfConfigForRollups() throws Exception {
        ResultSet results = session.execute("select agent_rollup_id,"
                + " parent_agent_rollup_id, agent from agent_rollup where one = 1");
        List<String> agentRollupIds = Lists.newArrayList();
        Multimap<String, String> childAgentIds = ArrayListMultimap.create();
        for (Row row : results) {
            int i = 0;
            String agentRollupId = row.getString(i++);
            String parentAgentRollupId = row.getString(i++);
            boolean agent = row.getBool(i++);
            if (!agent) {
                agentRollupIds.add(checkNotNull(agentRollupId));
            }
            if (parentAgentRollupId != null) {
                childAgentIds.put(parentAgentRollupId, agentRollupId);
            }
        }

        AgentConfig defaultAgentConfig = AgentConfig.newBuilder()
                .setUiConfig(UiConfig.newBuilder()
                        .setDefaultDisplayedTransactionType(
                                ConfigDefaults.DEFAULT_DISPLAYED_TRANSACTION_TYPE)
                        .addDefaultDisplayedPercentile(
                                ConfigDefaults.DEFAULT_DISPLAYED_PERCENTILE_1)
                        .addDefaultDisplayedPercentile(
                                ConfigDefaults.DEFAULT_DISPLAYED_PERCENTILE_2)
                        .addDefaultDisplayedPercentile(
                                ConfigDefaults.DEFAULT_DISPLAYED_PERCENTILE_3))
                .setAdvancedConfig(AdvancedConfig.newBuilder()
                        .setMaxAggregateQueriesPerType(OptionalInt32.newBuilder()
                                .setValue(ConfigDefaults.MAX_AGGREGATE_QUERIES_PER_TYPE))
                        .setMaxAggregateServiceCallsPerType(OptionalInt32.newBuilder()
                                .setValue(ConfigDefaults.MAX_AGGREGATE_SERVICE_CALLS_PER_TYPE)))
                .build();

        PreparedStatement readPS =
                session.prepare("select config from config where agent_rollup_id = ?");
        PreparedStatement insertPS =
                session.prepare("insert into config (agent_rollup_id, config) values (?, ?)");
        for (String agentRollupId : agentRollupIds) {
            Iterator<String> iterator = childAgentIds.get(agentRollupId).iterator();
            if (!iterator.hasNext()) {
                logger.warn("could not find a child agent for rollup: {}", agentRollupId);
                BoundStatement boundStatement = insertPS.bind();
                boundStatement.setString(0, agentRollupId);
                boundStatement.setBytes(1, ByteBuffer.wrap(defaultAgentConfig.toByteArray()));
                session.execute(boundStatement);
                continue;
            }
            String childAgentId = iterator.next();
            BoundStatement boundStatement = readPS.bind();
            boundStatement.setString(0, childAgentId);
            Row row = session.execute(boundStatement).one();

            boundStatement = insertPS.bind();
            boundStatement.setString(0, agentRollupId);
            if (row == null) {
                logger.warn("could not find config for agent id: {}", childAgentId);
                boundStatement.setBytes(1, ByteBuffer.wrap(defaultAgentConfig.toByteArray()));
            } else {
                try {
                    AgentConfig agentConfig = AgentConfig
                            .parseFrom(ByteString.copyFrom(checkNotNull(row.getBytes(0))));
                    AdvancedConfig advancedConfig = agentConfig.getAdvancedConfig();
                    AgentConfig rollupAgentConfig = AgentConfig.newBuilder()
                            .setUiConfig(agentConfig.getUiConfig())
                            .setAdvancedConfig(AdvancedConfig.newBuilder()
                                    .setMaxAggregateQueriesPerType(
                                            advancedConfig.getMaxAggregateQueriesPerType())
                                    .setMaxAggregateServiceCallsPerType(
                                            advancedConfig.getMaxAggregateServiceCallsPerType()))
                            .build();
                    boundStatement.setBytes(1, ByteBuffer.wrap(rollupAgentConfig.toByteArray()));
                } catch (InvalidProtocolBufferException e) {
                    logger.error(e.getMessage(), e);
                    boundStatement.setBytes(1, ByteBuffer.wrap(defaultAgentConfig.toByteArray()));
                }
            }
            session.execute(boundStatement);
        }
    }

    private void redoOnTriggeredAlertTable() throws Exception {
        if (columnExists("triggered_alert", "alert_config_id")) {
            // previously failed mid-upgrade prior to updating schema version
            return;
        }
        dropTable("triggered_alert");
        session.execute("create table if not exists triggered_alert"
                + " (agent_rollup_id varchar, alert_config_id varchar, primary key"
                + " (agent_rollup_id, alert_config_id)) " + WITH_LCS);
    }

    private void addSyntheticMonitorAndAlertPermissions() throws Exception {
        PreparedStatement insertPS =
                session.prepare("insert into role (name, permissions) values (?, ?)");
        ResultSet results = session.execute("select name, permissions from role");
        for (Row row : results) {
            String name = row.getString(0);
            Set<String> permissions = row.getSet(1, String.class);
            Set<String> permissionsToBeAdded = upgradePermissions2(permissions);
            if (permissionsToBeAdded.isEmpty()) {
                continue;
            }
            permissions.addAll(permissionsToBeAdded);
            BoundStatement boundStatement = insertPS.bind();
            boundStatement.setString(0, name);
            boundStatement.setSet(1, permissions, String.class);
            session.execute(boundStatement);
        }
    }

    private void anotherRedoOnTriggeredAlertTable() throws Exception {
        if (columnExists("triggered_alert", "alert_id")) {
            // previously failed mid-upgrade prior to updating schema version
            return;
        }
        dropTable("triggered_alert");
        session.execute("create table if not exists triggered_alert (agent_rollup_id"
                + " varchar, alert_id varchar, primary key (agent_rollup_id, alert_id)) "
                + WITH_LCS);
    }

    private void yetAnotherRedoOnTriggeredAlertTable() throws Exception {
        dropTable("triggered_alert");
        session.execute("create table if not exists triggered_alert (agent_rollup_id"
                + " varchar, alert_condition blob, primary key (agent_rollup_id, alert_condition)) "
                + WITH_LCS);
    }

    private void updateWebConfig() throws Exception {
        ResultSet results =
                session.execute("select value from central_config where key = 'web'");
        Row row = results.one();
        JsonNode webConfigNode;
        if (row == null) {
            webConfigNode = mapper.createObjectNode();
        } else {
            String webConfigText = row.getString(0);
            if (webConfigText == null) {
                webConfigNode = mapper.createObjectNode();
            } else {
                webConfigNode = mapper.readTree(webConfigText);
            }
        }
        if (!servlet && updateCentralConfigurationPropertiesFile(webConfigNode)) {
            reloadCentralConfiguration = true;
        }
        ImmutableCentralWebConfig.Builder builder = ImmutableCentralWebConfig.builder();
        JsonNode sessionTimeoutMinutesNode = webConfigNode.get("sessionTimeoutMinutes");
        if (sessionTimeoutMinutesNode != null) {
            builder.sessionTimeoutMinutes(sessionTimeoutMinutesNode.intValue());
        }
        JsonNode sessionCookieNameNode = webConfigNode.get("sessionCookieName");
        if (sessionCookieNameNode != null) {
            builder.sessionCookieName(sessionCookieNameNode.asText());
        }
        String updatedWebConfigText = mapper.writeValueAsString(builder.build());
        PreparedStatement preparedStatement =
                session.prepare("insert into central_config (key, value) values ('web', ?)");
        BoundStatement boundStatement = preparedStatement.bind();
        boundStatement.setString(0, updatedWebConfigText);
        session.execute(boundStatement);
    }

    private void removeInvalidAgentRollupRows() throws Exception {
        ResultSet results =
                session.execute("select agent_rollup_id, agent from agent_rollup");
        PreparedStatement deletePS =
                session.prepare("delete from agent_rollup where one = 1 and agent_rollup_id = ?");
        for (Row row : results) {
            if (row.isNull(1)) {
                BoundStatement boundStatement = deletePS.bind();
                boundStatement.setString(0, checkNotNull(row.getString(0)));
                session.execute(boundStatement);
            }
        }
    }

    private void renameConfigTable() throws Exception {
        if (!tableExists("config")) {
            // previously failed mid-upgrade prior to updating schema version
            return;
        }
        session.execute("create table if not exists agent_config (agent_rollup_id"
                + " varchar, config blob, config_update boolean, config_update_token uuid,"
                + " primary key (agent_rollup_id)) " + WITH_LCS);
        ResultSet results = session.execute("select agent_rollup_id, config,"
                + " config_update, config_update_token from config");
        PreparedStatement insertPS =
                session.prepare("insert into agent_config (agent_rollup_id, config, config_update,"
                        + " config_update_token) values (?, ?, ?, ?)");
        for (Row row : results) {
            BoundStatement boundStatement = insertPS.bind();
            boundStatement.setString(0, row.getString(0));
            boundStatement.setBytes(1, row.getBytes(1));
            boundStatement.setBool(2, row.getBool(2));
            boundStatement.setUUID(3, row.getUUID(3));
            session.execute(boundStatement);
        }
        dropTable("config");
    }

    private void upgradeAlertConfigs() throws Exception {
        PreparedStatement readPS =
                session.prepare("select agent_rollup_id, config from agent_config");
        PreparedStatement insertPS = session.prepare("insert into agent_config (agent_rollup_id,"
                + " config, config_update, config_update_token) values (?, ?, ?, ?)");
        BoundStatement boundStatement = readPS.bind();
        ResultSet results = session.execute(boundStatement);
        for (Row row : results) {
            String agentRollupId = row.getString(0);
            AgentConfig oldAgentConfig;
            try {
                oldAgentConfig =
                        AgentConfig.parseFrom(ByteString.copyFrom(checkNotNull(row.getBytes(1))));
            } catch (InvalidProtocolBufferException e) {
                logger.error(e.getMessage(), e);
                continue;
            }
            List<OldAlertConfig> oldAlertConfigs = oldAgentConfig.getOldAlertConfigList();
            if (oldAlertConfigs.isEmpty()) {
                continue;
            }
            AgentConfig agentConfig = upgradeOldAgentConfig(oldAgentConfig);
            boundStatement = insertPS.bind();
            boundStatement.setString(0, agentRollupId);
            boundStatement.setBytes(1, ByteBuffer.wrap(agentConfig.toByteArray()));
            session.execute(boundStatement);
        }
    }

    private void addAggregateThroughputColumn() throws Exception {
        addColumnIfNotExists("aggregate_tt_throughput_rollup_0", "error_count", "bigint");
        addColumnIfNotExists("aggregate_tt_throughput_rollup_1", "error_count", "bigint");
        addColumnIfNotExists("aggregate_tt_throughput_rollup_2", "error_count", "bigint");
        addColumnIfNotExists("aggregate_tt_throughput_rollup_3", "error_count", "bigint");
        addColumnIfNotExists("aggregate_tn_throughput_rollup_0", "error_count", "bigint");
        addColumnIfNotExists("aggregate_tn_throughput_rollup_1", "error_count", "bigint");
        addColumnIfNotExists("aggregate_tn_throughput_rollup_2", "error_count", "bigint");
        addColumnIfNotExists("aggregate_tn_throughput_rollup_3", "error_count", "bigint");
    }

    private void updateRolePermissionName() throws Exception {
        PreparedStatement insertPS =
                session.prepare("insert into role (name, permissions) values (?, ?)");
        ResultSet results = session.execute("select name, permissions from role");
        for (Row row : results) {
            String name = row.getString(0);
            Set<String> permissions = row.getSet(1, String.class);
            boolean updated = false;
            Set<String> upgradedPermissions = Sets.newHashSet();
            for (String permission : permissions) {
                PermissionParser parser = new PermissionParser(permission);
                parser.parse();
                if (parser.getPermission().equals("agent:alert")) {
                    upgradedPermissions.add("agent:"
                            + PermissionParser.quoteIfNeededAndJoin(parser.getAgentRollupIds())
                            + ":incident");
                    updated = true;
                } else {
                    upgradedPermissions.add(permission);
                }
            }
            if (updated) {
                BoundStatement boundStatement = insertPS.bind();
                boundStatement.setString(0, name);
                boundStatement.setSet(1, upgradedPermissions, String.class);
                session.execute(boundStatement);
            }
        }
    }

    private void updateSmtpConfig() throws Exception {
        ResultSet results =
                session.execute("select value from central_config where key = 'smtp'");
        Row row = results.one();
        if (row == null) {
            return;
        }
        String smtpConfigText = row.getString(0);
        if (smtpConfigText == null) {
            return;
        }
        JsonNode jsonNode = mapper.readTree(smtpConfigText);
        if (jsonNode == null || !jsonNode.isObject()) {
            return;
        }
        ObjectNode smtpConfigNode = (ObjectNode) jsonNode;
        JsonNode sslNode = smtpConfigNode.remove("ssl");
        if (sslNode != null && sslNode.isBoolean() && sslNode.asBoolean()) {
            smtpConfigNode.put("connectionSecurity", "ssl-tls");
        }
        String updatedWebConfigText = mapper.writeValueAsString(smtpConfigNode);
        PreparedStatement preparedStatement =
                session.prepare("insert into central_config (key, value) values ('web', ?)");
        BoundStatement boundStatement = preparedStatement.bind();
        boundStatement.setString(0, updatedWebConfigText);
        session.execute(boundStatement);
    }

    private void addColumnIfNotExists(String tableName, String columnName, String cqlType)
            throws Exception {
        if (!columnExists(tableName, columnName)) {
            session.execute("alter table " + tableName + " add " + columnName + " " + cqlType);
        }
    }

    private boolean tableExists(String tableName) {
        return keyspaceMetadata.getTable(tableName) != null;
    }

    private boolean columnExists(String tableName, String columnName) {
        return keyspaceMetadata.getTable(tableName).getColumn(columnName) != null;
    }

    // drop table can timeout, throwing NoHostAvailableException
    // (see https://github.com/glowroot/glowroot/issues/125)
    private void dropTable(String tableName) throws Exception {
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

    public static AgentConfig upgradeOldAgentConfig(AgentConfig oldAgentConfig) {
        AgentConfig.Builder builder = oldAgentConfig.toBuilder()
                .clearOldAlertConfig();
        for (OldAlertConfig oldAlertConfig : oldAgentConfig.getOldAlertConfigList()) {
            AlertConfig.Builder alertConfigBuilder = AlertConfig.newBuilder();
            switch (oldAlertConfig.getKind()) {
                case TRANSACTION:
                    alertConfigBuilder.getConditionBuilder().setMetricCondition(
                            createTransactionTimeCondition(oldAlertConfig));
                    break;
                case GAUGE:
                    alertConfigBuilder.getConditionBuilder().setMetricCondition(
                            createGaugeCondition(oldAlertConfig));
                    break;
                case SYNTHETIC_MONITOR:
                    alertConfigBuilder.getConditionBuilder().setSyntheticMonitorCondition(
                            createSyntheticMonitorCondition(oldAlertConfig));
                    break;
                case HEARTBEAT:
                    alertConfigBuilder.getConditionBuilder().setHeartbeatCondition(
                            createHeartbeatCondition(oldAlertConfig));
                    break;
                default:
                    logger.error("unexpected alert kind: {}", oldAlertConfig.getKind());
                    continue;
            }
            alertConfigBuilder.getNotificationBuilder().getEmailNotificationBuilder()
                    .addAllEmailAddress(oldAlertConfig.getEmailAddressList());
            builder.addAlertConfig(alertConfigBuilder);
        }
        return builder.build();
    }

    private static MetricCondition createTransactionTimeCondition(
            OldAlertConfig oldAlertConfig) {
        return MetricCondition.newBuilder()
                .setMetric("transaction:x-percentile")
                .setTransactionType(oldAlertConfig.getTransactionType())
                .setPercentile(oldAlertConfig.getTransactionPercentile())
                .setThreshold(oldAlertConfig.getThresholdMillis().getValue())
                .setTimePeriodSeconds(oldAlertConfig.getTimePeriodSeconds())
                .setMinTransactionCount(oldAlertConfig.getMinTransactionCount().getValue())
                .build();
    }

    private static MetricCondition createGaugeCondition(OldAlertConfig oldAlertConfig) {
        return MetricCondition.newBuilder()
                .setMetric("gauge:" + oldAlertConfig.getGaugeName())
                .setThreshold(oldAlertConfig.getGaugeThreshold().getValue())
                .setTimePeriodSeconds(oldAlertConfig.getTimePeriodSeconds())
                .build();
    }

    private static SyntheticMonitorCondition createSyntheticMonitorCondition(
            OldAlertConfig oldAlertConfig) {
        return SyntheticMonitorCondition.newBuilder()
                .setSyntheticMonitorId(oldAlertConfig.getSyntheticMonitorId())
                .setThresholdMillis(oldAlertConfig.getThresholdMillis().getValue())
                .build();
    }

    private static HeartbeatCondition createHeartbeatCondition(OldAlertConfig oldAlertConfig) {
        return HeartbeatCondition.newBuilder()
                .setTimePeriodSeconds(oldAlertConfig.getTimePeriodSeconds())
                .build();
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
        } else if (tableName.startsWith("aggregate_") || tableName.startsWith("synthetic_")) {
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
                        PermissionParser.quoteIfNeededAndJoin(parser.getAgentRollupIds()), perm);
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
            PermissionParser.upgradeAgentPermissionsFrom_0_9_1_to_0_9_2(perms);
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

    @VisibleForTesting
    static Set<String> upgradePermissions2(Set<String> permissions) {
        Set<String> permissionsToBeAdded = Sets.newHashSet();
        for (String permission : permissions) {
            if (!permission.startsWith("agent:")) {
                continue;
            }
            PermissionParser parser = new PermissionParser(permission);
            parser.parse();
            String perm = parser.getPermission();
            if (perm.equals("agent:transaction")) {
                permissionsToBeAdded.add("agent:"
                        + PermissionParser.quoteIfNeededAndJoin(parser.getAgentRollupIds())
                        + ":syntheticMonitor");
                permissionsToBeAdded.add("agent:"
                        + PermissionParser.quoteIfNeededAndJoin(parser.getAgentRollupIds())
                        + ":alert");
            }
        }
        return permissionsToBeAdded;
    }

    private static boolean updateCentralConfigurationPropertiesFile(JsonNode webConfigNode)
            throws IOException {
        String bindAddressText = "";
        JsonNode bindAddressNode = webConfigNode.get("bindAddress");
        if (bindAddressNode != null && !bindAddressNode.asText().equals("0.0.0.0")) {
            bindAddressText = bindAddressNode.asText();
        }
        String portText = "";
        JsonNode portNode = webConfigNode.get("port");
        if (portNode != null && portNode.intValue() != 4000) {
            portText = portNode.asText();
        }
        String httpsText = "";
        JsonNode httpsNode = webConfigNode.get("https");
        if (httpsNode != null && httpsNode.booleanValue()) {
            httpsText = "true";
        }
        String contextPathText = "";
        JsonNode contextPathNode = webConfigNode.get("contextPath");
        if (contextPathNode != null && !contextPathNode.asText().equals("/")) {
            contextPathText = contextPathNode.asText();
        }
        File propFile = new File("glowroot-central.properties");
        if (!propFile.exists()) {
            startupLogger.warn("glowroot-central.properties file does not exist, so not populating"
                    + " ui properties");
            return false;
        }
        Properties props = PropertiesFiles.load(propFile);
        StringBuilder sb = new StringBuilder();
        if (!props.containsKey("ui.bindAddress")) {
            sb.append("\n");
            sb.append("# default is ui.bindAddress=0.0.0.0\n");
            sb.append("ui.bindAddress=");
            sb.append(bindAddressText);
            sb.append("\n");
        }
        if (!props.containsKey("ui.port")) {
            sb.append("\n");
            sb.append("# default is ui.port=4000\n");
            sb.append("ui.port=");
            sb.append(portText);
            sb.append("\n");
        }
        if (!props.containsKey("ui.https")) {
            sb.append("\n");
            sb.append("# default is to serve the UI over http\n");
            sb.append("# set this to \"true\" to serve the UI over https\n");
            sb.append("# the SSL certificate and private key to be used must be placed in the same"
                    + " directory as this\n");
            sb.append("# properties file, with filenames \"certificate.pem\" and \"private.pem\","
                    + " and the private key must not\n");
            sb.append("# have a passphrase.\n");
            sb.append("# (for example, a self signed certificate can be generated at the command"
                    + " line using\n");
            sb.append("# \"openssl req -new -x509 -nodes -days 365 -out certificate.pem -keyout"
                    + " private.pem\")\n");
            sb.append("ui.https=");
            sb.append(httpsText);
            sb.append("\n");
        }
        if (!props.containsKey("ui.contextPath")) {
            sb.append("\n");
            sb.append("# default is ui.contextPath=/\n");
            sb.append("# this only needs to be changed if reverse proxying the UI behind a non-root"
                    + " context path\n");
            sb.append("ui.contextPath=");
            sb.append(contextPathText);
            sb.append("\n");
        }
        if (sb.length() == 0) {
            // glowroot-central.properties file has been updated
            return false;
        }
        if (props.containsKey("jgroups.configurationFile")) {
            startupLogger.error("When running in a cluster, you must manually upgrade"
                    + " the glowroot-central.properties files on each node to add the following"
                    + " properties:\n\n" + sb + "\n\n");
            throw new IllegalStateException(
                    "Glowroot central could not start, see error message above for instructions");
        }
        try (FileWriter out = new FileWriter(propFile, true)) {
            out.write(sb.toString());
        }
        return true;
    }

    private static @Nullable Integer getSchemaVersion(Session session, KeyspaceMetadata keyspace)
            throws Exception {
        ResultSet results =
                session.execute("select schema_version from schema_version where one = 1");
        Row row = results.one();
        if (row != null) {
            return row.getInt(0);
        }
        TableMetadata agentTable = keyspace.getTable("agent");
        if (agentTable != null && agentTable.getColumn("system_info") != null) {
            // special case, this is glowroot version 0.9.1, the only version supporting upgrades
            // prior to schema_version table
            return 1;
        }
        // new installation
        return null;
    }
}
