/*
 * Copyright 2017-2018 the original author or authors.
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
package org.glowroot.agent.embedded.repo;

import java.sql.SQLException;

import javax.annotation.Nullable;

import com.google.common.collect.ImmutableList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.glowroot.agent.embedded.util.DataSource;
import org.glowroot.agent.embedded.util.ImmutableColumn;
import org.glowroot.agent.embedded.util.Schemas.Column;
import org.glowroot.agent.embedded.util.Schemas.ColumnType;

import static com.google.common.base.Preconditions.checkNotNull;
import static java.util.concurrent.TimeUnit.DAYS;
import static org.glowroot.agent.util.Checkers.castUntainted;

class SchemaUpgrade {

    // log startup messages using logger name "org.glowroot"
    private static final Logger startupLogger = LoggerFactory.getLogger("org.glowroot");

    private static final int CURR_SCHEMA_VERSION = 5;

    private static final ImmutableList<Column> columns =
            ImmutableList.<Column>of(ImmutableColumn.of("schema_version", ColumnType.BIGINT));

    private final DataSource dataSource;
    private final @Nullable Integer initialSchemaVersion;

    SchemaUpgrade(DataSource dataSource) throws SQLException {
        this.dataSource = dataSource;

        dataSource.syncTable("schema_version", columns);
        initialSchemaVersion = getSchemaVersion(dataSource);
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
            startupLogger.warn("running an older version of glowroot on a newer glowroot schema"
                    + " (expecting glowroot schema version <= {} but found version {}), this could"
                    + " be problematic", CURR_SCHEMA_VERSION, initialSchemaVersion);
            return;
        }
        startupLogger.info("upgrading glowroot schema from version {} to version {} ...",
                initialSchemaVersion, CURR_SCHEMA_VERSION);
        // 0.9.28 to 0.10.0
        if (initialSchemaVersion < 3) {
            renameOldGaugeNameTable();
            updateSchemaVersion(3);
        }
        if (initialSchemaVersion < 4) {
            populateNewGaugeNameTable();
            updateSchemaVersion(4);
        }
        if (initialSchemaVersion == 4) {
            // only applies when upgrading from immediately prior schema version (4)
            // (fix bad upgrade that populated gauge_name table based on gauge_value_rollup_3
            // instead of gauge_value_rollup_4)
            populateNewGaugeNameTable();
            updateSchemaVersion(5);
        } else {
            updateSchemaVersion(5);
        }

        // when adding new schema upgrade, make sure to update CURR_SCHEMA_VERSION above
        startupLogger.info("upgraded glowroot schema from version {} to version {}",
                initialSchemaVersion, CURR_SCHEMA_VERSION);
    }

    public void updateSchemaVersionToCurent() throws Exception {
        updateSchemaVersion(CURR_SCHEMA_VERSION);
    }

    private void updateSchemaVersion(int schemaVersion) throws Exception {
        int updated =
                dataSource.update("update schema_version set schema_version = ?", schemaVersion);
        if (updated == 0) {
            dataSource.update("insert into schema_version (schema_version) values (?)",
                    schemaVersion);
        }
    }

    private void renameOldGaugeNameTable() throws SQLException {
        dataSource.renameTable("gauge_name", "gauge_id");
        dataSource.renameColumn("gauge_id", "id", "gauge_id");
    }

    private void populateNewGaugeNameTable() throws SQLException {
        startupLogger.info("populating new gauge name history table - this could take a few seconds"
                + " on large data sets ...");
        dataSource.syncTable("gauge_name", GaugeNameDao.columns);
        dataSource.syncIndexes("gauge_name", GaugeNameDao.indexes);
        // truncate in case previously failed prior to updating schema version
        dataSource.execute("truncate table gauge_name");
        long fixedIntervalMillis = DAYS.toMillis(1);
        // need ".0" to force double result
        String captureTimeSql = castUntainted(
                "ceil(capture_time / " + fixedIntervalMillis + ".0) * " + fixedIntervalMillis);
        dataSource.update("insert into gauge_name (capture_time, gauge_name) select distinct "
                + captureTimeSql + ", gauge_name from gauge_value_rollup_4, gauge_id where"
                + " gauge_value_rollup_4.gauge_id = gauge_id.gauge_id");
        startupLogger.info("populating new gauge name history table - complete");
    }

    private static @Nullable Integer getSchemaVersion(DataSource dataSource) throws SQLException {
        Long schemaVersion =
                dataSource.queryForOptionalLong("select schema_version from schema_version");
        if (schemaVersion != null) {
            return schemaVersion.intValue();
        }
        if (dataSource.tableExists("trace")) {
            // this is glowroot prior to when the schema_version table was introduced in 0.9.18
            return 1;
        }
        // new installation
        return null;
    }
}
