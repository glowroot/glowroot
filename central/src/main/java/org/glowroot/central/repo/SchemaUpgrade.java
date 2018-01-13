/*
 * Copyright 2016-2018 the original author or authors.
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
import java.util.Date;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
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
import com.datastax.driver.core.utils.UUIDs;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Stopwatch;
import com.google.common.collect.ArrayListMultimap;
import com.google.common.collect.HashMultimap;
import com.google.common.collect.ListMultimap;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.Multimap;
import com.google.common.collect.Multimaps;
import com.google.common.collect.Ordering;
import com.google.common.collect.Sets;
import com.google.common.primitives.Ints;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.protobuf.InvalidProtocolBufferException;
import org.immutables.value.Value;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.glowroot.central.util.MoreFutures;
import org.glowroot.central.util.Session;
import org.glowroot.common.config.CentralStorageConfig;
import org.glowroot.common.config.ConfigDefaults;
import org.glowroot.common.config.ImmutableCentralStorageConfig;
import org.glowroot.common.config.ImmutableCentralWebConfig;
import org.glowroot.common.config.PermissionParser;
import org.glowroot.common.config.StorageConfig;
import org.glowroot.common.repo.Utils;
import org.glowroot.common.repo.util.RollupLevelService;
import org.glowroot.common.util.Clock;
import org.glowroot.common.util.ObjectMappers;
import org.glowroot.common.util.PropertiesFiles;
import org.glowroot.common.util.Styles;
import org.glowroot.wire.api.model.AgentConfigOuterClass.AgentConfig;
import org.glowroot.wire.api.model.AgentConfigOuterClass.AgentConfig.AdvancedConfig;
import org.glowroot.wire.api.model.AgentConfigOuterClass.AgentConfig.AlertConfig;
import org.glowroot.wire.api.model.AgentConfigOuterClass.AgentConfig.AlertConfig.AlertCondition.HeartbeatCondition;
import org.glowroot.wire.api.model.AgentConfigOuterClass.AgentConfig.AlertConfig.AlertCondition.MetricCondition;
import org.glowroot.wire.api.model.AgentConfigOuterClass.AgentConfig.AlertConfig.AlertCondition.SyntheticMonitorCondition;
import org.glowroot.wire.api.model.AgentConfigOuterClass.AgentConfig.GeneralConfig;
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

    private static final int CURR_SCHEMA_VERSION = 58;

    private static final String WITH_LCS =
            "with compaction = { 'class' : 'LeveledCompactionStrategy' }";

    private final Session session;
    private final KeyspaceMetadata keyspaceMetadata;
    private final Clock clock;
    private final boolean servlet;

    private final PreparedStatement insertIntoSchemVersionPS;
    private final @Nullable Integer initialSchemaVersion;

    private boolean reloadCentralConfiguration;

    public SchemaUpgrade(Session session, KeyspaceMetadata keyspaceMetadata, Clock clock,
            boolean servlet) throws Exception {
        this.session = session;
        this.keyspaceMetadata = keyspaceMetadata;
        this.clock = clock;
        this.servlet = servlet;

        session.execute("create table if not exists schema_version (one int,"
                + " schema_version int, primary key (one)) " + WITH_LCS);
        insertIntoSchemVersionPS =
                session.prepare("insert into schema_version (one, schema_version) values (1, ?)");
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
            redoOnTriggeredAlertTable();
            updateSchemaVersion(19);
        }
        // 0.9.15 to 0.9.16
        if (initialSchemaVersion < 20) {
            redoOnTriggeredAlertTable();
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
            redoOnTriggeredAlertTable();
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
        // 0.9.21 to 0.9.22
        if (initialSchemaVersion == 28) {
            // only applies when upgrading from immediately prior schema version
            // (to fix bad upgrade in 28 that inserted 'smtp' config row into 'web' config row)
            updateSmtpConfig();
            sortOfFixWebConfig();
            updateSchemaVersion(29);
        } else if (initialSchemaVersion < 29) {
            updateSchemaVersion(29);
        }
        // 0.9.22 to 0.9.23
        if (initialSchemaVersion < 30) {
            addDefaultGaugeNameToUiConfigs();
            updateSchemaVersion(30);
        }
        // 0.9.24 to 0.9.25
        if (initialSchemaVersion < 31) {
            // this is needed due to change from triggered_alert to open_incident/resolved_incident
            redoOnTriggeredAlertTable();
            updateSchemaVersion(31);
        }
        if (initialSchemaVersion < 32) {
            redoOnHeartbeatTable();
            updateSchemaVersion(32);
        }
        // 0.9.26 to 0.9.27
        if (initialSchemaVersion < 33) {
            addSyntheticResultErrorIntervalsColumn();
            updateSchemaVersion(33);
        }
        // 0.9.28 to 0.10.0
        if (initialSchemaVersion < 34) {
            populateGaugeNameTable();
            updateSchemaVersion(34);
        }
        if (initialSchemaVersion == 34) {
            // only applies when upgrading from immediately prior schema version
            // (to fix bad upgrade in 34 that populated the gauge_name table based on
            // gauge_value_rollup_3 instead of gauge_value_rollup_4)
            populateGaugeNameTable();
            updateSchemaVersion(35);
        } else if (initialSchemaVersion < 35) {
            updateSchemaVersion(35);
        }
        if (initialSchemaVersion < 36) {
            populateAgentConfigGeneral();
            updateSchemaVersion(36);
        }
        if (initialSchemaVersion < 37) {
            populateV09AgentCheckTable();
            updateSchemaVersion(37);
        }
        if (initialSchemaVersion < 38) {
            populateAgentHistoryTable();
            updateSchemaVersion(38);
        }
        if (initialSchemaVersion < 39) {
            rewriteAgentConfigTablePart1();
            updateSchemaVersion(39);
        }
        if (initialSchemaVersion < 40) {
            rewriteAgentConfigTablePart2();
            updateSchemaVersion(40);
        }
        if (initialSchemaVersion < 41) {
            rewriteEnvironmentTablePart1();
            updateSchemaVersion(41);
        }
        if (initialSchemaVersion < 42) {
            rewriteEnvironmentTablePart2();
            updateSchemaVersion(42);
        }
        if (initialSchemaVersion < 43) {
            rewriteOpenIncidentTablePart1();
            updateSchemaVersion(43);
        }
        if (initialSchemaVersion < 44) {
            rewriteOpenIncidentTablePart2();
            updateSchemaVersion(44);
        }
        if (initialSchemaVersion < 45) {
            rewriteResolvedIncidentTablePart1();
            updateSchemaVersion(45);
        }
        if (initialSchemaVersion < 46) {
            rewriteResolvedIncidentTablePart2();
            updateSchemaVersion(46);
        }
        if (initialSchemaVersion < 47) {
            rewriteRoleTablePart1();
            updateSchemaVersion(47);
        }
        if (initialSchemaVersion < 48) {
            rewriteRoleTablePart2();
            updateSchemaVersion(48);
        }
        if (initialSchemaVersion < 49) {
            rewriteHeartbeatTablePart1();
            updateSchemaVersion(49);
        }
        if (initialSchemaVersion < 50) {
            rewriteHeartbeatTablePart2();
            updateSchemaVersion(50);
        }
        if (initialSchemaVersion < 51) {
            rewriteTransactionTypeTablePart1();
            updateSchemaVersion(51);
        }
        if (initialSchemaVersion < 52) {
            rewriteTransactionTypeTablePart2();
            updateSchemaVersion(52);
        }
        if (initialSchemaVersion < 53) {
            rewriteTraceAttributeNameTablePart1();
            updateSchemaVersion(53);
        }
        if (initialSchemaVersion < 54) {
            rewriteTraceAttributeNameTablePart2();
            updateSchemaVersion(54);
        }
        if (initialSchemaVersion < 55) {
            rewriteGaugeNameTablePart1();
            updateSchemaVersion(55);
        }
        if (initialSchemaVersion < 56) {
            rewriteGaugeNameTablePart2();
            updateSchemaVersion(56);
        }
        if (initialSchemaVersion < 57) {
            populateV09AgentRollupTable();
            updateSchemaVersion(57);
        }
        if (initialSchemaVersion < 58) {
            finishV09AgentIdUpdate();
            updateSchemaVersion(58);
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
                    int windowSizeHours = Session.getCompactionWindowSizeHours(expirationHours);
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
                int windowSizeHours = Session.getCompactionWindowSizeHours(expirationHours);
                session.execute("alter table " + tableName + " with compaction = { 'class'"
                        + " : 'TimeWindowCompactionStrategy', 'compaction_window_unit' : 'HOURS',"
                        + " 'compaction_window_size' : '" + windowSizeHours + "' }");
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
            int windowSizeHours = Session.getCompactionWindowSizeHours(expirationHours);
            if (twcsUpdatedCount++ == 0) {
                startupLogger.info("updating TimeWindowCompactionStrategy compaction windows ...");
            }
            session.execute("alter table " + tableName + " with compaction = { 'class'"
                    + " : 'TimeWindowCompactionStrategy', 'compaction_window_unit' : 'HOURS',"
                    + " 'compaction_window_size' : '" + windowSizeHours + "' }");
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
        BoundStatement boundStatement = insertIntoSchemVersionPS.bind();
        boundStatement.setInt(0, schemaVersion);
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
        dropTableIfExists("server_config");
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
        dropTableIfExists("agent_rollup");
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
                parentAgentRollupIds.addAll(getAgentRollupIds(parentAgentRollupId));
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
        dropTableIfExists("agent_one");
    }

    private static List<String> getAgentRollupIds(String agentRollupId) {
        List<String> agentRollupIds = Lists.newArrayList();
        int lastFoundIndex = -1;
        int nextFoundIndex;
        while ((nextFoundIndex = agentRollupId.indexOf('/', lastFoundIndex)) != -1) {
            agentRollupIds.add(agentRollupId.substring(0, nextFoundIndex));
            lastFoundIndex = nextFoundIndex + 1;
        }
        agentRollupIds.add(agentRollupId);
        return agentRollupIds;
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
        dropTableIfExists("agent");
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
                        .setDefaultTransactionType(ConfigDefaults.UI_DEFAULT_TRANSACTION_TYPE)
                        .addAllDefaultPercentile(ConfigDefaults.UI_DEFAULT_PERCENTILES))
                .setAdvancedConfig(AdvancedConfig.newBuilder()
                        .setMaxAggregateQueriesPerType(OptionalInt32.newBuilder()
                                .setValue(ConfigDefaults.ADVANCED_MAX_AGGREGATE_QUERIES_PER_TYPE))
                        .setMaxAggregateServiceCallsPerType(OptionalInt32.newBuilder().setValue(
                                ConfigDefaults.ADVANCED_MAX_AGGREGATE_SERVICE_CALLS_PER_TYPE)))
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
                    AgentConfig agentConfig = AgentConfig.parseFrom(checkNotNull(row.getBytes(0)));
                    AdvancedConfig advancedConfig = agentConfig.getAdvancedConfig();
                    AgentConfig updatedAgentConfig = AgentConfig.newBuilder()
                            .setUiConfig(agentConfig.getUiConfig())
                            .setAdvancedConfig(AdvancedConfig.newBuilder()
                                    .setMaxAggregateQueriesPerType(
                                            advancedConfig.getMaxAggregateQueriesPerType())
                                    .setMaxAggregateServiceCallsPerType(
                                            advancedConfig.getMaxAggregateServiceCallsPerType()))
                            .build();
                    boundStatement.setBytes(1, ByteBuffer.wrap(updatedAgentConfig.toByteArray()));
                } catch (InvalidProtocolBufferException e) {
                    logger.error(e.getMessage(), e);
                    boundStatement.setBytes(1, ByteBuffer.wrap(defaultAgentConfig.toByteArray()));
                }
            }
            session.execute(boundStatement);
        }
    }

    private void redoOnTriggeredAlertTable() throws Exception {
        dropTableIfExists("triggered_alert");
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
        dropTableIfExists("config");
    }

    private void upgradeAlertConfigs() throws Exception {
        PreparedStatement insertPS = session.prepare("insert into agent_config (agent_rollup_id,"
                + " config, config_update, config_update_token) values (?, ?, ?, ?)");
        ResultSet results = session.execute("select agent_rollup_id, config from agent_config");
        for (Row row : results) {
            String agentRollupId = row.getString(0);
            AgentConfig oldAgentConfig;
            try {
                oldAgentConfig = AgentConfig.parseFrom(checkNotNull(row.getBytes(1)));
            } catch (InvalidProtocolBufferException e) {
                logger.error(e.getMessage(), e);
                continue;
            }
            List<OldAlertConfig> oldAlertConfigs = oldAgentConfig.getOldAlertConfigList();
            if (oldAlertConfigs.isEmpty()) {
                continue;
            }
            AgentConfig agentConfig = upgradeOldAgentConfig(oldAgentConfig);
            BoundStatement boundStatement = insertPS.bind();
            int i = 0;
            boundStatement.setString(i++, agentRollupId);
            boundStatement.setBytes(i++, ByteBuffer.wrap(agentConfig.toByteArray()));
            boundStatement.setBool(i++, true);
            boundStatement.setUUID(i++, UUIDs.random());
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
                session.prepare("insert into central_config (key, value) values ('smtp', ?)");
        BoundStatement boundStatement = preparedStatement.bind();
        boundStatement.setString(0, updatedWebConfigText);
        session.execute(boundStatement);
    }

    private void addDefaultGaugeNameToUiConfigs() throws Exception {
        PreparedStatement insertPS = session.prepare("insert into agent_config (agent_rollup_id,"
                + " config, config_update, config_update_token) values (?, ?, ?, ?)");
        ResultSet results = session.execute("select agent_rollup_id, config from agent_config");
        for (Row row : results) {
            String agentRollupId = row.getString(0);
            AgentConfig oldAgentConfig;
            try {
                oldAgentConfig = AgentConfig.parseFrom(checkNotNull(row.getBytes(1)));
            } catch (InvalidProtocolBufferException e) {
                logger.error(e.getMessage(), e);
                continue;
            }
            AgentConfig agentConfig = oldAgentConfig.toBuilder()
                    .setUiConfig(oldAgentConfig.getUiConfig().toBuilder()
                            .addAllDefaultGaugeName(ConfigDefaults.UI_DEFAULT_GAUGE_NAMES))
                    .build();
            BoundStatement boundStatement = insertPS.bind();
            int i = 0;
            boundStatement.setString(i++, agentRollupId);
            boundStatement.setBytes(i++, ByteBuffer.wrap(agentConfig.toByteArray()));
            boundStatement.setBool(i++, true);
            boundStatement.setUUID(i++, UUIDs.random());
            session.execute(boundStatement);
        }
    }

    // fix bad upgrade that inserted 'smtp' config row into 'web' config row
    private void sortOfFixWebConfig() throws Exception {
        ResultSet results =
                session.execute("select value from central_config where key = 'web'");
        Row row = results.one();
        if (row == null) {
            return;
        }
        String webConfigText = row.getString(0);
        if (webConfigText == null) {
            return;
        }
        JsonNode jsonNode = mapper.readTree(webConfigText);
        if (jsonNode == null || !jsonNode.isObject()) {
            return;
        }
        ObjectNode webConfigNode = (ObjectNode) jsonNode;
        if (webConfigNode.has("host")) {
            // remove 'web' config row which has 'smtp' config (old 'web' config row is lost)
            session.execute("delete from central_config where key = 'web'");
        }
    }

    private void redoOnHeartbeatTable() throws Exception {
        dropTableIfExists("heartbeat");
    }

    private void addSyntheticResultErrorIntervalsColumn() throws Exception {
        addColumnIfNotExists("synthetic_result_rollup_0", "error_intervals", "blob");
        addColumnIfNotExists("synthetic_result_rollup_1", "error_intervals", "blob");
        addColumnIfNotExists("synthetic_result_rollup_2", "error_intervals", "blob");
        addColumnIfNotExists("synthetic_result_rollup_3", "error_intervals", "blob");
    }

    private void populateGaugeNameTable() throws Exception {
        logger.info("populating new gauge name history table - this could take several minutes on"
                + " large data sets ...");

        CentralStorageConfig storageConfig = getCentralStorageConfig(session);
        int maxRollupHours = storageConfig.getMaxRollupHours();
        dropTableIfExists("gauge_name");
        session.createTableWithTWCS("create table gauge_name (agent_rollup_id varchar, capture_time"
                + " timestamp, gauge_name varchar, primary key (agent_rollup_id, capture_time,"
                + " gauge_name))", maxRollupHours);
        PreparedStatement insertPS = session.prepare("insert into gauge_name (agent_rollup_id,"
                + " capture_time, gauge_name) values (?, ?, ?) using ttl ?");
        Multimap<Long, AgentRollupIdGaugeNamePair> rowsPerCaptureTime = HashMultimap.create();
        ResultSet results = session
                .execute("select agent_rollup, gauge_name, capture_time from gauge_value_rollup_4");
        for (Row row : results) {
            int i = 0;
            String agentRollupId = checkNotNull(row.getString(i++));
            String gaugeName = checkNotNull(row.getString(i++));
            long captureTime = checkNotNull(row.getTimestamp(i++)).getTime();
            long millisPerDay = DAYS.toMillis(1);
            long rollupCaptureTime = Utils.getRollupCaptureTime(captureTime, millisPerDay);
            rowsPerCaptureTime.put(rollupCaptureTime,
                    ImmutableAgentRollupIdGaugeNamePair.of(agentRollupId, gaugeName));
        }
        // read from 1-min gauge values to get not-yet-rolled-up data
        // (not using 5-second gauge values since those don't exist for agent rollups)
        results = session
                .execute("select agent_rollup, gauge_name, capture_time from gauge_value_rollup_1");
        for (Row row : results) {
            int i = 0;
            String agentRollupId = checkNotNull(row.getString(i++));
            String gaugeName = checkNotNull(row.getString(i++));
            long captureTime = checkNotNull(row.getTimestamp(i++)).getTime();
            long millisPerDay = DAYS.toMillis(1);
            long rollupCaptureTime = Utils.getRollupCaptureTime(captureTime, millisPerDay);
            rowsPerCaptureTime.put(rollupCaptureTime,
                    ImmutableAgentRollupIdGaugeNamePair.of(agentRollupId, gaugeName));
        }
        int maxRollupTTL = storageConfig.getMaxRollupTTL();
        List<ListenableFuture<ResultSet>> futures = Lists.newArrayList();
        List<Long> sortedCaptureTimes =
                Ordering.natural().sortedCopy(rowsPerCaptureTime.keySet());
        for (long captureTime : sortedCaptureTimes) {
            int adjustedTTL = Common.getAdjustedTTL(maxRollupTTL, captureTime, clock);
            for (AgentRollupIdGaugeNamePair row : rowsPerCaptureTime.get(captureTime)) {
                BoundStatement boundStatement = insertPS.bind();
                int i = 0;
                boundStatement.setString(i++, row.agentRollupId());
                boundStatement.setTimestamp(i++, new Date(captureTime));
                boundStatement.setString(i++, row.gaugeName());
                boundStatement.setInt(i++, adjustedTTL);
                futures.add(session.executeAsync(boundStatement));
            }
        }
        MoreFutures.waitForAll(futures);
        logger.info("populating new gauge name history table - complete");
    }

    private void populateAgentConfigGeneral() throws Exception {
        if (!columnExists("agent_rollup", "display")) {
            return;
        }
        ResultSet results =
                session.execute("select agent_rollup_id, display from agent_rollup where one = 1");
        PreparedStatement readConfigPS =
                session.prepare("select config from agent_config where agent_rollup_id = ?");
        PreparedStatement insertConfigPS =
                session.prepare("insert into agent_config (agent_rollup_id, config) values (?, ?)");
        for (Row row : results) {
            String agentRollupId = row.getString(0);
            String display = row.getString(1);
            if (display == null) {
                continue;
            }
            BoundStatement boundStatement = readConfigPS.bind();
            boundStatement.setString(0, agentRollupId);
            Row configRow = session.execute(boundStatement).one();
            if (configRow == null) {
                logger.warn("could not find config for agent rollup id: {}", agentRollupId);
                continue;
            }
            AgentConfig agentConfig = AgentConfig.parseFrom(checkNotNull(configRow.getBytes(0)));
            AgentConfig updatedAgentConfig = agentConfig.toBuilder()
                    .setGeneralConfig(GeneralConfig.newBuilder()
                            .setDisplay(display))
                    .build();
            boundStatement = insertConfigPS.bind();
            boundStatement.setString(0, agentRollupId);
            boundStatement.setBytes(1, ByteBuffer.wrap(updatedAgentConfig.toByteArray()));
            session.execute(boundStatement);
        }
        dropColumnIfExists("agent_rollup", "display");
    }

    private void populateV09AgentCheckTable() throws Exception {
        Map<String, V09AgentRollup> v09AgentRollups = getV09AgentRollupsFromAgentRollupTable();
        PreparedStatement insertV09AgentCheckPS = null;
        for (V09AgentRollup v09AgentRollup : v09AgentRollups.values()) {
            if (v09AgentRollup.agent() && v09AgentRollup.hasRollup()) {
                // only create v09_agent_check and v09_last_capture_time tables if needed
                if (insertV09AgentCheckPS == null) {
                    dropTableIfExists("v09_last_capture_time");
                    session.execute("create table v09_last_capture_time (one int,"
                            + " v09_last_capture_time timestamp, v09_fqt_last_expiration_time"
                            + " timestamp, v09_trace_last_expiration_time timestamp,"
                            + " v09_aggregate_last_expiration_time timestamp, primary key (one))");
                    BoundStatement boundStatement = session.prepare("insert into"
                            + " v09_last_capture_time (one, v09_last_capture_time,"
                            + " v09_fqt_last_expiration_time, v09_trace_last_expiration_time,"
                            + " v09_aggregate_last_expiration_time) values (1, ?, ?, ?, ?)")
                            .bind();
                    long nextDailyRollup = RollupLevelService.getCeilRollupTime(
                            clock.currentTimeMillis(), DAYS.toMillis(1));
                    CentralStorageConfig storageConfig = getCentralStorageConfig(session);
                    long v09FqtLastExpirationTime = addExpirationHours(nextDailyRollup,
                            storageConfig.fullQueryTextExpirationHours());
                    long v09TraceLastExpirationTime = addExpirationHours(nextDailyRollup,
                            storageConfig.traceExpirationHours());
                    long v09AggregateLastExpirationTime = addExpirationHours(nextDailyRollup,
                            storageConfig.getMaxRollupHours());
                    int i = 0;
                    boundStatement.setTimestamp(i++, new Date(nextDailyRollup));
                    boundStatement.setTimestamp(i++, new Date(v09FqtLastExpirationTime));
                    boundStatement.setTimestamp(i++, new Date(v09TraceLastExpirationTime));
                    boundStatement.setTimestamp(i++, new Date(v09AggregateLastExpirationTime));
                    session.execute(boundStatement);

                    dropTableIfExists("v09_agent_check");
                    session.execute("create table v09_agent_check (one int, agent_id varchar,"
                            + " primary key (one, agent_id))");
                    insertV09AgentCheckPS = session.prepare(
                            "insert into v09_agent_check (one, agent_id) values (1, ?)");
                }
                BoundStatement boundStatement = insertV09AgentCheckPS.bind();
                boundStatement.setString(0, v09AgentRollup.agentRollupId());
                session.execute(boundStatement);
            }
        }
    }

    private void populateAgentHistoryTable() throws Exception {
        logger.info("populating new agent history table - this could take a several minutes on"
                + " large data sets ...");

        CentralStorageConfig storageConfig = getCentralStorageConfig(session);
        dropTableIfExists("agent");
        session.createTableWithTWCS("create table agent (one int, capture_time timestamp, agent_id"
                + " varchar, primary key (one, capture_time, agent_id))",
                storageConfig.getMaxRollupHours());
        PreparedStatement insertPS = session.prepare("insert into agent (one, capture_time,"
                + " agent_id) values (1, ?, ?) using ttl ?");
        Map<String, V09AgentRollup> v09AgentRollups = getV09AgentRollupsFromAgentRollupTable();
        Multimap<Long, String> agentIdsPerCaptureTime = HashMultimap.create();
        ResultSet results = session
                .execute("select agent_rollup, capture_time from aggregate_tt_throughput_rollup_3");
        for (Row row : results) {
            String v09AgentId = checkNotNull(row.getString(0));
            V09AgentRollup v09AgentRollup = v09AgentRollups.get(v09AgentId);
            if (v09AgentRollup == null || !v09AgentRollup.agent()) {
                // v09AgentId is not an agent, or it was manually deleted (via the UI) from the
                // agent_rollup table in which case its parent is no longer known and best to ignore
                continue;
            }
            String agentId = v09AgentRollup.agentRollupId();
            long captureTime = checkNotNull(row.getTimestamp(1)).getTime();
            long millisPerDay = DAYS.toMillis(1);
            long rollupCaptureTime = Utils.getRollupCaptureTime(captureTime, millisPerDay);
            agentIdsPerCaptureTime.put(rollupCaptureTime, agentId);
        }
        // read from 1-min aggregates to get not-yet-rolled-up data
        results = session
                .execute("select agent_rollup, capture_time from aggregate_tt_throughput_rollup_0");
        for (Row row : results) {
            String v09AgentId = checkNotNull(row.getString(0));
            V09AgentRollup v09AgentRollup = v09AgentRollups.get(v09AgentId);
            if (v09AgentRollup == null || !v09AgentRollup.agent()) {
                // v09AgentId is not an agent, or it was manually deleted (via the UI) from the
                // agent_rollup table in which case its parent is no longer known and best to ignore
                continue;
            }
            String agentId = v09AgentRollup.agentRollupId();
            long captureTime = checkNotNull(row.getTimestamp(1)).getTime();
            long millisPerDay = DAYS.toMillis(1);
            long rollupCaptureTime = Utils.getRollupCaptureTime(captureTime, millisPerDay);
            agentIdsPerCaptureTime.put(rollupCaptureTime, agentId);
        }
        int maxRollupTTL = storageConfig.getMaxRollupTTL();
        List<Long> sortedCaptureTimes =
                Ordering.natural().sortedCopy(agentIdsPerCaptureTime.keySet());
        List<ListenableFuture<ResultSet>> futures = Lists.newArrayList();
        for (long captureTime : sortedCaptureTimes) {
            int adjustedTTL = Common.getAdjustedTTL(maxRollupTTL, captureTime, clock);
            for (String agentId : agentIdsPerCaptureTime.get(captureTime)) {
                BoundStatement boundStatement = insertPS.bind();
                int i = 0;
                boundStatement.setTimestamp(i++, new Date(captureTime));
                boundStatement.setString(i++, agentId);
                boundStatement.setInt(i++, adjustedTTL);
                futures.add(session.executeAsync(boundStatement));
            }
        }
        MoreFutures.waitForAll(futures);
        logger.info("populating new agent history table - complete");
    }

    private void rewriteAgentConfigTablePart1() throws Exception {
        dropTableIfExists("agent_config_temp");
        session.execute("create table agent_config_temp (agent_rollup_id varchar, config blob,"
                + " config_update boolean, config_update_token uuid, primary key"
                + " (agent_rollup_id))");
        PreparedStatement insertTempPS = session.prepare("insert into agent_config_temp"
                + " (agent_rollup_id, config, config_update, config_update_token) values"
                + " (?, ?, ?, ?)");
        ResultSet results = session.execute("select agent_rollup_id, config, config_update,"
                + " config_update_token from agent_config");
        for (Row row : results) {
            BoundStatement boundStatement = insertTempPS.bind();
            boundStatement.setString(0, row.getString(0));
            boundStatement.setBytes(1, row.getBytes(1));
            boundStatement.setBool(2, row.getBool(2));
            boundStatement.setUUID(3, row.getUUID(3));
            session.execute(boundStatement);
        }
    }

    private void rewriteAgentConfigTablePart2() throws Exception {
        if (!tableExists("agent_config_temp")) {
            // previously failed mid-upgrade prior to updating schema version
            return;
        }
        dropTableIfExists("agent_config");
        session.execute("create table agent_config (agent_rollup_id varchar, config blob,"
                + " config_update boolean, config_update_token uuid, primary key"
                + " (agent_rollup_id)) " + WITH_LCS);
        PreparedStatement insertPS = session.prepare("insert into agent_config"
                + " (agent_rollup_id, config, config_update, config_update_token) values"
                + " (?, ?, ?, ?)");
        Map<String, V09AgentRollup> v09AgentRollups = getV09AgentRollupsFromAgentRollupTable();
        ResultSet results = session.execute("select agent_rollup_id, config, config_update,"
                + " config_update_token from agent_config_temp");
        for (Row row : results) {
            String v09AgentRollupId = row.getString(0);
            V09AgentRollup v09AgentRollup = v09AgentRollups.get(v09AgentRollupId);
            if (v09AgentRollup == null) {
                // v09AgentRollupId was manually deleted (via the UI) from the agent_rollup
                // table in which case its parent is no longer known and best to ignore
                continue;
            }
            BoundStatement boundStatement = insertPS.bind();
            boundStatement.setString(0, v09AgentRollup.agentRollupId());
            boundStatement.setBytes(1, row.getBytes(1));
            boundStatement.setBool(2, row.getBool(2));
            boundStatement.setUUID(3, row.getUUID(3));
            session.execute(boundStatement);
        }
        dropTableIfExists("agent_config_temp");
    }

    private void rewriteEnvironmentTablePart1() throws Exception {
        dropTableIfExists("environment_temp");
        session.execute("create table environment_temp (agent_id varchar, environment blob,"
                + " primary key (agent_id))");
        PreparedStatement insertTempPS = session
                .prepare("insert into environment_temp (agent_id, environment) values (?, ?)");
        ResultSet results = session.execute("select agent_id, environment from environment");
        for (Row row : results) {
            BoundStatement boundStatement = insertTempPS.bind();
            boundStatement.setString(0, row.getString(0));
            boundStatement.setBytes(1, row.getBytes(1));
            session.execute(boundStatement);
        }
    }

    private void rewriteEnvironmentTablePart2() throws Exception {
        if (!tableExists("environment_temp")) {
            // previously failed mid-upgrade prior to updating schema version
            return;
        }
        dropTableIfExists("environment");
        session.execute("create table environment (agent_id varchar, environment blob,"
                + " primary key (agent_id)) " + WITH_LCS);
        PreparedStatement insertPS = session
                .prepare("insert into environment (agent_id, environment) values (?, ?)");
        Map<String, V09AgentRollup> v09AgentRollups = getV09AgentRollupsFromAgentRollupTable();
        ResultSet results = session.execute("select agent_id, environment from environment_temp");
        for (Row row : results) {
            String v09AgentRollupId = row.getString(0);
            V09AgentRollup v09AgentRollup = v09AgentRollups.get(v09AgentRollupId);
            if (v09AgentRollup == null) {
                // v09AgentRollupId was manually deleted (via the UI) from the agent_rollup
                // table in which case its parent is no longer known and best to ignore
                continue;
            }
            BoundStatement boundStatement = insertPS.bind();
            boundStatement.setString(0, v09AgentRollup.agentRollupId());
            boundStatement.setBytes(1, row.getBytes(1));
            session.execute(boundStatement);
        }
        dropTableIfExists("environment_temp");
    }

    private void rewriteOpenIncidentTablePart1() throws Exception {
        dropTableIfExists("open_incident_temp");
        session.execute("create table open_incident_temp (one int, agent_rollup_id varchar,"
                + " condition blob, severity varchar, notification blob, open_time timestamp,"
                + " primary key (one, agent_rollup_id, condition, severity))");
        PreparedStatement insertTempPS = session.prepare("insert into open_incident_temp (one,"
                + " agent_rollup_id, condition, severity, notification, open_time) values"
                + " (1, ?, ?, ?, ?, ?)");
        ResultSet results = session.execute("select agent_rollup_id, condition, severity,"
                + " notification, open_time from open_incident where one = 1");
        for (Row row : results) {
            BoundStatement boundStatement = insertTempPS.bind();
            boundStatement.setString(0, row.getString(0));
            boundStatement.setBytes(1, row.getBytes(1));
            boundStatement.setString(2, row.getString(2));
            boundStatement.setBytes(3, row.getBytes(3));
            boundStatement.setTimestamp(4, row.getTimestamp(4));
            session.execute(boundStatement);
        }
    }

    private void rewriteOpenIncidentTablePart2() throws Exception {
        if (!tableExists("open_incident_temp")) {
            // previously failed mid-upgrade prior to updating schema version
            return;
        }
        dropTableIfExists("open_incident");
        session.execute("create table open_incident (one int, agent_rollup_id varchar,"
                + " condition blob, severity varchar, notification blob, open_time timestamp,"
                + " primary key (one, agent_rollup_id, condition, severity)) " + WITH_LCS);
        PreparedStatement insertPS = session.prepare("insert into open_incident (one,"
                + " agent_rollup_id, condition, severity, notification, open_time) values"
                + " (1, ?, ?, ?, ?, ?)");
        Map<String, V09AgentRollup> v09AgentRollups = getV09AgentRollupsFromAgentRollupTable();
        ResultSet results = session.execute("select agent_rollup_id, condition, severity,"
                + " notification, open_time from open_incident_temp where one = 1");
        for (Row row : results) {
            String v09AgentRollupId = row.getString(0);
            V09AgentRollup v09AgentRollup = v09AgentRollups.get(v09AgentRollupId);
            if (v09AgentRollup == null) {
                // v09AgentRollupId was manually deleted (via the UI) from the agent_rollup
                // table in which case its parent is no longer known and best to ignore
                continue;
            }
            BoundStatement boundStatement = insertPS.bind();
            boundStatement.setString(0, v09AgentRollup.agentRollupId());
            boundStatement.setBytes(1, row.getBytes(1));
            boundStatement.setString(2, row.getString(2));
            boundStatement.setBytes(3, row.getBytes(3));
            boundStatement.setTimestamp(4, row.getTimestamp(4));
            session.execute(boundStatement);
        }
        dropTableIfExists("open_incident_temp");
    }

    private void rewriteResolvedIncidentTablePart1() throws Exception {
        dropTableIfExists("resolved_incident_temp");
        session.execute("create table resolved_incident_temp (one int, resolve_time"
                + " timestamp, agent_rollup_id varchar, condition blob, severity varchar,"
                + " notification blob, open_time timestamp, primary key (one, resolve_time,"
                + " agent_rollup_id, condition)) with clustering order by (resolve_time desc)");
        PreparedStatement insertTempPS = session.prepare("insert into resolved_incident_temp"
                + " (one, resolve_time, agent_rollup_id, condition, severity, notification,"
                + " open_time) values (1, ?, ?, ?, ?, ?, ?)");
        ResultSet results = session.execute("select resolve_time, agent_rollup_id, condition,"
                + " severity, notification, open_time from resolved_incident where one = 1");
        for (Row row : results) {
            BoundStatement boundStatement = insertTempPS.bind();
            boundStatement.setTimestamp(0, row.getTimestamp(0));
            boundStatement.setString(1, row.getString(1));
            boundStatement.setBytes(2, row.getBytes(2));
            boundStatement.setString(3, row.getString(3));
            boundStatement.setBytes(4, row.getBytes(4));
            boundStatement.setTimestamp(5, row.getTimestamp(5));
            session.execute(boundStatement);
        }
    }

    private void rewriteResolvedIncidentTablePart2() throws Exception {
        if (!tableExists("resolved_incident_temp")) {
            // previously failed mid-upgrade prior to updating schema version
            return;
        }
        dropTableIfExists("resolved_incident");
        session.createTableWithTWCS("create table resolved_incident (one int, resolve_time"
                + " timestamp, agent_rollup_id varchar, condition blob, severity varchar,"
                + " notification blob, open_time timestamp, primary key (one, resolve_time,"
                + " agent_rollup_id, condition)) with clustering order by (resolve_time desc)",
                StorageConfig.RESOLVED_INCIDENT_EXPIRATION_HOURS, true);
        PreparedStatement insertPS = session.prepare("insert into resolved_incident (one,"
                + " resolve_time, agent_rollup_id, condition, severity, notification,"
                + " open_time) values (1, ?, ?, ?, ?, ?, ?) using ttl ?");
        Map<String, V09AgentRollup> v09AgentRollups = getV09AgentRollupsFromAgentRollupTable();
        int ttl = Ints.saturatedCast(
                HOURS.toSeconds(StorageConfig.RESOLVED_INCIDENT_EXPIRATION_HOURS));
        ResultSet results = session.execute("select resolve_time, agent_rollup_id, condition,"
                + " severity, notification, open_time from resolved_incident_temp where one = 1");
        for (Row row : results) {
            String v09AgentRollupId = row.getString(1);
            V09AgentRollup v09AgentRollup = v09AgentRollups.get(v09AgentRollupId);
            if (v09AgentRollup == null) {
                // v09AgentRollupId was manually deleted (via the UI) from the agent_rollup
                // table in which case its parent is no longer known and best to ignore
                continue;
            }
            Date resolveTime = checkNotNull(row.getTimestamp(0));
            int adjustedTTL = Common.getAdjustedTTL(ttl, resolveTime.getTime(), clock);
            BoundStatement boundStatement = insertPS.bind();
            boundStatement.setTimestamp(0, resolveTime);
            boundStatement.setString(1, v09AgentRollup.agentRollupId());
            boundStatement.setBytes(2, row.getBytes(2));
            boundStatement.setString(3, row.getString(3));
            boundStatement.setBytes(4, row.getBytes(4));
            boundStatement.setTimestamp(5, row.getTimestamp(5));
            boundStatement.setInt(6, adjustedTTL);
            session.execute(boundStatement);
        }
        dropTableIfExists("resolved_incident_temp");
    }

    private void rewriteRoleTablePart1() throws Exception {
        dropTableIfExists("role_temp");
        session.execute("create table role_temp (name varchar, permissions set<varchar>,"
                + " primary key (name))");
        PreparedStatement insertTempPS =
                session.prepare("insert into role_temp (name, permissions) values (?, ?)");
        ResultSet results = session.execute("select name, permissions from role");
        for (Row row : results) {
            BoundStatement boundStatement = insertTempPS.bind();
            boundStatement.setString(0, row.getString(0));
            boundStatement.setSet(1, row.getSet(1, String.class));
            session.execute(boundStatement);
        }
    }

    private void rewriteRoleTablePart2() throws Exception {
        if (!tableExists("role_temp")) {
            // previously failed mid-upgrade prior to updating schema version
            return;
        }
        dropTableIfExists("role");
        session.execute("create table role (name varchar, permissions set<varchar>,"
                + " primary key (name)) " + WITH_LCS);
        PreparedStatement insertPS =
                session.prepare("insert into role (name, permissions) values (?, ?)");
        Map<String, V09AgentRollup> v09AgentRollups = getV09AgentRollupsFromAgentRollupTable();
        ResultSet results = session.execute("select name, permissions from role_temp");
        for (Row row : results) {
            Set<String> v09Permissions = row.getSet(1, String.class);
            Set<String> permissions = Sets.newLinkedHashSet();
            for (String v09Permission : v09Permissions) {
                if (!v09Permission.startsWith("agent:")) {
                    // non-agent permission, no need for conversion
                    permissions.add(v09Permission);
                    continue;
                }
                if (v09Permission.equals("agent:") || v09Permission.startsWith("agent::")
                        || v09Permission.equals("agent:*")
                        || v09Permission.startsWith("agent:*:")) {
                    // special cases, no need for conversion
                    permissions.add(v09Permission);
                    continue;
                }
                PermissionParser parser = new PermissionParser(v09Permission);
                parser.parse();
                List<String> v09AgentRollupIds = parser.getAgentRollupIds();
                String perm = parser.getPermission();
                if (v09AgentRollupIds.isEmpty()) {
                    // this shouldn't happen since v09Permission doesn't start with "agent::"
                    // (see condition above)
                    logger.warn("found agent permission without any agents: {}", v09Permission);
                    continue;
                }
                List<String> agentRollupIds = Lists.newArrayList();
                for (String v09AgentRollupId : v09AgentRollupIds) {
                    V09AgentRollup v09AgentRollup = v09AgentRollups.get(v09AgentRollupId);
                    if (v09AgentRollup == null) {
                        // v09AgentRollupId was manually deleted (via the UI) from the
                        // agent_rollup table in which case its parent is no longer known
                        // and best to ignore
                        continue;
                    }
                    agentRollupIds.add(v09AgentRollup.agentRollupId());
                }
                if (agentRollupIds.isEmpty()) {
                    // all v09AgentRollupIds were manually deleted (see comment above)
                    continue;
                }
                if (perm.isEmpty()) {
                    permissions.add(
                            "agent:" + PermissionParser.quoteIfNeededAndJoin(v09AgentRollupIds));
                } else {
                    permissions.add("agent:" + PermissionParser.quoteIfNeededAndJoin(agentRollupIds)
                            + ":" + perm.substring("agent:".length()));
                }
            }
            if (permissions.isEmpty()) {
                // all v09AgentRollupIds for all permissions were manually deleted (see comments
                // above)
                continue;
            }
            BoundStatement boundStatement = insertPS.bind();
            boundStatement.setString(0, row.getString(0));
            boundStatement.setSet(1, permissions);
            session.execute(boundStatement);
        }
        dropTableIfExists("role_temp");
    }

    private void rewriteHeartbeatTablePart1() throws Exception {
        logger.info("rewriting heartbeat table (part 1) ...");
        dropTableIfExists("heartbeat_temp");
        session.execute("create table heartbeat_temp (agent_id varchar, central_capture_time"
                + " timestamp, primary key (agent_id, central_capture_time))");
        PreparedStatement insertTempPS = session.prepare("insert into heartbeat_temp (agent_id,"
                + " central_capture_time) values (?, ?)");
        ResultSet results = session.execute("select agent_id, central_capture_time from heartbeat");
        List<ListenableFuture<ResultSet>> futures = Lists.newArrayList();
        for (Row row : results) {
            BoundStatement boundStatement = insertTempPS.bind();
            boundStatement.setString(0, row.getString(0));
            boundStatement.setTimestamp(1, row.getTimestamp(1));
            futures.add(session.executeAsync(boundStatement));
        }
        MoreFutures.waitForAll(futures);
        logger.info("rewriting heartbeat table (part 1) - complete");
    }

    private void rewriteHeartbeatTablePart2() throws Exception {
        if (!tableExists("heartbeat_temp")) {
            // previously failed mid-upgrade prior to updating schema version
            return;
        }
        logger.info("rewriting heartbeat table (part 2) ...");
        Map<String, V09AgentRollup> v09AgentRollups = getV09AgentRollupsFromAgentRollupTable();
        dropTableIfExists("heartbeat");
        session.createTableWithTWCS("create table heartbeat (agent_id varchar, central_capture_time"
                + " timestamp, primary key (agent_id, central_capture_time))",
                HeartbeatDao.EXPIRATION_HOURS);
        PreparedStatement insertPS = session.prepare("insert into heartbeat (agent_id,"
                + " central_capture_time) values (?, ?) using ttl ?");
        int ttl = Ints.saturatedCast(HOURS.toSeconds(HeartbeatDao.EXPIRATION_HOURS));
        ResultSet results =
                session.execute("select agent_id, central_capture_time from heartbeat_temp");
        List<ListenableFuture<ResultSet>> futures = Lists.newArrayList();
        for (Row row : results) {
            String v09AgentRollupId = row.getString(0);
            V09AgentRollup v09AgentRollup = v09AgentRollups.get(v09AgentRollupId);
            if (v09AgentRollup == null) {
                // v09AgentRollupId was manually deleted (via the UI) from the agent_rollup
                // table in which case its parent is no longer known and best to ignore
                continue;
            }
            Date centralCaptureDate = checkNotNull(row.getTimestamp(1));
            int adjustedTTL = Common.getAdjustedTTL(ttl, centralCaptureDate.getTime(), clock);
            BoundStatement boundStatement = insertPS.bind();
            int i = 0;
            boundStatement.setString(i++, v09AgentRollup.agentRollupId());
            boundStatement.setTimestamp(i++, centralCaptureDate);
            boundStatement.setInt(i++, adjustedTTL);
            futures.add(session.executeAsync(boundStatement));
        }
        MoreFutures.waitForAll(futures);
        dropTableIfExists("heartbeat_temp");
        logger.info("rewriting heartbeat table (part 2) - complete");
    }

    private void rewriteTransactionTypeTablePart1() throws Exception {
        dropTableIfExists("transaction_type_temp");
        session.execute("create table transaction_type_temp (one int, agent_rollup varchar,"
                + " transaction_type varchar, primary key (one, agent_rollup, transaction_type))");
        PreparedStatement insertTempPS = session.prepare("insert into transaction_type_temp (one,"
                + " agent_rollup, transaction_type) values (1, ?, ?)");
        ResultSet results = session.execute(
                "select agent_rollup, transaction_type from transaction_type where one = 1");
        for (Row row : results) {
            BoundStatement boundStatement = insertTempPS.bind();
            boundStatement.setString(0, row.getString(0));
            boundStatement.setString(1, row.getString(1));
            session.execute(boundStatement);
        }
    }

    private void rewriteTransactionTypeTablePart2() throws Exception {
        if (!tableExists("transaction_type_temp")) {
            // previously failed mid-upgrade prior to updating schema version
            return;
        }
        dropTableIfExists("transaction_type");
        Map<String, V09AgentRollup> v09AgentRollups = getV09AgentRollupsFromAgentRollupTable();
        session.execute("create table transaction_type (one int, agent_rollup varchar,"
                + " transaction_type varchar, primary key (one, agent_rollup, transaction_type)) "
                + WITH_LCS);
        PreparedStatement insertPS = session.prepare("insert into transaction_type (one,"
                + " agent_rollup, transaction_type) values (1, ?, ?) using ttl ?");
        int ttl = getCentralStorageConfig(session).getMaxRollupTTL();
        ResultSet results = session.execute(
                "select agent_rollup, transaction_type from transaction_type_temp where one = 1");
        for (Row row : results) {
            String v09AgentRollupId = row.getString(0);
            V09AgentRollup v09AgentRollup = v09AgentRollups.get(v09AgentRollupId);
            if (v09AgentRollup == null) {
                // v09AgentRollupId was manually deleted (via the UI) from the agent_rollup
                // table in which case its parent is no longer known and best to ignore
                continue;
            }
            BoundStatement boundStatement = insertPS.bind();
            boundStatement.setString(0, v09AgentRollup.agentRollupId());
            boundStatement.setString(1, row.getString(1));
            boundStatement.setInt(2, ttl);
            session.execute(boundStatement);
        }
        dropTableIfExists("transaction_type_temp");
    }

    private void rewriteTraceAttributeNameTablePart1() throws Exception {
        dropTableIfExists("trace_attribute_name_temp");
        session.execute("create table trace_attribute_name_temp (agent_rollup varchar,"
                + " transaction_type varchar, trace_attribute_name varchar, primary key"
                + " ((agent_rollup, transaction_type), trace_attribute_name))");
        PreparedStatement insertTempPS = session.prepare("insert into trace_attribute_name_temp"
                + " (agent_rollup, transaction_type, trace_attribute_name) values (?, ?, ?)");
        ResultSet results = session.execute("select agent_rollup, transaction_type,"
                + " trace_attribute_name from trace_attribute_name");
        for (Row row : results) {
            BoundStatement boundStatement = insertTempPS.bind();
            boundStatement.setString(0, row.getString(0));
            boundStatement.setString(1, row.getString(1));
            boundStatement.setString(2, row.getString(2));
            session.execute(boundStatement);
        }
    }

    private void rewriteTraceAttributeNameTablePart2() throws Exception {
        if (!tableExists("trace_attribute_name_temp")) {
            // previously failed mid-upgrade prior to updating schema version
            return;
        }
        dropTableIfExists("trace_attribute_name");
        Map<String, V09AgentRollup> v09AgentRollups = getV09AgentRollupsFromAgentRollupTable();
        session.execute("create table trace_attribute_name (agent_rollup varchar, transaction_type"
                + " varchar, trace_attribute_name varchar, primary key ((agent_rollup,"
                + " transaction_type), trace_attribute_name)) " + WITH_LCS);
        PreparedStatement insertPS = session.prepare("insert into trace_attribute_name"
                + " (agent_rollup, transaction_type, trace_attribute_name) values (?, ?, ?) using"
                + " ttl ?");
        int ttl = getCentralStorageConfig(session).getTraceTTL();
        ResultSet results = session.execute("select agent_rollup, transaction_type,"
                + " trace_attribute_name from trace_attribute_name_temp");
        for (Row row : results) {
            String v09AgentRollupId = row.getString(0);
            V09AgentRollup v09AgentRollup = v09AgentRollups.get(v09AgentRollupId);
            if (v09AgentRollup == null) {
                // v09AgentRollupId was manually deleted (via the UI) from the agent_rollup
                // table in which case its parent is no longer known and best to ignore
                continue;
            }
            BoundStatement boundStatement = insertPS.bind();
            boundStatement.setString(0, v09AgentRollup.agentRollupId());
            boundStatement.setString(1, row.getString(1));
            boundStatement.setString(2, row.getString(2));
            boundStatement.setInt(3, ttl);
            session.execute(boundStatement);
        }
        dropTableIfExists("trace_attribute_name_temp");
    }

    private void rewriteGaugeNameTablePart1() throws Exception {
        logger.info("rewriting gauge_name table (part 1) - this could take several minutes on large"
                + " data sets ...");
        dropTableIfExists("gauge_name_temp");
        session.execute("create table gauge_name_temp (agent_rollup_id varchar, capture_time"
                + " timestamp, gauge_name varchar, primary key (agent_rollup_id, capture_time,"
                + " gauge_name))");
        PreparedStatement insertTempPS = session.prepare("insert into gauge_name_temp"
                + " (agent_rollup_id, capture_time, gauge_name) values (?, ?, ?) using ttl ?");
        ResultSet results =
                session.execute("select agent_rollup_id, capture_time, gauge_name from gauge_name");
        List<ListenableFuture<ResultSet>> futures = Lists.newArrayList();
        for (Row row : results) {
            BoundStatement boundStatement = insertTempPS.bind();
            boundStatement.setString(0, row.getString(0));
            boundStatement.setTimestamp(1, row.getTimestamp(1));
            boundStatement.setString(2, row.getString(2));
            futures.add(session.executeAsync(boundStatement));
        }
        MoreFutures.waitForAll(futures);
        logger.info("rewriting gauge_name table (part 1) - complete");
    }

    private void rewriteGaugeNameTablePart2() throws Exception {
        logger.info("rewriting gauge_name table (part 2) - this could take several minutes on large"
                + " data sets ...");
        if (!tableExists("gauge_name_temp")) {
            // previously failed mid-upgrade prior to updating schema version
            return;
        }
        CentralStorageConfig storageConfig = getCentralStorageConfig(session);
        dropTableIfExists("gauge_name");
        Map<String, V09AgentRollup> v09AgentRollups = getV09AgentRollupsFromAgentRollupTable();
        session.createTableWithTWCS("create table gauge_name (agent_rollup_id varchar, capture_time"
                + " timestamp, gauge_name varchar, primary key (agent_rollup_id, capture_time,"
                + " gauge_name))", storageConfig.getMaxRollupHours());
        PreparedStatement insertPS = session.prepare("insert into gauge_name (agent_rollup_id,"
                + " capture_time, gauge_name) values (?, ?, ?) using ttl ?");
        int ttl = getCentralStorageConfig(session).getMaxRollupTTL();
        ResultSet results = session
                .execute("select agent_rollup_id, capture_time, gauge_name from gauge_name_temp");
        List<ListenableFuture<ResultSet>> futures = Lists.newArrayList();
        for (Row row : results) {
            String v09AgentRollupId = row.getString(0);
            V09AgentRollup v09AgentRollup = v09AgentRollups.get(v09AgentRollupId);
            if (v09AgentRollup == null) {
                // v09AgentRollupId was manually deleted (via the UI) from the agent_rollup
                // table in which case its parent is no longer known and best to ignore
                continue;
            }
            Date captureDate = checkNotNull(row.getTimestamp(1));
            int adjustedTTL = Common.getAdjustedTTL(ttl, captureDate.getTime(), clock);
            BoundStatement boundStatement = insertPS.bind();
            boundStatement.setString(0, v09AgentRollup.agentRollupId());
            boundStatement.setTimestamp(1, captureDate);
            boundStatement.setString(2, row.getString(2));
            boundStatement.setInt(3, adjustedTTL);
            futures.add(session.executeAsync(boundStatement));
        }
        MoreFutures.waitForAll(futures);
        dropTableIfExists("gauge_name_temp");
        logger.info("rewriting gauge_name table (part 2) - complete");
    }

    private void populateV09AgentRollupTable() throws Exception {
        Map<String, V09AgentRollup> v09AgentRollups = getV09AgentRollupsFromAgentRollupTable();
        PreparedStatement insertPS = null;
        for (V09AgentRollup v09AgentRollup : v09AgentRollups.values()) {
            if (v09AgentRollup.agent() && v09AgentRollup.hasRollup()) {
                // only create v09_agent_check and v09_last_capture_time tables if needed
                if (insertPS == null) {
                    dropTableIfExists("v09_agent_rollup");
                    session.execute("create table v09_agent_rollup (one int, v09_agent_id varchar,"
                            + " v09_agent_rollup_id varchar, primary key (one, v09_agent_id,"
                            + " v09_agent_rollup_id)) " + WITH_LCS);
                    insertPS = session.prepare("insert into v09_agent_rollup"
                            + " (one, v09_agent_id, v09_agent_rollup_id) values (1, ?, ?)");
                }
                BoundStatement boundStatement = insertPS.bind();
                boundStatement.setString(0, v09AgentRollup.agentRollupId());
                int i = 0;
                boundStatement.setString(i++, v09AgentRollup.v09AgentRollupId());
                boundStatement.setString(i++,
                        checkNotNull(v09AgentRollup.v09ParentAgentRollupId()));
                session.execute(boundStatement);
            }
        }
    }

    private void finishV09AgentIdUpdate() throws Exception {
        dropTableIfExists("trace_check");
        // TODO at some point in the future drop agent_rollup
        // (intentionally not dropping it for now, in case any upgrade corrections are needed post
        // v0.10.0)
    }

    private Map<String, V09AgentRollup> getV09AgentRollupsFromAgentRollupTable() throws Exception {
        Map<String, V09AgentRollup> v09AgentRollupIds = Maps.newHashMap();
        ResultSet results = session.execute("select agent_rollup_id, parent_agent_rollup_id, agent"
                + " from agent_rollup where one = 1");
        for (Row row : results) {
            int i = 0;
            String v09AgentRollupId = checkNotNull(row.getString(i++));
            String v09ParentAgentRollupId = row.getString(i++);
            boolean agent = row.getBool(i++);
            boolean hasRollup = v09ParentAgentRollupId != null;
            String agentRollupId;
            if (agent) {
                if (v09ParentAgentRollupId == null) {
                    agentRollupId = v09AgentRollupId;
                } else {
                    agentRollupId =
                            v09ParentAgentRollupId.replace("/", "::") + "::" + v09AgentRollupId;
                }
            } else {
                agentRollupId = v09AgentRollupId.replace("/", "::") + "::";
            }
            v09AgentRollupIds.put(v09AgentRollupId, ImmutableV09AgentRollup.builder()
                    .agent(agent)
                    .hasRollup(hasRollup)
                    .agentRollupId(agentRollupId)
                    .v09AgentRollupId(v09AgentRollupId)
                    .v09ParentAgentRollupId(v09ParentAgentRollupId)
                    .build());
        }
        return v09AgentRollupIds;
    }

    private void addColumnIfNotExists(String tableName, String columnName, String cqlType)
            throws Exception {
        if (!columnExists(tableName, columnName)) {
            session.execute("alter table " + tableName + " add " + columnName + " " + cqlType);
        }
    }

    private void dropColumnIfExists(String tableName, String columnName) throws Exception {
        if (columnExists(tableName, columnName)) {
            session.execute("alter table " + tableName + " drop " + columnName);
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
    private void dropTableIfExists(String tableName) throws Exception {
        Stopwatch stopwatch = Stopwatch.createStarted();
        while (stopwatch.elapsed(SECONDS) < 60) {
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
        } else if (tableName.equals("gauge_name") || tableName.equals("agent")) {
            return getMaxRollupExpirationHours(storageConfig);
        } else if (tableName.equals("heartbeat")) {
            return HeartbeatDao.EXPIRATION_HOURS;
        } else if (tableName.equals("resolved_incident")) {
            return StorageConfig.RESOLVED_INCIDENT_EXPIRATION_HOURS;
        } else {
            logger.warn("unexpected table: {}", tableName);
            return -1;
        }
    }

    private static int getMaxRollupExpirationHours(CentralStorageConfig storageConfig) {
        int maxRollupExpirationHours = 0;
        for (int expirationHours : storageConfig.rollupExpirationHours()) {
            if (expirationHours == 0) {
                // zero value expiration/TTL means never expire
                return 0;
            }
            maxRollupExpirationHours = Math.max(maxRollupExpirationHours, expirationHours);
        }
        return maxRollupExpirationHours;
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
            sb.append("# default is ui.https=false\n");
            sb.append("# set this to \"true\" to serve the UI over HTTPS\n");
            sb.append("# the certificate and private key to be used must be placed in the same"
                    + " directory as this properties\n");
            sb.append("# file, with filenames \"ui-cert.pem\" and \"ui-key.pem\", where ui-cert.pem"
                    + " is an X.509 certificate\n");
            sb.append("# chain file in PEM format, and ui-key.pem is a PKCS#8 private key file in"
                    + " PEM format without a\n");
            sb.append("# passphrase (for example, a self signed certificate can be generated at the"
                    + " command line meeting\n");
            sb.append("# the above requirements using:\n");
            sb.append("# \"openssl req -new -x509 -nodes -days 365 -out ui-cert.pem -keyout"
                    + " ui-key.pem\")\n");
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

    private static CentralStorageConfig getCentralStorageConfig(Session session) throws Exception {
        ResultSet results =
                session.execute("select value from central_config where key = 'storage'");
        Row row = results.one();
        if (row == null) {
            return ImmutableCentralStorageConfig.builder().build();
        }
        String storageConfigText = row.getString(0);
        if (storageConfigText == null) {
            return ImmutableCentralStorageConfig.builder().build();
        }
        try {
            return mapper.readValue(storageConfigText, ImmutableCentralStorageConfig.class);
        } catch (IOException e) {
            logger.warn(e.getMessage(), e);
            return ImmutableCentralStorageConfig.builder().build();
        }
    }

    private static long addExpirationHours(long timeInMillis, int expirationHours) {
        if (expirationHours == 0) {
            // 100 years from now is the same thing as never expire (0)
            return timeInMillis + DAYS.toMillis(365 * 100L);
        } else {
            return timeInMillis + HOURS.toMillis(expirationHours);
        }
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

    @Value.Immutable
    @Styles.AllParameters
    interface AgentRollupIdGaugeNamePair {
        String agentRollupId();
        String gaugeName();
    }

    @Value.Immutable
    interface V09AgentRollup {
        boolean agent();
        boolean hasRollup();
        String agentRollupId();
        String v09AgentRollupId();
        @Nullable
        String v09ParentAgentRollupId();
    }
}
