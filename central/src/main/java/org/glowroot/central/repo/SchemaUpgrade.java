/*
 * Copyright 2016-2019 the original author or authors.
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
import java.io.IOException;
import java.io.Writer;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Queue;
import java.util.Set;
import java.util.UUID;

import com.datastax.driver.core.BoundStatement;
import com.datastax.driver.core.PreparedStatement;
import com.datastax.driver.core.ResultSet;
import com.datastax.driver.core.Row;
import com.datastax.driver.core.TableMetadata;
import com.datastax.driver.core.exceptions.InvalidConfigurationInQueryException;
import com.datastax.driver.core.exceptions.InvalidQueryException;
import com.datastax.driver.core.utils.UUIDs;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Stopwatch;
import com.google.common.collect.ArrayListMultimap;
import com.google.common.collect.HashMultimap;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ListMultimap;
import com.google.common.collect.Multimap;
import com.google.common.collect.Multimaps;
import com.google.common.collect.Ordering;
import com.google.common.collect.Sets;
import com.google.common.primitives.Ints;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.protobuf.InvalidProtocolBufferException;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.immutables.value.Value;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.glowroot.central.util.MoreFutures;
import org.glowroot.central.util.Session;
import org.glowroot.common.ConfigDefaults;
import org.glowroot.common.Constants;
import org.glowroot.common.util.CaptureTimes;
import org.glowroot.common.util.Clock;
import org.glowroot.common.util.ObjectMappers;
import org.glowroot.common.util.PropertiesFiles;
import org.glowroot.common.util.Styles;
import org.glowroot.common2.config.CentralStorageConfig;
import org.glowroot.common2.config.ImmutableCentralStorageConfig;
import org.glowroot.common2.config.ImmutableCentralWebConfig;
import org.glowroot.common2.config.MoreConfigDefaults;
import org.glowroot.common2.config.PermissionParser;
import org.glowroot.common2.repo.ConfigRepository.RollupConfig;
import org.glowroot.common2.repo.util.RollupLevelService;
import org.glowroot.wire.api.model.AgentConfigOuterClass.AgentConfig;
import org.glowroot.wire.api.model.AgentConfigOuterClass.AgentConfig.AdvancedConfig;
import org.glowroot.wire.api.model.AgentConfigOuterClass.AgentConfig.AlertConfig;
import org.glowroot.wire.api.model.AgentConfigOuterClass.AgentConfig.AlertConfig.AlertCondition.HeartbeatCondition;
import org.glowroot.wire.api.model.AgentConfigOuterClass.AgentConfig.AlertConfig.AlertCondition.MetricCondition;
import org.glowroot.wire.api.model.AgentConfigOuterClass.AgentConfig.AlertConfig.AlertCondition.SyntheticMonitorCondition;
import org.glowroot.wire.api.model.AgentConfigOuterClass.AgentConfig.GeneralConfig;
import org.glowroot.wire.api.model.AgentConfigOuterClass.AgentConfig.OldAlertConfig;
import org.glowroot.wire.api.model.AgentConfigOuterClass.AgentConfig.SyntheticMonitorConfig;
import org.glowroot.wire.api.model.AgentConfigOuterClass.AgentConfig.UiDefaultsConfig;
import org.glowroot.wire.api.model.Proto.OptionalInt32;

import static com.google.common.base.Preconditions.checkNotNull;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.nio.file.StandardOpenOption.APPEND;
import static java.nio.file.StandardOpenOption.CREATE;
import static java.util.concurrent.TimeUnit.DAYS;
import static java.util.concurrent.TimeUnit.HOURS;
import static java.util.concurrent.TimeUnit.SECONDS;

public class SchemaUpgrade {

    private static final Logger logger = LoggerFactory.getLogger(SchemaUpgrade.class);

    // log startup messages using logger name "org.glowroot"
    private static final Logger startupLogger = LoggerFactory.getLogger("org.glowroot");

    private static final ObjectMapper mapper = ObjectMappers.create();

    private static final int CURR_SCHEMA_VERSION = 91;

    private final Session session;
    private final Clock clock;
    private final boolean servlet;

    private final PreparedStatement insertIntoSchemVersionPS;
    private final @Nullable Integer initialSchemaVersion;

    private boolean reloadCentralConfiguration;

    public SchemaUpgrade(Session session, Clock clock, boolean servlet) throws Exception {
        this.session = session;
        this.clock = clock;
        this.servlet = servlet;

        session.createTableWithLCS("create table if not exists schema_version (one int,"
                + " schema_version int, primary key (one))");
        insertIntoSchemVersionPS =
                session.prepare("insert into schema_version (one, schema_version) values (1, ?)");
        initialSchemaVersion = getSchemaVersion(session);
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
        startupLogger.info("upgrading glowroot central schema from version {} to version {}...",
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
            updateTwcsDtcsGcSeconds();
            updateSchemaVersion(11);
        }
        if (initialSchemaVersion < 12) {
            updateNeedsRollupGcSeconds();
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
        // 0.10.0 to 0.10.1
        if (initialSchemaVersion < 59) {
            removeTraceTtErrorCountPartialColumn();
            updateSchemaVersion(59);
        }
        if (initialSchemaVersion < 60) {
            removeTraceTnErrorCountPartialColumn();
            updateSchemaVersion(60);
        }
        if (initialSchemaVersion < 61) {
            populateTraceTtSlowCountAndPointPartialPart1();
            updateSchemaVersion(61);
        }
        if (initialSchemaVersion < 62) {
            populateTraceTtSlowCountAndPointPartialPart2();
            updateSchemaVersion(62);
        }
        if (initialSchemaVersion < 63) {
            populateTraceTnSlowCountAndPointPartialPart1();
            updateSchemaVersion(63);
        }
        if (initialSchemaVersion < 64) {
            populateTraceTnSlowCountAndPointPartialPart2();
            updateSchemaVersion(64);
        }
        if (initialSchemaVersion < 65) {
            updateTwcsDtcsGcSeconds();
            updateSchemaVersion(65);
        }
        if (initialSchemaVersion < 66) {
            updateNeedsRollupGcSeconds();
            updateSchemaVersion(66);
        }
        if (initialSchemaVersion < 67) {
            updateLcsUncheckedTombstoneCompaction();
            updateSchemaVersion(67);
        }
        // 0.10.2 to 0.10.3
        if (initialSchemaVersion < 68) {
            updateStcsUncheckedTombstoneCompaction();
            updateSchemaVersion(68);
        }
        if (initialSchemaVersion < 69) {
            optimizeTwcsTables();
            updateSchemaVersion(69);
        }
        if (initialSchemaVersion < 70) {
            changeV09TablesToLCS();
            updateSchemaVersion(70);
        }
        if (initialSchemaVersion < 71) {
            updateCentralStorageConfig();
            updateSchemaVersion(71);
        }
        // 0.10.3 to 0.10.4
        if (initialSchemaVersion < 72) {
            rewriteV09AgentRollupPart1();
            updateSchemaVersion(72);
        }
        if (initialSchemaVersion < 73) {
            rewriteV09AgentRollupPart2();
            updateSchemaVersion(73);
        }
        // 0.10.4 to 0.10.5
        if (initialSchemaVersion >= 69 && initialSchemaVersion < 74) {
            optimizeTwcsTables();
            updateSchemaVersion(74);
        }
        // 0.10.5 to 0.10.6
        if (initialSchemaVersion < 75) {
            updateTraceAttributeNamePartitionKeyPart1();
            updateSchemaVersion(75);
        }
        if (initialSchemaVersion < 76) {
            updateTraceAttributeNamePartitionKeyPart2();
            updateSchemaVersion(76);
        }
        // 0.10.11 to 0.10.12
        if (initialSchemaVersion < 77) {
            populateActiveAgentTable(0);
            updateSchemaVersion(77);
        }
        if (initialSchemaVersion < 78) {
            populateActiveAgentTable(1);
            updateSchemaVersion(78);
        }
        if (initialSchemaVersion < 79) {
            populateActiveAgentTable(2);
            updateSchemaVersion(79);
        }
        if (initialSchemaVersion < 80) {
            populateActiveAgentTable(3);
            updateSchemaVersion(80);
        }
        if (initialSchemaVersion < 81) {
            dropTableIfExists("agent");
            updateSchemaVersion(81);
        }
        // 0.10.12 to 0.11.0
        if (initialSchemaVersion < 82) {
            updateRolePermissionName2();
            updateSchemaVersion(82);
        }
        // 0.11.1 to 0.12.0
        if (initialSchemaVersion < 83) {
            updateEncryptedPasswordAttributeName();
            updateSchemaVersion(83);
        }
        // 0.12.2 to 0.12.3
        if (initialSchemaVersion < 84) {
            populateSyntheticMonitorIdTable();
            updateSchemaVersion(84);
        }
        // 0.12.3 to 0.13.0
        if (initialSchemaVersion < 85) {
            populateAgentDisplayTable();
            updateSchemaVersion(85);
        }
        // 0.13.0 to 0.13.1
        if (initialSchemaVersion < 86) {
            updateTraceSlowCountAndPointPartialTables();
            updateSchemaVersion(86);
        }
        if (initialSchemaVersion < 87) {
            splitActiveAgentRollupTables(0);
            updateSchemaVersion(87);
        }
        if (initialSchemaVersion < 88) {
            splitActiveAgentRollupTables(1);
            updateSchemaVersion(88);
        }
        if (initialSchemaVersion < 89) {
            splitActiveAgentRollupTables(2);
            updateSchemaVersion(89);
        }
        if (initialSchemaVersion < 90) {
            splitActiveAgentRollupTables(3);
            updateSchemaVersion(90);
        }
        if (initialSchemaVersion < 91) {
            addAggregateSummaryColumns();
            updateSchemaVersion(91);
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
        List<String> snappyTableNames = new ArrayList<>();
        List<String> dtcsTableNames = new ArrayList<>();
        for (TableMetadata table : session.getTables()) {
            String compressionClass = table.getOptions().getCompression().get("class");
            if (compressionClass != null && compressionClass
                    .equals("org.apache.cassandra.io.compress.SnappyCompressor")) {
                snappyTableNames.add(compressionClass);
            }
            String compactionClass = table.getOptions().getCompaction().get("class");
            if (compactionClass != null && compactionClass
                    .equals("org.apache.cassandra.db.compaction.DateTieredCompactionStrategy")) {
                dtcsTableNames.add(table.getName());
            }
            if (table.getName().startsWith("trace_") && table.getName().endsWith("_partial")
                    && compactionClass != null && compactionClass.equals(
                            "org.apache.cassandra.db.compaction.SizeTieredCompactionStrategy")) {
                // these need to be updated to TWCS also
                dtcsTableNames.add(table.getName());
            }
        }

        int snappyUpdatedCount = 0;
        for (String tableName : snappyTableNames) {
            session.updateSchemaWithRetry("alter table " + tableName
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
                int expirationHours =
                        RepoAdminImpl.getExpirationHoursForTable(tableName, storageConfig);
                if (expirationHours == -1) {
                    // warning already logged above inside getExpirationHoursForTable()
                    continue;
                }
                session.updateTableTwcsProperties(tableName, expirationHours);
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
        if (dtcsUpdatedCount > 0) {
            startupLogger.info("upgraded {} tables from DateTieredCompactionStrategy to"
                    + " TimeWindowCompactionStrategy compaction", dtcsUpdatedCount);
        }
    }

    private void updateSchemaVersion(int schemaVersion) throws Exception {
        BoundStatement boundStatement = insertIntoSchemVersionPS.bind();
        boundStatement.setInt(0, schemaVersion);
        session.write(boundStatement);
    }

    private void renameAgentColumnFromSystemInfoToEnvironment() throws Exception {
        if (!columnExists("agent", "system_info")) {
            // previously failed mid-upgrade prior to updating schema version
            return;
        }
        addColumnIfNotExists("agent", "environment", "blob");
        ResultSet results = session.read("select agent_id, system_info from agent");
        PreparedStatement preparedStatement =
                session.prepare("insert into agent (agent_id, environment) values (?, ?)");
        for (Row row : results) {
            BoundStatement boundStatement = preparedStatement.bind();
            boundStatement.setString(0, row.getString(0));
            boundStatement.setBytes(1, row.getBytes(1));
            session.write(boundStatement);
        }
        session.updateSchemaWithRetry("alter table agent drop system_info");
    }

    private void updateRoles() throws Exception {
        PreparedStatement insertPS =
                session.prepare("insert into role (name, permissions) values (?, ?)");
        ResultSet results = session.read("select name, permissions from role");
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
            session.write(boundStatement);
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
            session.updateSchemaWithRetry("alter table trace_entry with compression = {'class':"
                    + " 'org.apache.cassandra.io.compress.LZ4Compressor', 'chunk_length_kb' :"
                    + " 64};");
        } catch (InvalidConfigurationInQueryException e) {
            logger.debug(e.getMessage(), e);
            // try with compression options for Cassandra 2.x
            // see https://docs.datastax.com/en/cql/3.1/cql/cql_reference/compressSubprop.html
            session.updateSchemaWithRetry("alter table trace_entry with compression"
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
        session.createTableWithLCS("create table if not exists central_config (key varchar,"
                + " value varchar, primary key (key))");
        ResultSet results = session.read("select key, value from server_config");
        PreparedStatement insertPS =
                session.prepare("insert into central_config (key, value) values (?, ?)");
        for (Row row : results) {
            BoundStatement boundStatement = insertPS.bind();
            boundStatement.setString(0, row.getString(0));
            boundStatement.setString(1, row.getString(1));
            session.write(boundStatement);
        }
        dropTableIfExists("server_config");
    }

    private void addAgentOneTable() throws Exception {
        if (!tableExists("agent_rollup")) {
            // previously failed mid-upgrade prior to updating schema version
            return;
        }
        session.createTableWithLCS("create table if not exists agent_one (one int, agent_id"
                + " varchar, agent_rollup varchar, primary key (one, agent_id))");
        ResultSet results = session.read("select agent_rollup from agent_rollup");
        PreparedStatement insertPS =
                session.prepare("insert into agent_one (one, agent_id) values (1, ?)");
        for (Row row : results) {
            BoundStatement boundStatement = insertPS.bind();
            boundStatement.setString(0, row.getString(0));
            session.write(boundStatement);
        }
        dropTableIfExists("agent_rollup");
    }

    private void addAgentRollupColumn() throws Exception {
        addColumnIfNotExists("agent", "agent_rollup", "varchar");
    }

    private void updateTwcsDtcsGcSeconds() throws Exception {
        logger.info("updating gc_grace_seconds on TWCS/DTCS tables...");
        for (TableMetadata table : session.getTables()) {
            String compactionClass = table.getOptions().getCompaction().get("class");
            if (compactionClass == null) {
                continue;
            }
            if (compactionClass
                    .equals("org.apache.cassandra.db.compaction.TimeWindowCompactionStrategy")
                    || compactionClass.equals(
                            "org.apache.cassandra.db.compaction.DateTieredCompactionStrategy")) {
                // see gc_grace_seconds related comments in Sessions.createTableWithTWCS()
                // for reasoning behind the value of 4 hours
                session.updateSchemaWithRetry("alter table " + table.getName()
                        + " with gc_grace_seconds = " + HOURS.toSeconds(4));
            }
        }
        logger.info("updating gc_grace_seconds on TWCS/DTCS tables - complete");
    }

    private void updateNeedsRollupGcSeconds() throws Exception {
        logger.info("updating gc_grace_seconds on \"needs rollup\" tables...");
        // reduce from default 10 days to 4 hours
        //
        // since rollup operations are idempotent, any records resurrected after gc_grace_seconds
        // would just create extra work, but not have any other effect
        //
        // not using gc_grace_seconds of 0 since that disables hinted handoff
        // (http://www.uberobert.com/cassandra_gc_grace_disables_hinted_handoff)
        //
        // it seems any value over max_hint_window_in_ms (which defaults to 3 hours) is good
        long gcGraceSeconds = HOURS.toSeconds(4);

        if (tableExists("aggregate_needs_rollup_from_child")) {
            session.updateSchemaWithRetry("alter table aggregate_needs_rollup_from_child with"
                    + " gc_grace_seconds = " + gcGraceSeconds);
        }
        session.updateSchemaWithRetry(
                "alter table aggregate_needs_rollup_1 with gc_grace_seconds = " + gcGraceSeconds);
        session.updateSchemaWithRetry(
                "alter table aggregate_needs_rollup_2 with gc_grace_seconds = " + gcGraceSeconds);
        session.updateSchemaWithRetry(
                "alter table aggregate_needs_rollup_3 with gc_grace_seconds = " + gcGraceSeconds);
        if (tableExists("gauge_needs_rollup_from_child")) {
            session.updateSchemaWithRetry("alter table gauge_needs_rollup_from_child with"
                    + " gc_grace_seconds = " + gcGraceSeconds);
        }
        session.updateSchemaWithRetry(
                "alter table gauge_needs_rollup_1 with gc_grace_seconds = " + gcGraceSeconds);
        session.updateSchemaWithRetry(
                "alter table gauge_needs_rollup_2 with gc_grace_seconds = " + gcGraceSeconds);
        session.updateSchemaWithRetry(
                "alter table gauge_needs_rollup_3 with gc_grace_seconds = " + gcGraceSeconds);
        session.updateSchemaWithRetry(
                "alter table gauge_needs_rollup_4 with gc_grace_seconds = " + gcGraceSeconds);
        logger.info("updating gc_grace_seconds on \"needs rollup\" tables - complete");
    }

    private void updateAgentRollup() throws Exception {
        if (!tableExists("agent_one")) {
            // previously failed mid-upgrade prior to updating schema version
            return;
        }
        session.createTableWithLCS("create table if not exists agent_rollup (one int,"
                + " agent_rollup_id varchar, parent_agent_rollup_id varchar, agent boolean,"
                + " display varchar, last_capture_time timestamp, primary key (one,"
                + " agent_rollup_id))");
        ResultSet results =
                session.read("select agent_id, agent_rollup from agent_one");
        PreparedStatement insertPS = session.prepare("insert into agent_rollup (one,"
                + " agent_rollup_id, parent_agent_rollup_id, agent) values (1, ?, ?, ?)");
        Set<String> parentAgentRollupIds = new HashSet<>();
        for (Row row : results) {
            String agentRollupId = row.getString(0);
            String parentAgentRollupId = row.getString(1);
            BoundStatement boundStatement = insertPS.bind();
            int i = 0;
            boundStatement.setString(i++, agentRollupId);
            boundStatement.setString(i++, parentAgentRollupId);
            boundStatement.setBool(i++, true);
            session.write(boundStatement);
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
            session.write(boundStatement);
        }
        session.updateSchemaWithRetry("alter table agent drop agent_rollup");
        dropTableIfExists("agent_one");
    }

    private static List<String> getAgentRollupIds(String agentRollupId) {
        List<String> agentRollupIds = new ArrayList<>();
        int lastFoundIndex = -1;
        int nextFoundIndex;
        while ((nextFoundIndex = agentRollupId.indexOf('/', lastFoundIndex + 1)) != -1) {
            agentRollupIds.add(agentRollupId.substring(0, nextFoundIndex));
            lastFoundIndex = nextFoundIndex;
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
        session.createTableWithLCS("create table if not exists config (agent_rollup_id varchar,"
                + " config blob, config_update boolean, config_update_token uuid, primary key"
                + " (agent_rollup_id))");
        session.createTableWithLCS("create table if not exists environment (agent_id varchar,"
                + " environment blob, primary key (agent_id))");

        ResultSet results =
                session.read("select agent_rollup_id, agent from agent_rollup where one = 1");
        List<String> agentIds = new ArrayList<>();
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
            results = session.read(boundStatement);
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
            session.write(boundStatement);

            boundStatement = insertConfigPS.bind();
            i = 0;
            boundStatement.setString(i++, agentId);
            boundStatement.setBytes(i++, configBytes);
            boundStatement.setBool(i++, configUpdate);
            boundStatement.setUUID(i++, configUpdateToken);
            session.write(boundStatement);
        }
        dropTableIfExists("agent");
    }

    private void initialPopulationOfConfigForRollups() throws Exception {
        ResultSet results = session.read("select agent_rollup_id,"
                + " parent_agent_rollup_id, agent from agent_rollup where one = 1");
        List<String> agentRollupIds = new ArrayList<>();
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
                .setUiDefaultsConfig(UiDefaultsConfig.newBuilder()
                        .setDefaultTransactionType(ConfigDefaults.UI_DEFAULTS_TRANSACTION_TYPE)
                        .addAllDefaultPercentile(ConfigDefaults.UI_DEFAULTS_PERCENTILES))
                .setAdvancedConfig(AdvancedConfig.newBuilder()
                        .setMaxQueryAggregates(OptionalInt32.newBuilder()
                                .setValue(ConfigDefaults.ADVANCED_MAX_QUERY_AGGREGATES))
                        .setMaxServiceCallAggregates(OptionalInt32.newBuilder()
                                .setValue(ConfigDefaults.ADVANCED_MAX_SERVICE_CALL_AGGREGATES)))
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
                session.write(boundStatement);
                continue;
            }
            String childAgentId = iterator.next();
            BoundStatement boundStatement = readPS.bind();
            boundStatement.setString(0, childAgentId);
            Row row = session.read(boundStatement).one();

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
                            .setUiDefaultsConfig(agentConfig.getUiDefaultsConfig())
                            .setAdvancedConfig(AdvancedConfig.newBuilder()
                                    .setMaxQueryAggregates(advancedConfig.getMaxQueryAggregates())
                                    .setMaxServiceCallAggregates(
                                            advancedConfig.getMaxServiceCallAggregates()))
                            .build();
                    boundStatement.setBytes(1, ByteBuffer.wrap(updatedAgentConfig.toByteArray()));
                } catch (InvalidProtocolBufferException e) {
                    logger.error(e.getMessage(), e);
                    boundStatement.setBytes(1, ByteBuffer.wrap(defaultAgentConfig.toByteArray()));
                }
            }
            session.write(boundStatement);
        }
    }

    private void redoOnTriggeredAlertTable() throws Exception {
        dropTableIfExists("triggered_alert");
    }

    private void addSyntheticMonitorAndAlertPermissions() throws Exception {
        PreparedStatement insertPS =
                session.prepare("insert into role (name, permissions) values (?, ?)");
        ResultSet results = session.read("select name, permissions from role");
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
            session.write(boundStatement);
        }
    }

    private void updateWebConfig() throws Exception {
        ResultSet results =
                session.read("select value from central_config where key = 'web'");
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
        session.write(boundStatement);
    }

    private void removeInvalidAgentRollupRows() throws Exception {
        ResultSet results =
                session.read("select agent_rollup_id, agent from agent_rollup");
        PreparedStatement deletePS =
                session.prepare("delete from agent_rollup where one = 1 and agent_rollup_id = ?");
        for (Row row : results) {
            if (row.isNull(1)) {
                BoundStatement boundStatement = deletePS.bind();
                boundStatement.setString(0, checkNotNull(row.getString(0)));
                session.write(boundStatement);
            }
        }
    }

    private void renameConfigTable() throws Exception {
        if (!tableExists("config")) {
            // previously failed mid-upgrade prior to updating schema version
            return;
        }
        session.createTableWithLCS("create table if not exists agent_config (agent_rollup_id"
                + " varchar, config blob, config_update boolean, config_update_token uuid,"
                + " primary key (agent_rollup_id))");
        ResultSet results = session.read("select agent_rollup_id, config,"
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
            session.write(boundStatement);
        }
        dropTableIfExists("config");
    }

    private void upgradeAlertConfigs() throws Exception {
        PreparedStatement insertPS = session.prepare("insert into agent_config (agent_rollup_id,"
                + " config, config_update, config_update_token) values (?, ?, ?, ?)");
        ResultSet results = session.read("select agent_rollup_id, config from agent_config");
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
            session.write(boundStatement);
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
        ResultSet results = session.read("select name, permissions from role");
        for (Row row : results) {
            String name = row.getString(0);
            Set<String> permissions = row.getSet(1, String.class);
            boolean updated = false;
            Set<String> upgradedPermissions = new HashSet<>();
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
                session.write(boundStatement);
            }
        }
    }

    private void updateSmtpConfig() throws Exception {
        ResultSet results =
                session.read("select value from central_config where key = 'smtp'");
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
        session.write(boundStatement);
    }

    private void addDefaultGaugeNameToUiConfigs() throws Exception {
        PreparedStatement insertPS = session.prepare("insert into agent_config (agent_rollup_id,"
                + " config, config_update, config_update_token) values (?, ?, ?, ?)");
        ResultSet results = session.read("select agent_rollup_id, config from agent_config");
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
                    .setUiDefaultsConfig(oldAgentConfig.getUiDefaultsConfig().toBuilder()
                            .addAllDefaultGaugeName(ConfigDefaults.UI_DEFAULTS_GAUGE_NAMES))
                    .build();
            BoundStatement boundStatement = insertPS.bind();
            int i = 0;
            boundStatement.setString(i++, agentRollupId);
            boundStatement.setBytes(i++, ByteBuffer.wrap(agentConfig.toByteArray()));
            boundStatement.setBool(i++, true);
            boundStatement.setUUID(i++, UUIDs.random());
            session.write(boundStatement);
        }
    }

    // fix bad upgrade that inserted 'smtp' config row into 'web' config row
    private void sortOfFixWebConfig() throws Exception {
        ResultSet results =
                session.read("select value from central_config where key = 'web'");
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
            session.read("delete from central_config where key = 'web'");
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
                + " large data sets...");
        CentralStorageConfig storageConfig = getCentralStorageConfig(session);
        int maxRollupHours = storageConfig.getMaxRollupHours();
        dropTableIfExists("gauge_name");
        session.createTableWithTWCS("create table if not exists gauge_name (agent_rollup_id"
                + " varchar, capture_time timestamp, gauge_name varchar, primary key"
                + " (agent_rollup_id, capture_time, gauge_name))", maxRollupHours);
        PreparedStatement insertPS = session.prepare("insert into gauge_name (agent_rollup_id,"
                + " capture_time, gauge_name) values (?, ?, ?) using ttl ?");
        Multimap<Long, AgentRollupIdGaugeNamePair> rowsPerCaptureTime = HashMultimap.create();
        ResultSet results = session
                .read("select agent_rollup, gauge_name, capture_time from gauge_value_rollup_4");
        for (Row row : results) {
            int i = 0;
            String agentRollupId = checkNotNull(row.getString(i++));
            String gaugeName = checkNotNull(row.getString(i++));
            long captureTime = checkNotNull(row.getTimestamp(i++)).getTime();
            long millisPerDay = DAYS.toMillis(1);
            long rollupCaptureTime = CaptureTimes.getRollup(captureTime, millisPerDay);
            rowsPerCaptureTime.put(rollupCaptureTime,
                    ImmutableAgentRollupIdGaugeNamePair.of(agentRollupId, gaugeName));
        }
        // read from 1-min gauge values to get not-yet-rolled-up data
        // (not using 5-second gauge values since those don't exist for agent rollups)
        results = session
                .read("select agent_rollup, gauge_name, capture_time from gauge_value_rollup_1");
        for (Row row : results) {
            int i = 0;
            String agentRollupId = checkNotNull(row.getString(i++));
            String gaugeName = checkNotNull(row.getString(i++));
            long captureTime = checkNotNull(row.getTimestamp(i++)).getTime();
            long millisPerDay = DAYS.toMillis(1);
            long rollupCaptureTime = CaptureTimes.getRollup(captureTime, millisPerDay);
            rowsPerCaptureTime.put(rollupCaptureTime,
                    ImmutableAgentRollupIdGaugeNamePair.of(agentRollupId, gaugeName));
        }
        int maxRollupTTL = storageConfig.getMaxRollupTTL();
        Queue<ListenableFuture<?>> futures = new ArrayDeque<>();
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
                futures.add(session.writeAsync(boundStatement));
                waitForSome(futures);
            }
        }
        MoreFutures.waitForAll(futures);
        logger.info("populating new gauge name history table - complete");
    }

    private void populateAgentConfigGeneral() throws Exception {
        if (!columnExists("agent_rollup", "display")) {
            // previously failed mid-upgrade prior to updating schema version
            return;
        }
        ResultSet results =
                session.read("select agent_rollup_id, display from agent_rollup where one = 1");
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
            Row configRow = session.read(boundStatement).one();
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
            session.write(boundStatement);
        }
        dropColumnIfExists("agent_rollup", "display");
    }

    private void populateV09AgentCheckTable() throws Exception {
        int fullQueryTextExpirationHours = getFullQueryTextExpirationHours();
        Map<String, V09AgentRollup> v09AgentRollups = getV09AgentRollupsFromAgentRollupTable();
        PreparedStatement insertV09AgentCheckPS = null;
        for (V09AgentRollup v09AgentRollup : v09AgentRollups.values()) {
            if (v09AgentRollup.agent() && v09AgentRollup.hasRollup()) {
                // only create v09_agent_check and v09_last_capture_time tables if needed
                if (insertV09AgentCheckPS == null) {
                    dropTableIfExists("v09_last_capture_time");
                    session.createTableWithLCS("create table if not exists v09_last_capture_time"
                            + " (one int, v09_last_capture_time timestamp,"
                            + " v09_fqt_last_expiration_time timestamp,"
                            + " v09_trace_last_expiration_time timestamp,"
                            + " v09_aggregate_last_expiration_time timestamp, primary key (one))");
                    BoundStatement boundStatement = session.prepare("insert into"
                            + " v09_last_capture_time (one, v09_last_capture_time,"
                            + " v09_fqt_last_expiration_time, v09_trace_last_expiration_time,"
                            + " v09_aggregate_last_expiration_time) values (1, ?, ?, ?, ?)")
                            .bind();
                    long nextDailyRollup = RollupLevelService.getCeilRollupTime(
                            clock.currentTimeMillis(), DAYS.toMillis(1));
                    CentralStorageConfig storageConfig = getCentralStorageConfig(session);
                    long v09FqtLastExpirationTime =
                            addExpirationHours(nextDailyRollup, fullQueryTextExpirationHours);
                    long v09TraceLastExpirationTime = addExpirationHours(nextDailyRollup,
                            storageConfig.traceExpirationHours());
                    long v09AggregateLastExpirationTime = addExpirationHours(nextDailyRollup,
                            storageConfig.getMaxRollupHours());
                    int i = 0;
                    boundStatement.setTimestamp(i++, new Date(nextDailyRollup));
                    boundStatement.setTimestamp(i++, new Date(v09FqtLastExpirationTime));
                    boundStatement.setTimestamp(i++, new Date(v09TraceLastExpirationTime));
                    boundStatement.setTimestamp(i++, new Date(v09AggregateLastExpirationTime));
                    session.write(boundStatement);

                    dropTableIfExists("v09_agent_check");
                    session.createTableWithLCS("create table if not exists v09_agent_check (one"
                            + " int, agent_id varchar, primary key (one, agent_id))");
                    insertV09AgentCheckPS = session.prepare(
                            "insert into v09_agent_check (one, agent_id) values (1, ?)");
                }
                BoundStatement boundStatement = insertV09AgentCheckPS.bind();
                boundStatement.setString(0, v09AgentRollup.agentRollupId());
                session.write(boundStatement);
            }
        }
    }

    private int getFullQueryTextExpirationHours() throws Exception {
        // since fullQueryTextExpirationHours is no longer part of CentralStorageConfig (starting
        // with 0.10.3, it must be pulled from json
        ResultSet results =
                session.read("select value from central_config where key = 'storage'");
        Row row = results.one();
        if (row == null) {
            // 2 weeks was the default
            return 24 * 14;
        }
        String storageConfigText = row.getString(0);
        if (storageConfigText == null) {
            // 2 weeks was the default
            return 24 * 14;
        }
        try {
            JsonNode node = mapper.readTree(storageConfigText);
            // 2 weeks was the default
            return node.path("fullQueryTextExpirationHours").asInt(24 * 14);
        } catch (IOException e) {
            logger.warn(e.getMessage(), e);
            // 2 weeks was the default
            return 24 * 14;
        }
    }

    private void populateAgentHistoryTable() throws Exception {
        logger.info("populating new agent history table - this could take several minutes on large"
                + " data sets...");
        CentralStorageConfig storageConfig = getCentralStorageConfig(session);
        dropTableIfExists("agent");
        session.createTableWithTWCS("create table if not exists agent (one int, capture_time"
                + " timestamp, agent_id varchar, primary key (one, capture_time, agent_id))",
                storageConfig.getMaxRollupHours());
        PreparedStatement insertPS = session.prepare("insert into agent (one, capture_time,"
                + " agent_id) values (1, ?, ?) using ttl ?");
        Map<String, V09AgentRollup> v09AgentRollups = getV09AgentRollupsFromAgentRollupTable();
        Multimap<Long, String> agentIdsPerCaptureTime = HashMultimap.create();
        ResultSet results = session
                .read("select agent_rollup, capture_time from aggregate_tt_throughput_rollup_3");
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
            long rollupCaptureTime = CaptureTimes.getRollup(captureTime, millisPerDay);
            agentIdsPerCaptureTime.put(rollupCaptureTime, agentId);
        }
        // read from 1-min aggregates to get not-yet-rolled-up data
        results = session
                .read("select agent_rollup, capture_time from aggregate_tt_throughput_rollup_0");
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
            long rollupCaptureTime = CaptureTimes.getRollup(captureTime, millisPerDay);
            agentIdsPerCaptureTime.put(rollupCaptureTime, agentId);
        }
        int maxRollupTTL = storageConfig.getMaxRollupTTL();
        List<Long> sortedCaptureTimes =
                Ordering.natural().sortedCopy(agentIdsPerCaptureTime.keySet());
        Queue<ListenableFuture<?>> futures = new ArrayDeque<>();
        for (long captureTime : sortedCaptureTimes) {
            int adjustedTTL = Common.getAdjustedTTL(maxRollupTTL, captureTime, clock);
            for (String agentId : agentIdsPerCaptureTime.get(captureTime)) {
                BoundStatement boundStatement = insertPS.bind();
                int i = 0;
                boundStatement.setTimestamp(i++, new Date(captureTime));
                boundStatement.setString(i++, agentId);
                boundStatement.setInt(i++, adjustedTTL);
                futures.add(session.writeAsync(boundStatement));
                waitForSome(futures);
            }
        }
        MoreFutures.waitForAll(futures);
        logger.info("populating new agent history table - complete");
    }

    private void rewriteAgentConfigTablePart1() throws Exception {
        dropTableIfExists("agent_config_temp");
        session.updateSchemaWithRetry("create table if not exists agent_config_temp"
                + " (agent_rollup_id varchar, config blob, config_update boolean,"
                + " config_update_token uuid, primary key (agent_rollup_id))");
        PreparedStatement insertTempPS = session.prepare("insert into agent_config_temp"
                + " (agent_rollup_id, config, config_update, config_update_token) values"
                + " (?, ?, ?, ?)");
        ResultSet results = session.read("select agent_rollup_id, config, config_update,"
                + " config_update_token from agent_config");
        for (Row row : results) {
            BoundStatement boundStatement = insertTempPS.bind();
            boundStatement.setString(0, row.getString(0));
            boundStatement.setBytes(1, row.getBytes(1));
            boundStatement.setBool(2, row.getBool(2));
            boundStatement.setUUID(3, row.getUUID(3));
            session.write(boundStatement);
        }
    }

    private void rewriteAgentConfigTablePart2() throws Exception {
        if (!tableExists("agent_config_temp")) {
            // previously failed mid-upgrade prior to updating schema version
            return;
        }
        dropTableIfExists("agent_config");
        session.createTableWithLCS("create table if not exists agent_config (agent_rollup_id"
                + " varchar, config blob, config_update boolean, config_update_token uuid, primary"
                + " key (agent_rollup_id))");
        PreparedStatement insertPS = session.prepare("insert into agent_config"
                + " (agent_rollup_id, config, config_update, config_update_token) values"
                + " (?, ?, ?, ?)");
        Map<String, V09AgentRollup> v09AgentRollups = getV09AgentRollupsFromAgentRollupTable();
        ResultSet results = session.read("select agent_rollup_id, config, config_update,"
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
            session.write(boundStatement);
        }
        dropTableIfExists("agent_config_temp");
    }

    private void rewriteEnvironmentTablePart1() throws Exception {
        dropTableIfExists("environment_temp");
        session.updateSchemaWithRetry("create table if not exists environment_temp (agent_id"
                + " varchar, environment blob, primary key (agent_id))");
        PreparedStatement insertTempPS = session
                .prepare("insert into environment_temp (agent_id, environment) values (?, ?)");
        ResultSet results = session.read("select agent_id, environment from environment");
        for (Row row : results) {
            BoundStatement boundStatement = insertTempPS.bind();
            boundStatement.setString(0, row.getString(0));
            boundStatement.setBytes(1, row.getBytes(1));
            session.write(boundStatement);
        }
    }

    private void rewriteEnvironmentTablePart2() throws Exception {
        if (!tableExists("environment_temp")) {
            // previously failed mid-upgrade prior to updating schema version
            return;
        }
        dropTableIfExists("environment");
        session.createTableWithLCS("create table if not exists environment (agent_id varchar,"
                + " environment blob, primary key (agent_id))");
        PreparedStatement insertPS = session
                .prepare("insert into environment (agent_id, environment) values (?, ?)");
        Map<String, V09AgentRollup> v09AgentRollups = getV09AgentRollupsFromAgentRollupTable();
        ResultSet results = session.read("select agent_id, environment from environment_temp");
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
            session.write(boundStatement);
        }
        dropTableIfExists("environment_temp");
    }

    private void rewriteOpenIncidentTablePart1() throws Exception {
        if (!tableExists("open_incident")) {
            // must be upgrading all the way from a glowroot version prior to open_incident
            return;
        }
        dropTableIfExists("open_incident_temp");
        session.updateSchemaWithRetry("create table if not exists open_incident_temp (one int,"
                + " agent_rollup_id varchar, condition blob, severity varchar, notification blob,"
                + " open_time timestamp, primary key (one, agent_rollup_id, condition, severity))");
        PreparedStatement insertTempPS = session.prepare("insert into open_incident_temp (one,"
                + " agent_rollup_id, condition, severity, notification, open_time) values"
                + " (1, ?, ?, ?, ?, ?)");
        ResultSet results = session.read("select agent_rollup_id, condition, severity,"
                + " notification, open_time from open_incident where one = 1");
        for (Row row : results) {
            BoundStatement boundStatement = insertTempPS.bind();
            boundStatement.setString(0, row.getString(0));
            boundStatement.setBytes(1, row.getBytes(1));
            boundStatement.setString(2, row.getString(2));
            boundStatement.setBytes(3, row.getBytes(3));
            boundStatement.setTimestamp(4, row.getTimestamp(4));
            session.write(boundStatement);
        }
    }

    private void rewriteOpenIncidentTablePart2() throws Exception {
        if (!tableExists("open_incident_temp")) {
            // previously failed mid-upgrade prior to updating schema version
            return;
        }
        dropTableIfExists("open_incident");
        session.createTableWithLCS("create table if not exists open_incident (one int,"
                + " agent_rollup_id varchar, condition blob, severity varchar, notification blob,"
                + " open_time timestamp, primary key (one, agent_rollup_id, condition, severity))");
        PreparedStatement insertPS = session.prepare("insert into open_incident (one,"
                + " agent_rollup_id, condition, severity, notification, open_time) values"
                + " (1, ?, ?, ?, ?, ?)");
        Map<String, V09AgentRollup> v09AgentRollups = getV09AgentRollupsFromAgentRollupTable();
        ResultSet results = session.read("select agent_rollup_id, condition, severity,"
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
            session.write(boundStatement);
        }
        dropTableIfExists("open_incident_temp");
    }

    private void rewriteResolvedIncidentTablePart1() throws Exception {
        if (!tableExists("resolved_incident")) {
            // must be upgrading all the way from a glowroot version prior to resolved_incident
            return;
        }
        dropTableIfExists("resolved_incident_temp");
        session.updateSchemaWithRetry("create table if not exists resolved_incident_temp (one int,"
                + " resolve_time timestamp, agent_rollup_id varchar, condition blob, severity"
                + " varchar, notification blob, open_time timestamp, primary key (one,"
                + " resolve_time, agent_rollup_id, condition)) with clustering order by"
                + " (resolve_time desc)");
        PreparedStatement insertTempPS = session.prepare("insert into resolved_incident_temp"
                + " (one, resolve_time, agent_rollup_id, condition, severity, notification,"
                + " open_time) values (1, ?, ?, ?, ?, ?, ?)");
        ResultSet results = session.read("select resolve_time, agent_rollup_id, condition,"
                + " severity, notification, open_time from resolved_incident where one = 1");
        for (Row row : results) {
            BoundStatement boundStatement = insertTempPS.bind();
            boundStatement.setTimestamp(0, row.getTimestamp(0));
            boundStatement.setString(1, row.getString(1));
            boundStatement.setBytes(2, row.getBytes(2));
            boundStatement.setString(3, row.getString(3));
            boundStatement.setBytes(4, row.getBytes(4));
            boundStatement.setTimestamp(5, row.getTimestamp(5));
            session.write(boundStatement);
        }
    }

    private void rewriteResolvedIncidentTablePart2() throws Exception {
        if (!tableExists("resolved_incident_temp")) {
            // previously failed mid-upgrade prior to updating schema version
            return;
        }
        dropTableIfExists("resolved_incident");
        session.createTableWithTWCS("create table if not exists resolved_incident (one int,"
                + " resolve_time timestamp, agent_rollup_id varchar, condition blob, severity"
                + " varchar, notification blob, open_time timestamp, primary key (one,"
                + " resolve_time, agent_rollup_id, condition)) with clustering order by"
                + " (resolve_time desc)", Constants.RESOLVED_INCIDENT_EXPIRATION_HOURS, true);
        PreparedStatement insertPS = session.prepare("insert into resolved_incident (one,"
                + " resolve_time, agent_rollup_id, condition, severity, notification,"
                + " open_time) values (1, ?, ?, ?, ?, ?, ?) using ttl ?");
        Map<String, V09AgentRollup> v09AgentRollups = getV09AgentRollupsFromAgentRollupTable();
        int ttl = Ints.saturatedCast(
                HOURS.toSeconds(Constants.RESOLVED_INCIDENT_EXPIRATION_HOURS));
        ResultSet results = session.read("select resolve_time, agent_rollup_id, condition,"
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
            session.write(boundStatement);
        }
        dropTableIfExists("resolved_incident_temp");
    }

    private void rewriteRoleTablePart1() throws Exception {
        dropTableIfExists("role_temp");
        session.updateSchemaWithRetry("create table if not exists role_temp (name varchar,"
                + " permissions set<varchar>, primary key (name))");
        PreparedStatement insertTempPS =
                session.prepare("insert into role_temp (name, permissions) values (?, ?)");
        ResultSet results = session.read("select name, permissions from role");
        for (Row row : results) {
            BoundStatement boundStatement = insertTempPS.bind();
            boundStatement.setString(0, row.getString(0));
            boundStatement.setSet(1, row.getSet(1, String.class));
            session.write(boundStatement);
        }
    }

    private void rewriteRoleTablePart2() throws Exception {
        if (!tableExists("role_temp")) {
            // previously failed mid-upgrade prior to updating schema version
            return;
        }
        dropTableIfExists("role");
        session.createTableWithLCS("create table if not exists role (name varchar, permissions"
                + " set<varchar>, primary key (name))");
        PreparedStatement insertPS =
                session.prepare("insert into role (name, permissions) values (?, ?)");
        Map<String, V09AgentRollup> v09AgentRollups = getV09AgentRollupsFromAgentRollupTable();
        ResultSet results = session.read("select name, permissions from role_temp");
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
                List<String> agentRollupIds = new ArrayList<>();
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
            session.write(boundStatement);
        }
        dropTableIfExists("role_temp");
    }

    private void rewriteHeartbeatTablePart1() throws Exception {
        if (!tableExists("heartbeat")) {
            // must be upgrading all the way from a glowroot version prior to heartbeat
            return;
        }
        logger.info("rewriting heartbeat table (part 1)...");
        dropTableIfExists("heartbeat_temp");
        session.updateSchemaWithRetry("create table if not exists heartbeat_temp (agent_id varchar,"
                + " central_capture_time timestamp, primary key (agent_id, central_capture_time))");
        PreparedStatement insertTempPS = session.prepare("insert into heartbeat_temp (agent_id,"
                + " central_capture_time) values (?, ?)");
        ResultSet results = session.read("select agent_id, central_capture_time from heartbeat");
        Queue<ListenableFuture<?>> futures = new ArrayDeque<>();
        for (Row row : results) {
            BoundStatement boundStatement = insertTempPS.bind();
            boundStatement.setString(0, row.getString(0));
            boundStatement.setTimestamp(1, row.getTimestamp(1));
            futures.add(session.writeAsync(boundStatement));
            waitForSome(futures);
        }
        MoreFutures.waitForAll(futures);
        logger.info("rewriting heartbeat table (part 1) - complete");
    }

    private void rewriteHeartbeatTablePart2() throws Exception {
        if (!tableExists("heartbeat_temp")) {
            // previously failed mid-upgrade prior to updating schema version
            return;
        }
        logger.info("rewriting heartbeat table (part 2)...");
        Map<String, V09AgentRollup> v09AgentRollups = getV09AgentRollupsFromAgentRollupTable();
        dropTableIfExists("heartbeat");
        session.createTableWithTWCS("create table if not exists heartbeat (agent_id varchar,"
                + " central_capture_time timestamp, primary key (agent_id, central_capture_time))",
                HeartbeatDao.EXPIRATION_HOURS);
        PreparedStatement insertPS = session.prepare("insert into heartbeat (agent_id,"
                + " central_capture_time) values (?, ?) using ttl ?");
        int ttl = Ints.saturatedCast(HOURS.toSeconds(HeartbeatDao.EXPIRATION_HOURS));
        ResultSet results =
                session.read("select agent_id, central_capture_time from heartbeat_temp");
        Queue<ListenableFuture<?>> futures = new ArrayDeque<>();
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
            futures.add(session.writeAsync(boundStatement));
            waitForSome(futures);
        }
        MoreFutures.waitForAll(futures);
        dropTableIfExists("heartbeat_temp");
        logger.info("rewriting heartbeat table (part 2) - complete");
    }

    private void rewriteTransactionTypeTablePart1() throws Exception {
        dropTableIfExists("transaction_type_temp");
        session.updateSchemaWithRetry("create table if not exists transaction_type_temp (one int,"
                + " agent_rollup varchar, transaction_type varchar, primary key (one, agent_rollup,"
                + " transaction_type))");
        PreparedStatement insertTempPS = session.prepare("insert into transaction_type_temp (one,"
                + " agent_rollup, transaction_type) values (1, ?, ?)");
        ResultSet results = session.read(
                "select agent_rollup, transaction_type from transaction_type where one = 1");
        for (Row row : results) {
            BoundStatement boundStatement = insertTempPS.bind();
            boundStatement.setString(0, row.getString(0));
            boundStatement.setString(1, row.getString(1));
            session.write(boundStatement);
        }
    }

    private void rewriteTransactionTypeTablePart2() throws Exception {
        if (!tableExists("transaction_type_temp")) {
            // previously failed mid-upgrade prior to updating schema version
            return;
        }
        dropTableIfExists("transaction_type");
        Map<String, V09AgentRollup> v09AgentRollups = getV09AgentRollupsFromAgentRollupTable();
        session.createTableWithLCS("create table if not exists transaction_type (one int,"
                + " agent_rollup varchar, transaction_type varchar, primary key (one, agent_rollup,"
                + " transaction_type))");
        PreparedStatement insertPS = session.prepare("insert into transaction_type (one,"
                + " agent_rollup, transaction_type) values (1, ?, ?) using ttl ?");
        int ttl = getCentralStorageConfig(session).getMaxRollupTTL();
        ResultSet results = session.read(
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
            session.write(boundStatement);
        }
        dropTableIfExists("transaction_type_temp");
    }

    private void rewriteTraceAttributeNameTablePart1() throws Exception {
        dropTableIfExists("trace_attribute_name_temp");
        session.updateSchemaWithRetry("create table if not exists trace_attribute_name_temp"
                + " (agent_rollup varchar, transaction_type varchar, trace_attribute_name varchar,"
                + " primary key ((agent_rollup, transaction_type), trace_attribute_name))");
        PreparedStatement insertTempPS = session.prepare("insert into trace_attribute_name_temp"
                + " (agent_rollup, transaction_type, trace_attribute_name) values (?, ?, ?)");
        ResultSet results = session.read("select agent_rollup, transaction_type,"
                + " trace_attribute_name from trace_attribute_name");
        for (Row row : results) {
            BoundStatement boundStatement = insertTempPS.bind();
            boundStatement.setString(0, row.getString(0));
            boundStatement.setString(1, row.getString(1));
            boundStatement.setString(2, row.getString(2));
            session.write(boundStatement);
        }
    }

    private void rewriteTraceAttributeNameTablePart2() throws Exception {
        if (!tableExists("trace_attribute_name_temp")) {
            // previously failed mid-upgrade prior to updating schema version
            return;
        }
        dropTableIfExists("trace_attribute_name");
        Map<String, V09AgentRollup> v09AgentRollups = getV09AgentRollupsFromAgentRollupTable();
        session.createTableWithLCS("create table if not exists trace_attribute_name (agent_rollup"
                + " varchar, transaction_type varchar, trace_attribute_name varchar, primary key"
                + " ((agent_rollup, transaction_type), trace_attribute_name))");
        PreparedStatement insertPS = session.prepare("insert into trace_attribute_name"
                + " (agent_rollup, transaction_type, trace_attribute_name) values (?, ?, ?) using"
                + " ttl ?");
        int ttl = getCentralStorageConfig(session).getTraceTTL();
        ResultSet results = session.read("select agent_rollup, transaction_type,"
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
            session.write(boundStatement);
        }
        dropTableIfExists("trace_attribute_name_temp");
    }

    private void rewriteGaugeNameTablePart1() throws Exception {
        logger.info("rewriting gauge_name table (part 1) - this could take several minutes on large"
                + " data sets...");
        dropTableIfExists("gauge_name_temp");
        session.updateSchemaWithRetry("create table if not exists gauge_name_temp (agent_rollup_id"
                + " varchar, capture_time timestamp, gauge_name varchar, primary key"
                + " (agent_rollup_id, capture_time, gauge_name))");
        PreparedStatement insertTempPS = session.prepare("insert into gauge_name_temp"
                + " (agent_rollup_id, capture_time, gauge_name) values (?, ?, ?)");
        ResultSet results =
                session.read("select agent_rollup_id, capture_time, gauge_name from gauge_name");
        Queue<ListenableFuture<?>> futures = new ArrayDeque<>();
        for (Row row : results) {
            BoundStatement boundStatement = insertTempPS.bind();
            boundStatement.setString(0, row.getString(0));
            boundStatement.setTimestamp(1, row.getTimestamp(1));
            boundStatement.setString(2, row.getString(2));
            futures.add(session.writeAsync(boundStatement));
            waitForSome(futures);
        }
        MoreFutures.waitForAll(futures);
        logger.info("rewriting gauge_name table (part 1) - complete");
    }

    private void rewriteGaugeNameTablePart2() throws Exception {
        if (!tableExists("gauge_name_temp")) {
            // previously failed mid-upgrade prior to updating schema version
            return;
        }
        logger.info("rewriting gauge_name table (part 2) - this could take several minutes on large"
                + " data sets...");
        CentralStorageConfig storageConfig = getCentralStorageConfig(session);
        dropTableIfExists("gauge_name");
        Map<String, V09AgentRollup> v09AgentRollups = getV09AgentRollupsFromAgentRollupTable();
        session.createTableWithTWCS("create table if not exists gauge_name (agent_rollup_id"
                + " varchar, capture_time timestamp, gauge_name varchar, primary key"
                + " (agent_rollup_id, capture_time, gauge_name))",
                storageConfig.getMaxRollupHours());
        PreparedStatement insertPS = session.prepare("insert into gauge_name (agent_rollup_id,"
                + " capture_time, gauge_name) values (?, ?, ?) using ttl ?");
        int ttl = getCentralStorageConfig(session).getMaxRollupTTL();
        ResultSet results = session
                .read("select agent_rollup_id, capture_time, gauge_name from gauge_name_temp");
        Queue<ListenableFuture<?>> futures = new ArrayDeque<>();
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
            futures.add(session.writeAsync(boundStatement));
            waitForSome(futures);
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
                    session.createTableWithLCS("create table if not exists v09_agent_rollup (one"
                            + " int, v09_agent_id varchar, v09_agent_rollup_id varchar, primary key"
                            + " (one, v09_agent_id, v09_agent_rollup_id))");
                    insertPS = session.prepare("insert into v09_agent_rollup"
                            + " (one, v09_agent_id, v09_agent_rollup_id) values (1, ?, ?)");
                }
                BoundStatement boundStatement = insertPS.bind();
                boundStatement.setString(0, v09AgentRollup.agentRollupId());
                int i = 0;
                boundStatement.setString(i++, v09AgentRollup.v09AgentRollupId());
                boundStatement.setString(i++,
                        checkNotNull(v09AgentRollup.v09ParentAgentRollupId()));
                session.write(boundStatement);
            }
        }
    }

    private Map<String, V09AgentRollup> getV09AgentRollupsFromAgentRollupTable() throws Exception {
        Map<String, V09AgentRollup> v09AgentRollupIds = new HashMap<>();
        ResultSet results = session.read("select agent_rollup_id, parent_agent_rollup_id, agent"
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

    private void finishV09AgentIdUpdate() throws Exception {
        dropTableIfExists("trace_check");
        // TODO at some point in the future drop agent_rollup
        // (intentionally not dropping it for now, in case any upgrade corrections are needed post
        // v0.10.0)
    }

    private void removeTraceTtErrorCountPartialColumn() throws Exception {
        // this column is unused
        dropColumnIfExists("trace_tt_error_point", "partial");
    }

    private void removeTraceTnErrorCountPartialColumn() throws Exception {
        // this column is unused
        dropColumnIfExists("trace_tn_error_point", "partial");
    }

    private void populateTraceTtSlowCountAndPointPartialPart1() throws Exception {
        logger.info("populating trace_tt_slow_count_partial and trace_tt_slow_point_partial tables"
                + " - this could take several minutes on large data sets...");
        CentralStorageConfig storageConfig = getCentralStorageConfig(session);
        dropTableIfExists("trace_tt_slow_count_partial");
        dropTableIfExists("trace_tt_slow_point_partial");
        session.createTableWithTWCS("create table if not exists trace_tt_slow_count_partial"
                + " (agent_rollup varchar, transaction_type varchar, capture_time timestamp,"
                + " agent_id varchar, trace_id varchar, primary key ((agent_rollup,"
                + " transaction_type), capture_time, agent_id, trace_id))",
                storageConfig.traceExpirationHours(), false, true);
        session.createTableWithTWCS("create table if not exists trace_tt_slow_point_partial"
                + " (agent_rollup varchar, transaction_type varchar, capture_time timestamp,"
                + " agent_id varchar, trace_id varchar, duration_nanos bigint, error boolean,"
                + " headline varchar, user varchar, attributes blob, primary key ((agent_rollup,"
                + " transaction_type), capture_time, agent_id, trace_id))",
                storageConfig.traceExpirationHours(), false, true);
        PreparedStatement insertCountPartialPS = session.prepare("insert into"
                + " trace_tt_slow_count_partial (agent_rollup, transaction_type, capture_time,"
                + " agent_id, trace_id) values (?, ?, ?, ?, ?) using ttl ?");
        PreparedStatement insertPointPartialPS = session.prepare("insert into"
                + " trace_tt_slow_point_partial (agent_rollup, transaction_type, capture_time,"
                + " agent_id, trace_id, duration_nanos, error, headline, user, attributes) values"
                + " (?, ?, ?, ?, ?, ?, ?, ?, ?, ?) using ttl ?");
        int ttl = getCentralStorageConfig(session).getTraceTTL();
        ResultSet results = session.read("select agent_rollup, transaction_type, capture_time,"
                + " agent_id, trace_id, duration_nanos, error, headline, user, attributes, partial"
                + " from trace_tt_slow_point");
        Queue<ListenableFuture<?>> futures = new ArrayDeque<>();
        Stopwatch stopwatch = Stopwatch.createStarted();
        int rowCount = 0;
        for (Row row : results) {
            if (!row.getBool(10)) { // partial
                // unfortunately cannot use "where partial = true allow filtering" in the query
                // above as that leads to ReadTimeoutException
                continue;
            }
            BoundStatement boundStatement = insertCountPartialPS.bind();
            int i = 0;
            copyString(row, boundStatement, i++); // agent_rollup
            copyString(row, boundStatement, i++); // transaction_type
            Date captureDate = checkNotNull(row.getTimestamp(i));
            int adjustedTTL = Common.getAdjustedTTL(ttl, captureDate.getTime(), clock);
            copyTimestamp(row, boundStatement, i++); // capture_time
            copyString(row, boundStatement, i++); // agent_id
            copyString(row, boundStatement, i++); // trace_id
            boundStatement.setInt(i++, adjustedTTL);
            futures.add(session.writeAsync(boundStatement));

            boundStatement = insertPointPartialPS.bind();
            i = 0;
            copyString(row, boundStatement, i++); // agent_rollup
            copyString(row, boundStatement, i++); // transaction_type
            copyTimestamp(row, boundStatement, i++); // capture_time
            copyString(row, boundStatement, i++); // agent_id
            copyString(row, boundStatement, i++); // trace_id
            copyLong(row, boundStatement, i++); // duration_nanos
            copyBool(row, boundStatement, i++); // error
            copyString(row, boundStatement, i++); // headline
            copyString(row, boundStatement, i++); // user
            copyBytes(row, boundStatement, i++); // attributes
            boundStatement.setInt(i++, adjustedTTL);
            futures.add(session.writeAsync(boundStatement));

            rowCount++;
            if (stopwatch.elapsed(SECONDS) > 60) {
                logger.info("processed {} records", rowCount);
                stopwatch.reset().start();
            }
            waitForSome(futures);
        }
        MoreFutures.waitForAll(futures);
        logger.info("populating trace_tt_slow_count_partial and trace_tt_slow_point_partial tables"
                + " - complete");
    }

    private void populateTraceTtSlowCountAndPointPartialPart2() throws Exception {
        if (!columnExists("trace_tt_slow_point", "partial")) {
            // previously failed mid-upgrade prior to updating schema version
            return;
        }
        PreparedStatement deleteCountPS = session.prepare("delete from trace_tt_slow_count where"
                + " agent_rollup = ? and transaction_type = ? and capture_time = ? and agent_id = ?"
                + " and trace_id = ?");
        PreparedStatement deletePointPS = session.prepare("delete from trace_tt_slow_point where"
                + " agent_rollup = ? and transaction_type = ? and capture_time = ? and agent_id = ?"
                + " and trace_id = ?");
        ResultSet results = session.read("select agent_rollup, transaction_type, capture_time,"
                + " agent_id, trace_id from trace_tt_slow_count_partial");
        Queue<ListenableFuture<?>> futures = new ArrayDeque<>();
        for (Row row : results) {
            BoundStatement boundStatement = deleteCountPS.bind();
            int i = 0;
            copyString(row, boundStatement, i++); // agent_rollup
            copyString(row, boundStatement, i++); // transaction_type
            copyTimestamp(row, boundStatement, i++); // capture_time
            copyString(row, boundStatement, i++); // agent_id
            copyString(row, boundStatement, i++); // trace_id
            futures.add(session.writeAsync(boundStatement));

            boundStatement = deletePointPS.bind();
            i = 0;
            copyString(row, boundStatement, i++); // agent_rollup
            copyString(row, boundStatement, i++); // transaction_type
            copyTimestamp(row, boundStatement, i++); // capture_time
            copyString(row, boundStatement, i++); // agent_id
            copyString(row, boundStatement, i++); // trace_id
            futures.add(session.writeAsync(boundStatement));
            waitForSome(futures);
        }
        MoreFutures.waitForAll(futures);
        dropColumnIfExists("trace_tt_slow_point", "partial");
    }

    private void populateTraceTnSlowCountAndPointPartialPart1() throws Exception {
        logger.info("populating trace_tn_slow_count_partial and trace_tn_slow_point_partial tables"
                + " - this could take several minutes on large data sets...");
        CentralStorageConfig storageConfig = getCentralStorageConfig(session);
        dropTableIfExists("trace_tn_slow_count_partial");
        dropTableIfExists("trace_tn_slow_point_partial");
        session.createTableWithTWCS("create table if not exists trace_tn_slow_count_partial"
                + " (agent_rollup varchar, transaction_type varchar, transaction_name varchar,"
                + " capture_time timestamp, agent_id varchar, trace_id varchar, primary key"
                + " ((agent_rollup, transaction_type, transaction_name), capture_time, agent_id,"
                + " trace_id))", storageConfig.traceExpirationHours(), false, true);
        session.createTableWithTWCS("create table if not exists trace_tn_slow_point_partial"
                + " (agent_rollup varchar, transaction_type varchar, transaction_name varchar,"
                + " capture_time timestamp, agent_id varchar, trace_id varchar, duration_nanos"
                + " bigint, error boolean, headline varchar, user varchar, attributes blob, primary"
                + " key ((agent_rollup, transaction_type, transaction_name), capture_time,"
                + " agent_id, trace_id))", storageConfig.traceExpirationHours(), false, true);
        PreparedStatement insertCountPartialPS = session.prepare("insert into"
                + " trace_tn_slow_count_partial (agent_rollup, transaction_type, transaction_name,"
                + " capture_time, agent_id, trace_id) values (?, ?, ?, ?, ?, ?) using ttl ?");
        PreparedStatement insertPointPartialPS = session.prepare("insert into"
                + " trace_tn_slow_point_partial (agent_rollup, transaction_type, transaction_name,"
                + " capture_time, agent_id, trace_id, duration_nanos, error, headline, user,"
                + " attributes) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) using ttl ?");
        int ttl = getCentralStorageConfig(session).getTraceTTL();
        ResultSet results = session.read("select agent_rollup, transaction_type,"
                + " transaction_name, capture_time, agent_id, trace_id, duration_nanos, error,"
                + " headline, user, attributes, partial from trace_tn_slow_point");
        Queue<ListenableFuture<?>> futures = new ArrayDeque<>();
        Stopwatch stopwatch = Stopwatch.createStarted();
        int rowCount = 0;
        for (Row row : results) {
            if (!row.getBool(11)) { // partial
                // unfortunately cannot use "where partial = true allow filtering" in the query
                // above as that leads to ReadTimeoutException
                continue;
            }
            BoundStatement boundStatement = insertCountPartialPS.bind();
            int i = 0;
            copyString(row, boundStatement, i++); // agent_rollup
            copyString(row, boundStatement, i++); // transaction_type
            copyString(row, boundStatement, i++); // transaction_name
            Date captureDate = checkNotNull(row.getTimestamp(i));
            int adjustedTTL = Common.getAdjustedTTL(ttl, captureDate.getTime(), clock);
            copyTimestamp(row, boundStatement, i++); // capture_time
            copyString(row, boundStatement, i++); // agent_id
            copyString(row, boundStatement, i++); // trace_id
            boundStatement.setInt(i++, adjustedTTL);
            futures.add(session.writeAsync(boundStatement));

            boundStatement = insertPointPartialPS.bind();
            i = 0;
            copyString(row, boundStatement, i++); // agent_rollup
            copyString(row, boundStatement, i++); // transaction_type
            copyString(row, boundStatement, i++); // transaction_name
            copyTimestamp(row, boundStatement, i++); // capture_time
            copyString(row, boundStatement, i++); // agent_id
            copyString(row, boundStatement, i++); // trace_id
            copyLong(row, boundStatement, i++); // duration_nanos
            copyBool(row, boundStatement, i++); // error
            copyString(row, boundStatement, i++); // headline
            copyString(row, boundStatement, i++); // user
            copyBytes(row, boundStatement, i++); // attributes
            boundStatement.setInt(i++, adjustedTTL);
            futures.add(session.writeAsync(boundStatement));

            rowCount++;
            if (stopwatch.elapsed(SECONDS) > 60) {
                logger.info("processed {} records", rowCount);
                stopwatch.reset().start();
            }
            waitForSome(futures);
        }
        MoreFutures.waitForAll(futures);
        logger.info("populating trace_tn_slow_count_partial and trace_tn_slow_point_partial tables"
                + " - complete");
    }

    private void populateTraceTnSlowCountAndPointPartialPart2() throws Exception {
        if (!columnExists("trace_tn_slow_point", "partial")) {
            // previously failed mid-upgrade prior to updating schema version
            return;
        }
        PreparedStatement deleteCountPS = session.prepare("delete from trace_tn_slow_count where"
                + " agent_rollup = ? and transaction_type = ? and transaction_name = ? and"
                + " capture_time = ? and agent_id = ? and trace_id = ?");
        PreparedStatement deletePointPS = session.prepare("delete from trace_tn_slow_point where"
                + " agent_rollup = ? and transaction_type = ? and transaction_name = ? and"
                + " capture_time = ? and agent_id = ? and trace_id = ?");
        ResultSet results = session.read("select agent_rollup, transaction_type,"
                + " transaction_name, capture_time, agent_id, trace_id from"
                + " trace_tn_slow_count_partial");
        Queue<ListenableFuture<?>> futures = new ArrayDeque<>();
        for (Row row : results) {
            BoundStatement boundStatement = deleteCountPS.bind();
            int i = 0;
            copyString(row, boundStatement, i++); // agent_rollup
            copyString(row, boundStatement, i++); // transaction_type
            copyString(row, boundStatement, i++); // transaction_name
            copyTimestamp(row, boundStatement, i++); // capture_time
            copyString(row, boundStatement, i++); // agent_id
            copyString(row, boundStatement, i++); // trace_id
            futures.add(session.writeAsync(boundStatement));

            boundStatement = deletePointPS.bind();
            i = 0;
            copyString(row, boundStatement, i++); // agent_rollup
            copyString(row, boundStatement, i++); // transaction_type
            copyString(row, boundStatement, i++); // transaction_name
            copyTimestamp(row, boundStatement, i++); // capture_time
            copyString(row, boundStatement, i++); // agent_id
            copyString(row, boundStatement, i++); // trace_id
            futures.add(session.writeAsync(boundStatement));
            waitForSome(futures);
        }
        MoreFutures.waitForAll(futures);
        dropColumnIfExists("trace_tn_slow_point", "partial");
    }

    private void updateLcsUncheckedTombstoneCompaction() throws Exception {
        for (TableMetadata table : session.getTables()) {
            String compactionClass = table.getOptions().getCompaction().get("class");
            if (compactionClass != null && compactionClass
                    .equals("org.apache.cassandra.db.compaction.LeveledCompactionStrategy")) {
                session.updateSchemaWithRetry("alter table " + table.getName()
                        + " with compaction = { 'class' : 'LeveledCompactionStrategy',"
                        + " 'unchecked_tombstone_compaction' : true }");
            }
        }
    }

    private void updateStcsUncheckedTombstoneCompaction() throws Exception {
        for (TableMetadata table : session.getTables()) {
            String compactionClass = table.getOptions().getCompaction().get("class");
            if (compactionClass != null && compactionClass
                    .equals("org.apache.cassandra.db.compaction.SizeTieredCompactionStrategy")) {
                session.updateSchemaWithRetry("alter table " + table.getName()
                        + " with compaction = { 'class' : 'SizeTieredCompactionStrategy',"
                        + " 'unchecked_tombstone_compaction' : true }");
            }
        }
    }

    private void optimizeTwcsTables() throws Exception {
        for (TableMetadata table : session.getTables()) {
            Map<String, String> compaction = table.getOptions().getCompaction();
            String compactionClass = compaction.get("class");
            if (compactionClass != null && compactionClass
                    .equals("org.apache.cassandra.db.compaction.TimeWindowCompactionStrategy")) {
                String compactionWindowUnit = compaction.get("compaction_window_unit");
                if (compactionWindowUnit == null) {
                    logger.warn("compaction_window_unit missing for table: {}", table.getName());
                    continue;
                }
                String compactionWindowSizeText = compaction.get("compaction_window_size");
                if (compactionWindowSizeText == null) {
                    logger.warn("compaction_window_size missing for table: {}", table.getName());
                    continue;
                }
                int compactionWindowSize;
                try {
                    compactionWindowSize = Integer.parseInt(compactionWindowSizeText);
                } catch (NumberFormatException e) {
                    logger.warn("unable to parse compaction_window_size value: {}",
                            compactionWindowSizeText);
                    continue;
                }
                session.updateTableTwcsProperties(table.getName(), compactionWindowUnit,
                        compactionWindowSize);
            }
        }
    }

    private void changeV09TablesToLCS() throws Exception {
        if (tableExists("v09_last_capture_time")) {
            session.updateSchemaWithRetry("alter table v09_last_capture_time with compaction = {"
                    + " 'class' : 'LeveledCompactionStrategy', 'unchecked_tombstone_compaction' :"
                    + " true }");
        }
        if (tableExists("v09_agent_check")) {
            session.updateSchemaWithRetry("alter table v09_agent_check with compaction = { 'class'"
                    + " : 'LeveledCompactionStrategy', 'unchecked_tombstone_compaction' : true }");
        }
    }

    private void updateCentralStorageConfig() throws Exception {
        ResultSet results =
                session.read("select value from central_config where key = 'storage'");
        Row row = results.one();
        if (row == null) {
            return;
        }
        String storageConfigText = row.getString(0);
        if (storageConfigText == null) {
            return;
        }
        CentralStorageConfig storageConfig;
        try {
            ObjectNode node = (ObjectNode) mapper.readTree(storageConfigText);
            node.remove("fullQueryTextExpirationHours");
            storageConfig = mapper.readValue(mapper.treeAsTokens(node),
                    ImmutableCentralStorageConfig.class);
        } catch (IOException e) {
            logger.warn(e.getMessage(), e);
            return;
        }
        PreparedStatement insertPS =
                session.prepare("insert into central_config (key, value) values (?, ?)");
        BoundStatement boundStatement = insertPS.bind();
        boundStatement.setString(0, "storage");
        boundStatement.setString(1, mapper.writeValueAsString(storageConfig));
        session.write(boundStatement);
    }

    private void rewriteV09AgentRollupPart1() throws Exception {
        if (!tableExists("v09_agent_rollup")) {
            // must be upgrading all the way from a glowroot version prior to v09_agent_rollup
            return;
        }
        dropTableIfExists("v09_agent_rollup_temp");
        session.createTableWithLCS("create table if not exists v09_agent_rollup_temp (one int,"
                + " v09_agent_id varchar, v09_agent_rollup_id varchar, primary key (one,"
                + " v09_agent_id))");
        PreparedStatement insertTempPS = session.prepare("insert into v09_agent_rollup_temp (one,"
                + " v09_agent_id, v09_agent_rollup_id) values (1, ?, ?)");
        ResultSet results = session.read(
                "select v09_agent_id, v09_agent_rollup_id from v09_agent_rollup where one = 1");
        for (Row row : results) {
            BoundStatement boundStatement = insertTempPS.bind();
            boundStatement.setString(0, row.getString(0));
            boundStatement.setString(1, row.getString(1));
            session.write(boundStatement);
        }
    }

    private void rewriteV09AgentRollupPart2() throws Exception {
        if (!tableExists("v09_agent_rollup_temp")) {
            // previously failed mid-upgrade prior to updating schema version
            return;
        }
        dropTableIfExists("v09_agent_rollup");
        session.createTableWithLCS("create table if not exists v09_agent_rollup (one int,"
                + " v09_agent_id varchar, v09_agent_rollup_id varchar, primary key (one,"
                + " v09_agent_id))");
        PreparedStatement insertPS = session.prepare("insert into v09_agent_rollup (one,"
                + " v09_agent_id, v09_agent_rollup_id) values (1, ?, ?)");
        ResultSet results = session.read("select v09_agent_id, v09_agent_rollup_id from"
                + " v09_agent_rollup_temp where one = 1");
        for (Row row : results) {
            BoundStatement boundStatement = insertPS.bind();
            boundStatement.setString(0, row.getString(0));
            boundStatement.setString(1, row.getString(1));
            session.write(boundStatement);
        }
        dropTableIfExists("v09_agent_rollup_temp");
    }

    private void updateTraceAttributeNamePartitionKeyPart1() throws Exception {
        dropTableIfExists("trace_attribute_name_temp");
        session.updateSchemaWithRetry("create table if not exists trace_attribute_name_temp"
                + " (agent_rollup varchar, transaction_type varchar, trace_attribute_name varchar,"
                + " primary key (agent_rollup, transaction_type, trace_attribute_name))");
        PreparedStatement insertTempPS = session.prepare("insert into trace_attribute_name_temp"
                + " (agent_rollup, transaction_type, trace_attribute_name) values (?, ?, ?)");
        ResultSet results = session.read("select agent_rollup, transaction_type,"
                + " trace_attribute_name from trace_attribute_name");
        Queue<ListenableFuture<?>> futures = new ArrayDeque<>();
        for (Row row : results) {
            BoundStatement boundStatement = insertTempPS.bind();
            boundStatement.setString(0, row.getString(0));
            boundStatement.setString(1, row.getString(1));
            boundStatement.setString(2, row.getString(2));
            futures.add(session.writeAsync(boundStatement));
            waitForSome(futures);
        }
        MoreFutures.waitForAll(futures);
    }

    private void updateTraceAttributeNamePartitionKeyPart2() throws Exception {
        if (!tableExists("trace_attribute_name_temp")) {
            // previously failed mid-upgrade prior to updating schema version
            return;
        }
        dropTableIfExists("trace_attribute_name");
        session.createTableWithLCS("create table if not exists trace_attribute_name (agent_rollup"
                + " varchar, transaction_type varchar, trace_attribute_name varchar, primary key"
                + " (agent_rollup, transaction_type, trace_attribute_name))");
        PreparedStatement insertPS = session.prepare("insert into trace_attribute_name"
                + " (agent_rollup, transaction_type, trace_attribute_name) values (?, ?, ?) using"
                + " ttl ?");
        int ttl = getCentralStorageConfig(session).getTraceTTL();
        ResultSet results = session.read("select agent_rollup, transaction_type,"
                + " trace_attribute_name from trace_attribute_name_temp");
        Queue<ListenableFuture<?>> futures = new ArrayDeque<>();
        for (Row row : results) {
            BoundStatement boundStatement = insertPS.bind();
            boundStatement.setString(0, row.getString(0));
            boundStatement.setString(1, row.getString(1));
            boundStatement.setString(2, row.getString(2));
            boundStatement.setInt(3, ttl);
            futures.add(session.writeAsync(boundStatement));
            waitForSome(futures);
        }
        MoreFutures.waitForAll(futures);
        dropTableIfExists("trace_attribute_name_temp");
    }

    private void populateActiveAgentTable(int rollupLevel) throws Exception {
        logger.info("populating active_agent_rollup_{} table - this could take"
                + " several minutes on large data sets...", rollupLevel);
        dropTableIfExists("active_agent_rollup_" + rollupLevel);
        int expirationHours =
                getCentralStorageConfig(session).rollupExpirationHours().get(rollupLevel);
        session.createTableWithTWCS("create table if not exists active_agent_rollup_" + rollupLevel
                + " (one int, capture_time timestamp, agent_id varchar, primary key (one,"
                + " capture_time, agent_id))", expirationHours);
        PreparedStatement insertPS = session.prepare("insert into active_agent_rollup_"
                + rollupLevel + " (one, capture_time, agent_id) values (1, ?, ?) using ttl ?");
        int ttl = Ints.saturatedCast(HOURS.toSeconds(expirationHours));
        long rollupIntervalMillis;
        if (rollupLevel < 3) {
            rollupIntervalMillis =
                    RollupConfig.buildRollupConfigs().get(rollupLevel + 1).intervalMillis();
        } else {
            rollupIntervalMillis = DAYS.toMillis(1);
        }
        int[] negativeOffsets = new int[(int) (DAYS.toMillis(1) / rollupIntervalMillis)];
        for (int i = 0; i < negativeOffsets.length; i++) {
            negativeOffsets[i] = (int) (rollupIntervalMillis * (i + 1 - negativeOffsets.length));
        }
        PreparedStatement readPS = session.prepare(
                "select capture_time, agent_id from agent where one = 1 and capture_time > ?");
        BoundStatement boundStatement = readPS.bind();
        long now = clock.currentTimeMillis();
        boundStatement.setTimestamp(0, new Date(now - HOURS.toMillis(expirationHours)));
        ResultSet results = session.read(boundStatement);
        Queue<ListenableFuture<?>> futures = new ArrayDeque<>();
        for (Row row : results) {
            Date captureDate = checkNotNull(row.getTimestamp(0));
            String agentId = row.getString(1);
            for (int negativeOffset : negativeOffsets) {
                long offsetCaptureTime = captureDate.getTime() + negativeOffset;
                int adjustedTTL = Common.getAdjustedTTL(ttl, offsetCaptureTime, clock);
                boundStatement = insertPS.bind();
                boundStatement.setTimestamp(0, new Date(offsetCaptureTime));
                boundStatement.setString(1, agentId);
                boundStatement.setInt(2, adjustedTTL);
                futures.add(session.writeAsync(boundStatement));
                waitForSome(futures);
                if (offsetCaptureTime > now) {
                    break;
                }
            }
        }
        MoreFutures.waitForAll(futures);
        logger.info("populating active_agent_rollup_{} table - complete", rollupLevel);
    }

    private void updateRolePermissionName2() throws Exception {
        PreparedStatement insertPS =
                session.prepare("insert into role (name, permissions) values (?, ?)");
        ResultSet results = session.read("select name, permissions from role");
        for (Row row : results) {
            String name = row.getString(0);
            Set<String> permissions = row.getSet(1, String.class);
            boolean updated = false;
            Set<String> upgradedPermissions = new HashSet<>();
            for (String permission : permissions) {
                PermissionParser parser = new PermissionParser(permission);
                parser.parse();
                if (parser.getPermission().equals("agent:transaction:profile")) {
                    upgradedPermissions.add("agent:"
                            + PermissionParser.quoteIfNeededAndJoin(parser.getAgentRollupIds())
                            + ":transaction:threadProfile");
                    updated = true;
                } else if (parser.getPermission().equals("agent:config:edit:gauge")) {
                    upgradedPermissions.add("agent:"
                            + PermissionParser.quoteIfNeededAndJoin(parser.getAgentRollupIds())
                            + ":config:edit:gauges");
                    updated = true;
                } else if (parser.getPermission().equals("agent:config:edit:syntheticMonitor")) {
                    upgradedPermissions.add("agent:"
                            + PermissionParser.quoteIfNeededAndJoin(parser.getAgentRollupIds())
                            + ":config:edit:syntheticMonitors");
                    updated = true;
                } else if (parser.getPermission().equals("agent:config:edit:alert")) {
                    upgradedPermissions.add("agent:"
                            + PermissionParser.quoteIfNeededAndJoin(parser.getAgentRollupIds())
                            + ":config:edit:alerts");
                    updated = true;
                } else if (parser.getPermission().equals("agent:config:edit:plugin")) {
                    upgradedPermissions.add("agent:"
                            + PermissionParser.quoteIfNeededAndJoin(parser.getAgentRollupIds())
                            + ":config:edit:plugins");
                    updated = true;
                } else if (parser.getPermission().equals("agent:config:edit:ui")) {
                    upgradedPermissions.add("agent:"
                            + PermissionParser.quoteIfNeededAndJoin(parser.getAgentRollupIds())
                            + ":config:edit:uiDefaults");
                    updated = true;
                } else {
                    upgradedPermissions.add(permission);
                }
            }
            if (updated) {
                BoundStatement boundStatement = insertPS.bind();
                boundStatement.setString(0, name);
                boundStatement.setSet(1, upgradedPermissions, String.class);
                session.write(boundStatement);
            }
        }
    }

    private void updateEncryptedPasswordAttributeName() throws Exception {
        PreparedStatement readPS =
                session.prepare("select value from central_config where key = ?");
        PreparedStatement insertPS =
                session.prepare("insert into central_config (key, value) values (?, ?)");
        updateEncryptedPasswordAttributeName("smtp", readPS, insertPS);
        updateEncryptedPasswordAttributeName("httpProxy", readPS, insertPS);
        updateEncryptedPasswordAttributeName("ldap", readPS, insertPS);
    }

    private void updateEncryptedPasswordAttributeName(String key, PreparedStatement readPS,
            PreparedStatement insertPS) throws Exception {
        BoundStatement boundStatement = readPS.bind();
        boundStatement.setString(0, key);
        ResultSet results = session.read(boundStatement);
        Row row = results.one();
        if (row == null) {
            return;
        }
        String configText = row.getString(0);
        if (configText == null) {
            return;
        }
        JsonNode jsonNode = mapper.readTree(configText);
        if (jsonNode == null || !jsonNode.isObject()) {
            return;
        }
        ObjectNode objectNode = (ObjectNode) jsonNode;
        JsonNode passwordNode = objectNode.remove("password");
        if (passwordNode != null) {
            objectNode.set("encryptedPassword", passwordNode);
        }
        String updatedConfigText = mapper.writeValueAsString(objectNode);
        boundStatement = insertPS.bind();
        boundStatement.setString(0, key);
        boundStatement.setString(1, updatedConfigText);
        session.write(boundStatement);
    }

    private void populateSyntheticMonitorIdTable() throws Exception {
        if (!tableExists("synthetic_result_rollup_0")) {
            // must be upgrading from a very old version
            return;
        }
        CentralStorageConfig storageConfig = getCentralStorageConfig(session);
        int maxRollupHours = storageConfig.getMaxRollupHours();
        dropTableIfExists("synthetic_monitor_id");
        session.createTableWithTWCS("create table if not exists synthetic_monitor_id"
                + " (agent_rollup_id varchar, capture_time timestamp, synthetic_monitor_id varchar,"
                + " synthetic_monitor_display varchar, primary key (agent_rollup_id, capture_time,"
                + " synthetic_monitor_id))", maxRollupHours);
        PreparedStatement insertPS = session.prepare("insert into synthetic_monitor_id"
                + " (agent_rollup_id, capture_time, synthetic_monitor_id,"
                + " synthetic_monitor_display) values (?, ?, ?, ?) using ttl ?");
        Multimap<Long, AgentRollupIdSyntheticMonitorIdPair> rowsPerCaptureTime =
                HashMultimap.create();
        ResultSet results = session.read("select agent_rollup_id, synthetic_config_id, capture_time"
                + " from synthetic_result_rollup_3");
        for (Row row : results) {
            int i = 0;
            String agentRollupId = checkNotNull(row.getString(i++));
            String syntheticMonitorId = checkNotNull(row.getString(i++));
            long captureTime = checkNotNull(row.getTimestamp(i++)).getTime();
            long millisPerDay = DAYS.toMillis(1);
            long rollupCaptureTime = CaptureTimes.getRollup(captureTime, millisPerDay);
            rowsPerCaptureTime.put(rollupCaptureTime, ImmutableAgentRollupIdSyntheticMonitorIdPair
                    .of(agentRollupId, syntheticMonitorId));
        }
        // read from 1-min synthetic results to get not-yet-rolled-up data
        results = session.read("select agent_rollup_id, synthetic_config_id, capture_time from"
                + " synthetic_result_rollup_0");
        for (Row row : results) {
            int i = 0;
            String agentRollupId = checkNotNull(row.getString(i++));
            String syntheticMonitorId = checkNotNull(row.getString(i++));
            long captureTime = checkNotNull(row.getTimestamp(i++)).getTime();
            long millisPerDay = DAYS.toMillis(1);
            long rollupCaptureTime = CaptureTimes.getRollup(captureTime, millisPerDay);
            rowsPerCaptureTime.put(rollupCaptureTime, ImmutableAgentRollupIdSyntheticMonitorIdPair
                    .of(agentRollupId, syntheticMonitorId));
        }
        int maxRollupTTL = storageConfig.getMaxRollupTTL();
        Queue<ListenableFuture<?>> futures = new ArrayDeque<>();
        List<Long> sortedCaptureTimes =
                Ordering.natural().sortedCopy(rowsPerCaptureTime.keySet());
        Map<String, Map<String, String>> syntheticMonitorDisplays = new HashMap<>();
        PreparedStatement readAgentConfig =
                session.prepare("select config from agent_config where agent_rollup_id = ?");
        for (long captureTime : sortedCaptureTimes) {
            int adjustedTTL = Common.getAdjustedTTL(maxRollupTTL, captureTime, clock);
            for (AgentRollupIdSyntheticMonitorIdPair row : rowsPerCaptureTime.get(captureTime)) {
                BoundStatement boundStatement = insertPS.bind();
                int i = 0;
                boundStatement.setString(i++, row.agentRollupId());
                boundStatement.setTimestamp(i++, new Date(captureTime));
                boundStatement.setString(i++, row.syntheticMonitorId());
                Map<String, String> innerMap = syntheticMonitorDisplays.get(row.agentRollupId());
                if (innerMap == null) {
                    innerMap = getSyntheticMonitorDisplays(readAgentConfig, row.agentRollupId());
                    syntheticMonitorDisplays.put(row.agentRollupId(), innerMap);
                }
                String display = innerMap.get(row.syntheticMonitorId());
                if (display == null) {
                    display = row.syntheticMonitorId() + " (deleted prior to 0.12.3)";
                }
                boundStatement.setString(i++, display);
                boundStatement.setInt(i++, adjustedTTL);
                futures.add(session.writeAsync(boundStatement));
                waitForSome(futures);
            }
        }
        MoreFutures.waitForAll(futures);
    }

    private Map<String, String> getSyntheticMonitorDisplays(PreparedStatement readAgentConfig,
            String agentRollupId) throws Exception {
        BoundStatement boundStatement = readAgentConfig.bind();
        boundStatement.setString(0, agentRollupId);
        ResultSet results = session.read(boundStatement);
        if (results.isExhausted()) {
            return ImmutableMap.of();
        }
        AgentConfig agentConfig;
        try {
            Row row = checkNotNull(results.one());
            agentConfig = AgentConfig.parseFrom(checkNotNull(row.getBytes(0)));
        } catch (InvalidProtocolBufferException e) {
            logger.error(e.getMessage(), e);
            return ImmutableMap.of();
        }
        Map<String, String> syntheticMonitorDisplays = new HashMap<>();
        for (SyntheticMonitorConfig config : agentConfig.getSyntheticMonitorConfigList()) {
            syntheticMonitorDisplays.put(config.getId(),
                    MoreConfigDefaults.getDisplayOrDefault(config));
        }
        return syntheticMonitorDisplays;
    }

    private void populateAgentDisplayTable() throws Exception {
        dropTableIfExists("agent_display");
        session.createTableWithLCS("create table if not exists agent_display (agent_rollup_id"
                + " varchar, display varchar, primary key (agent_rollup_id))");
        PreparedStatement insertPS = session
                .prepare("insert into agent_display (agent_rollup_id, display) values (?, ?)");
        ResultSet results = session.read("select agent_rollup_id, config from agent_config");
        Queue<ListenableFuture<?>> futures = new ArrayDeque<>();
        for (Row row : results) {
            String agentRollupId = row.getString(0);
            AgentConfig agentConfig;
            try {
                agentConfig = AgentConfig.parseFrom(checkNotNull(row.getBytes(1)));
            } catch (InvalidProtocolBufferException e) {
                logger.error(e.getMessage(), e);
                continue;
            }
            String display = agentConfig.getGeneralConfig().getDisplay();
            if (!display.isEmpty()) {
                BoundStatement boundStatement = insertPS.bind();
                int i = 0;
                boundStatement.setString(i++, agentRollupId);
                boundStatement.setString(i++, display);
                futures.add(session.writeAsync(boundStatement));
                waitForSome(futures);
            }
        }
        MoreFutures.waitForAll(futures);
    }

    private void updateTraceSlowCountAndPointPartialTables() throws Exception {
        addColumnIfNotExists("trace_tt_slow_count_partial", "real_capture_time", "timestamp");
        addColumnIfNotExists("trace_tn_slow_count_partial", "real_capture_time", "timestamp");
        addColumnIfNotExists("trace_tt_slow_point_partial", "real_capture_time", "timestamp");
        addColumnIfNotExists("trace_tn_slow_point_partial", "real_capture_time", "timestamp");
    }

    private void splitActiveAgentRollupTables(int rollupLevel) throws Exception {
        logger.info("populating active_top_level_rollup_{} and active_child_rollup_{} tables - this"
                + " could take several minutes on large data sets...", rollupLevel);
        dropTableIfExists("active_top_level_rollup_" + rollupLevel);
        dropTableIfExists("active_child_rollup_" + rollupLevel);
        Integer expirationHours =
                getCentralStorageConfig(session).rollupExpirationHours().get(rollupLevel);
        session.createTableWithTWCS("create table if not exists active_top_level_rollup_"
                + rollupLevel + " (one int, capture_time timestamp, top_level_id varchar, primary"
                + " key (one, capture_time, top_level_id))", expirationHours);
        session.createTableWithTWCS("create table if not exists active_child_rollup_" + rollupLevel
                + " (top_level_id varchar, capture_time timestamp, child_agent_id varchar, primary"
                + " key (top_level_id, capture_time, child_agent_id))", expirationHours);

        PreparedStatement insertTopLevelPS = session.prepare("insert into active_top_level_rollup_"
                + rollupLevel + " (one, capture_time, top_level_id) values (1, ?, ?) using ttl ?");
        PreparedStatement insertChildPS = session.prepare("insert into active_child_rollup_"
                + rollupLevel + " (top_level_id, capture_time, child_agent_id) values (?, ?, ?)"
                + " using ttl ?");

        int ttl = Ints.saturatedCast(HOURS.toSeconds(expirationHours));
        PreparedStatement readPS = session.prepare("select capture_time, agent_id from"
                + " active_agent_rollup_" + rollupLevel + " where one = 1 and capture_time > ?");
        BoundStatement boundStatement = readPS.bind();
        boundStatement.setTimestamp(0,
                new Date(clock.currentTimeMillis() - HOURS.toMillis(expirationHours)));
        ResultSet results = session.read(boundStatement);
        Queue<ListenableFuture<?>> futures = new ArrayDeque<>();
        for (Row row : results) {
            Date captureDate = checkNotNull(row.getTimestamp(0));
            String agentId = checkNotNull(row.getString(1));
            int index = agentId.indexOf("::");
            String topLevelId;
            String childAgentId;
            if (index == -1) {
                topLevelId = agentId;
                childAgentId = null;
            } else {
                topLevelId = agentId.substring(0, index + 2);
                childAgentId = agentId.substring(index + 2);
            }

            int adjustedTTL = Common.getAdjustedTTL(ttl, captureDate.getTime(), clock);
            boundStatement = insertTopLevelPS.bind();
            boundStatement.setTimestamp(0, captureDate);
            boundStatement.setString(1, topLevelId);
            boundStatement.setInt(2, adjustedTTL);
            futures.add(session.writeAsync(boundStatement));
            if (childAgentId != null) {
                boundStatement = insertChildPS.bind();
                boundStatement.setString(0, topLevelId);
                boundStatement.setTimestamp(1, captureDate);
                boundStatement.setString(2, childAgentId);
                boundStatement.setInt(3, adjustedTTL);
                futures.add(session.writeAsync(boundStatement));
            }
            waitForSome(futures);
        }
        MoreFutures.waitForAll(futures);
        dropTableIfExists("active_agent_rollup_" + rollupLevel);
        logger.info("populating active_top_level_rollup_{} and active_child_rollup_{} tables"
                + " - complete", rollupLevel);
    }

    private void addAggregateSummaryColumns() throws Exception {
        addColumnIfNotExists("aggregate_tt_summary_rollup_0", "total_cpu_nanos", "double");
        addColumnIfNotExists("aggregate_tt_summary_rollup_1", "total_cpu_nanos", "double");
        addColumnIfNotExists("aggregate_tt_summary_rollup_2", "total_cpu_nanos", "double");
        addColumnIfNotExists("aggregate_tt_summary_rollup_3", "total_cpu_nanos", "double");
        addColumnIfNotExists("aggregate_tn_summary_rollup_0", "total_cpu_nanos", "double");
        addColumnIfNotExists("aggregate_tn_summary_rollup_1", "total_cpu_nanos", "double");
        addColumnIfNotExists("aggregate_tn_summary_rollup_2", "total_cpu_nanos", "double");
        addColumnIfNotExists("aggregate_tn_summary_rollup_3", "total_cpu_nanos", "double");

        addColumnIfNotExists("aggregate_tt_summary_rollup_0", "total_allocated_bytes", "double");
        addColumnIfNotExists("aggregate_tt_summary_rollup_1", "total_allocated_bytes", "double");
        addColumnIfNotExists("aggregate_tt_summary_rollup_2", "total_allocated_bytes", "double");
        addColumnIfNotExists("aggregate_tt_summary_rollup_3", "total_allocated_bytes", "double");
        addColumnIfNotExists("aggregate_tn_summary_rollup_0", "total_allocated_bytes", "double");
        addColumnIfNotExists("aggregate_tn_summary_rollup_1", "total_allocated_bytes", "double");
        addColumnIfNotExists("aggregate_tn_summary_rollup_2", "total_allocated_bytes", "double");
        addColumnIfNotExists("aggregate_tn_summary_rollup_3", "total_allocated_bytes", "double");
    }

    private void addColumnIfNotExists(String tableName, String columnName, String cqlType)
            throws Exception {
        try {
            if (tableExists(tableName) && !columnExists(tableName, columnName)) {
                session.updateSchemaWithRetry(
                        "alter table " + tableName + " add " + columnName + " " + cqlType);
            }
        } catch (InvalidQueryException e) {
            // since there is not a real "if not exists" variant, if updateSchemaWithRetry times
            // out, then it will retry and fail (eventually) with "InvalidQueryException: Invalid
            // column name .. because it conflicts with an existing column"
            if (!columnExists(tableName, columnName)) {
                throw e;
            }
        }
    }

    private void dropColumnIfExists(String tableName, String columnName) throws Exception {
        try {
            if (columnExists(tableName, columnName)) {
                session.updateSchemaWithRetry("alter table " + tableName + " drop " + columnName);
            }
        } catch (InvalidQueryException e) {
            // since there is not a real "if exists" variant, if updateSchemaWithRetry times out,
            // then it will retry and fail (eventually) with "InvalidQueryException: Column .. was
            // not found in table"
            if (columnExists(tableName, columnName)) {
                throw e;
            }
        }
    }

    private boolean tableExists(String tableName) {
        return session.getTable(tableName) != null;
    }

    private boolean columnExists(String tableName, String columnName) {
        TableMetadata tableMetadata = session.getTable(tableName);
        return tableMetadata != null && tableMetadata.getColumn(columnName) != null;
    }

    // drop table can timeout, throwing NoHostAvailableException
    // (see https://github.com/glowroot/glowroot/issues/125)
    private void dropTableIfExists(String tableName) throws InterruptedException {
        session.updateSchemaWithRetry("drop table if exists " + tableName);
    }

    // this is needed to prevent OOM due to ever expanding list of futures (and the result sets that
    // they retain)
    private static void waitForSome(Queue<ListenableFuture<?>> futures) throws Exception {
        while (futures.size() > 1000) {
            futures.remove().get();
        }
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

    @VisibleForTesting
    static @Nullable Set<String> upgradePermissions(Set<String> permissions) {
        Set<String> updatedPermissions = new HashSet<>();
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
        for (Map.Entry<String, List<String>> entry : Multimaps.asMap(agentPermissions).entrySet()) {
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
        Set<String> permissionsToBeAdded = new HashSet<>();
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
                    + " is a PEM encoded X.509\n");
            sb.append("# certificate chain, and ui-key.pem is a PEM encoded PKCS#8 private key"
                    + " without a passphrase (for\n");
            sb.append("# example, a self signed certificate can be generated at the command line"
                    + " meeting the above\n");
            sb.append("# requirements using OpenSSL 1.0.0 or later:\n");
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
        try (Writer out = Files.newBufferedWriter(propFile.toPath(), UTF_8, CREATE, APPEND)) {
            out.write(sb.toString());
        }
        return true;
    }

    private static CentralStorageConfig getCentralStorageConfig(Session session) throws Exception {
        ResultSet results =
                session.read("select value from central_config where key = 'storage'");
        Row row = results.one();
        if (row == null) {
            return ImmutableCentralStorageConfig.builder().build();
        }
        String storageConfigText = row.getString(0);
        if (storageConfigText == null) {
            return ImmutableCentralStorageConfig.builder().build();
        }
        try {
            ObjectNode node = (ObjectNode) mapper.readTree(storageConfigText);
            // fullQueryTextExpirationHours is removed from CentralStorageConfig in upgrade to
            // 0.10.3, but this method can be called before that, e.g. when upgrading from 0.9.28 to
            // 0.10.0 as part of bigger upgrade to post-0.10.3
            node.remove("fullQueryTextExpirationHours");
            return mapper.readValue(mapper.treeAsTokens(node), ImmutableCentralStorageConfig.class);
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

    private static void copyString(Row row, BoundStatement boundStatement, int i) {
        boundStatement.setString(i, row.getString(i));
    }

    private static void copyLong(Row row, BoundStatement boundStatement, int i) {
        boundStatement.setLong(i, row.getLong(i));
    }

    private static void copyBool(Row row, BoundStatement boundStatement, int i) {
        boundStatement.setBool(i, row.getBool(i));
    }

    private static void copyTimestamp(Row row, BoundStatement boundStatement, int i) {
        boundStatement.setTimestamp(i, row.getTimestamp(i));
    }

    private static void copyBytes(Row row, BoundStatement boundStatement, int i) {
        boundStatement.setBytes(i, row.getBytes(i));
    }

    private static @Nullable Integer getSchemaVersion(Session session) throws Exception {
        ResultSet results =
                session.read("select schema_version from schema_version where one = 1");
        Row row = results.one();
        if (row != null) {
            return row.getInt(0);
        }
        TableMetadata agentTable = session.getTable("agent");
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
    @Styles.AllParameters
    interface AgentRollupIdSyntheticMonitorIdPair {
        String agentRollupId();
        String syntheticMonitorId();
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
