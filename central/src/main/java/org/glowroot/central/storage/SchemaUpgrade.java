/*
 * Copyright 2016 the original author or authors.
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
package org.glowroot.central.storage;

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
import com.datastax.driver.core.exceptions.InvalidQueryException;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ArrayListMultimap;
import com.google.common.collect.ListMultimap;
import com.google.common.collect.Multimaps;
import com.google.common.collect.Sets;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.glowroot.common.config.PermissionParser;

import static com.google.common.base.Preconditions.checkNotNull;

public class SchemaUpgrade {

    private static final Logger logger = LoggerFactory.getLogger(SchemaUpgrade.class);

    private static final int CURR_SCHEMA_VERSION = 9;

    private static final String WITH_LCS =
            "with compaction = { 'class' : 'LeveledCompactionStrategy' }";

    private final Session session;

    private final PreparedStatement insertPS;
    private final @Nullable Integer initialSchemaVersion;

    public SchemaUpgrade(Session session, KeyspaceMetadata keyspace) {
        this.session = session;

        session.execute("create table if not exists schema_version (one int, schema_version int,"
                + " primary key (one)) " + WITH_LCS);
        insertPS =
                session.prepare("insert into schema_version (one, schema_version) values (?, ?)");
        initialSchemaVersion = getSchemaVersion(session, keyspace);
    }

    public @Nullable Integer getInitialSchemaVersion() {
        return initialSchemaVersion;
    }

    public void upgrade() {
        checkNotNull(initialSchemaVersion);
        // 0.9.1 to 0.9.2
        if (initialSchemaVersion < 2) {
            renameAgentColumnFromSystemInfoToEnvironment();
            updateSchemaVersion(2);
        }
        if (initialSchemaVersion < 3) {
            upgradeRoles();
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
        // when adding new schema upgrade, make sure to update CURR_SCHEMA_VERSION above
    }

    public void updateSchemaVersionToCurent() {
        updateSchemaVersion(CURR_SCHEMA_VERSION);
    }

    private void updateSchemaVersion(int schemaVersion) {
        BoundStatement boundStatement = insertPS.bind();
        boundStatement.setInt(0, 1);
        boundStatement.setInt(1, schemaVersion);
        session.execute(boundStatement);
    }

    private void renameAgentColumnFromSystemInfoToEnvironment() {
        ResultSet results;
        try {
            results = session.execute("select agent_id, system_info from agent");
        } catch (InvalidQueryException e) {
            // system_info column does not exist, rename already completed
            logger.debug(e.getMessage(), e);
            return;
        }
        try {
            session.execute("alter table agent add environment blob");
        } catch (InvalidQueryException e) {
            // previously failed mid-upgrade
            logger.debug(e.getMessage(), e);
        }
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

    private void upgradeRoles() {
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
        try {
            session.execute("alter table agent add config_update boolean");
        } catch (InvalidQueryException e) {
            logger.debug(e.getMessage(), e);
        }
        try {
            session.execute("alter table agent add config_update_token uuid");
        } catch (InvalidQueryException e) {
            logger.debug(e.getMessage(), e);
        }
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
        try {
            session.execute("alter table trace_entry add shared_query_text_index int");
        } catch (InvalidQueryException e) {
            logger.debug(e.getMessage(), e);
        }
        try {
            session.execute("alter table trace_entry add query_message_prefix varchar");
        } catch (InvalidQueryException e) {
            logger.debug(e.getMessage(), e);
        }
        try {
            session.execute("alter table trace_entry add query_message_suffix varchar");
        } catch (InvalidQueryException e) {
            logger.debug(e.getMessage(), e);
        }
    }

    private void renameServerConfigTable() {
        try {
            session.execute("create table if not exists central_config (key varchar, value varchar,"
                    + " primary key (key)) " + WITH_LCS);
        } catch (InvalidQueryException e) {
            logger.debug(e.getMessage(), e);
        }
        ResultSet results = session.execute("select key, value from server_config");
        PreparedStatement insertPS =
                session.prepare("insert into central_config (key, value) values (?, ?)");
        for (Row row : results) {
            BoundStatement boundStatement = insertPS.bind();
            boundStatement.setString(0, row.getString(0));
            boundStatement.setString(1, row.getString(1));
            session.execute(boundStatement);
        }
        try {
            session.execute("drop table server_config");
        } catch (InvalidQueryException e) {
            logger.debug(e.getMessage(), e);
        }
    }

    private void addAgentOneTable() {
        try {
            session.execute("create table if not exists agent_one (one int, agent_id varchar,"
                    + " agent_rollup varchar, primary key (one, agent_id)) " + WITH_LCS);
        } catch (InvalidQueryException e) {
            logger.debug(e.getMessage(), e);
        }
        ResultSet results = session.execute("select agent_rollup from agent_rollup");
        PreparedStatement insertPS =
                session.prepare("insert into agent_one (one, agent_id) values (1, ?)");
        for (Row row : results) {
            BoundStatement boundStatement = insertPS.bind();
            boundStatement.setString(0, row.getString(0));
            session.execute(boundStatement);
        }
        try {
            session.execute("drop table agent_rollup");
        } catch (InvalidQueryException e) {
            logger.debug(e.getMessage(), e);
        }
    }

    private void addAgentRollupColumn() {
        try {
            session.execute("alter table agent add agent_rollup varchar");
        } catch (InvalidQueryException e) {
            logger.debug(e.getMessage(), e);
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
                agentPermissions.put(PermissionParser.quoteIfNecessaryAndJoin(parser.getAgentIds()),
                        perm);
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
