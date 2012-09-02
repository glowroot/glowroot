/**
 * Copyright 2011-2012 the original author or authors.
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
package org.informantproject.core.config;

import java.sql.SQLException;
import java.sql.Types;

import javax.annotation.Nullable;

import org.informantproject.core.util.DataSource;
import org.informantproject.core.util.DataSource.Column;
import org.informantproject.core.util.DataSource.PrimaryKeyColumn;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.collect.ImmutableList;
import com.google.inject.Inject;
import com.google.inject.Singleton;

/**
 * Data access object for storing and reading config data from the embedded H2 database.
 * 
 * @author Trask Stalnaker
 * @since 0.5
 */
@Singleton
class ConfigDao {

    private static final Logger logger = LoggerFactory.getLogger(ConfigDao.class);

    private static final String CORE = "core";
    private static final String PROFILING_COARSE = "profiling-coarse";
    private static final String PROFILING_FINE = "profiling-fine";

    private static final ImmutableList<Column> columns = ImmutableList.of(
            new PrimaryKeyColumn("ID", Types.VARCHAR),
            new Column("CONFIG", Types.VARCHAR));

    private final DataSource dataSource;
    private final boolean valid;
    private final Object writeLock = new Object();

    @Inject
    ConfigDao(DataSource dataSource) {
        this.dataSource = dataSource;
        boolean localValid;
        try {
            if (!dataSource.tableExists("config")) {
                dataSource.createTable("config", columns);
            } else if (dataSource.tableNeedsUpgrade("config", columns)) {
                logger.warn("upgrading config table schema, which unfortunately at this point just"
                        + " means dropping and re-create the table (losing existing data)");
                dataSource.execute("drop table config");
                dataSource.createTable("config", columns);
                logger.warn("the schema for the config table was outdated so it was dropped and"
                        + " re-created, existing config data was lost");
            }
            localValid = true;
        } catch (SQLException e) {
            logger.error(e.getMessage(), e);
            localValid = false;
        }
        valid = localValid;
    }

    Object getWriteLock() {
        return writeLock;
    }

    @Nullable
    CoreConfig readCoreConfig() {
        logger.debug("readCoreConfig()");
        String configJson = readConfig(CORE);
        if (configJson == null) {
            return null;
        } else {
            return CoreConfig.fromJson(configJson);
        }
    }

    @Nullable
    CoarseProfilingConfig readCoarseProfilingConfig() {
        logger.debug("readCoarseProfilingConfig()");
        String configJson = readConfig(PROFILING_COARSE);
        if (configJson == null) {
            return null;
        } else {
            return CoarseProfilingConfig.fromJson(configJson);
        }
    }

    @Nullable
    FineProfilingConfig readFineProfilingConfig() {
        logger.debug("readFineProfilingConfig()");
        String configJson = readConfig(PROFILING_FINE);
        if (configJson == null) {
            return null;
        } else {
            return FineProfilingConfig.fromJson(configJson);
        }
    }

    @Nullable
    PluginConfig readPluginConfig(String pluginId) {
        logger.debug("readPluginConfig(): pluginId={}", pluginId);
        String configJson = readConfig(pluginId);
        if (configJson == null) {
            return null;
        } else {
            return PluginConfig.fromJson(pluginId, configJson);
        }
    }

    void storeCoreConfig(CoreConfig config) {
        logger.debug("storeCoreConfig(): config={}", config);
        storeConfig(CORE, config.toJson());
    }

    void storeCoarseProfilingConfig(CoarseProfilingConfig config) {
        logger.debug("storeCoarseProfilingConfig(): config={}", config);
        storeConfig(PROFILING_COARSE, config.toJson());
    }

    void storeFineProfilingConfig(FineProfilingConfig config) {
        logger.debug("storeFineProfilingConfig(): config={}", config);
        storeConfig(PROFILING_FINE, config.toJson());
    }

    void storePluginConfig(String pluginId, PluginConfig config) {
        logger.debug("storePluginConfig(): pluginId={}, config={}", pluginId, config);
        storeConfig(pluginId, config.toJson());
    }

    @Nullable
    private String readConfig(String id) {
        if (!valid) {
            return null;
        }
        try {
            return dataSource.queryForString("select config from config where id = ?",
                    new Object[] { id });
        } catch (SQLException e) {
            logger.error(e.getMessage(), e);
            return null;
        }
    }

    private void storeConfig(String id, String config) {
        if (!valid) {
            return;
        }
        try {
            dataSource.update("merge into config (id, config) values (?, ?)", new Object[] {
                    id, config });
        } catch (SQLException e) {
            logger.error(e.getMessage(), e);
        }
    }
}
