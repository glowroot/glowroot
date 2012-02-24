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
package org.informantproject.configuration;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;

import org.informantproject.util.DataSource;
import org.informantproject.util.DataSource.Column;
import org.informantproject.util.DataSource.ResultSetExtractor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.collect.ImmutableList;
import com.google.inject.Inject;
import com.google.inject.Singleton;

/**
 * Data access object for storing and reading configuration data from the embedded H2 database.
 * 
 * @author Trask Stalnaker
 * @since 0.5
 */
@Singleton
class ConfigurationDao {

    private static final Logger logger = LoggerFactory.getLogger(ConfigurationDao.class);

    private static final String CORE = "core";
    private static final String PLUGIN = "plugin";

    private static ImmutableList<Column> columns = ImmutableList.of(
            new Column("ID", Types.VARCHAR),
            new Column("CONFIGURATION", Types.VARCHAR));

    private final DataSource dataSource;

    private final boolean valid;

    @Inject
    ConfigurationDao(DataSource dataSource) {
        this.dataSource = dataSource;
        boolean localValid;
        try {
            if (!dataSource.tableExists("configuration")) {
                dataSource.createTable("configuration", columns);
            }
            localValid = true;
        } catch (SQLException e) {
            logger.error(e.getMessage(), e);
            localValid = false;
        }
        valid = localValid;
    }

    synchronized ImmutableCoreConfiguration readCoreConfiguration() {
        if (!valid) {
            return null;
        }
        String json = readConfigurationJson(CORE);
        if (json == null) {
            return null;
        } else {
            return ImmutableCoreConfiguration.fromJson(json);
        }
    }

    synchronized ImmutablePluginConfiguration readPluginConfiguration() {
        if (!valid) {
            return null;
        }
        String json = readConfigurationJson(PLUGIN);
        if (json == null) {
            return null;
        } else {
            return ImmutablePluginConfiguration.fromJson(json);
        }
    }

    synchronized void storeCoreConfiguration(ImmutableCoreConfiguration configuration) {
        logger.debug("storeCoreConfiguration(): configuration={}", configuration);
        if (!valid) {
            return;
        }
        try {
            storeConfiguration(CORE, configuration.toJson());
        } catch (SQLException e) {
            logger.error(e.getMessage(), e);
        }
    }

    synchronized void storePluginConfiguration(ImmutablePluginConfiguration configuration) {
        logger.debug("storePluginConfiguration(): configuration={}", configuration);
        if (!valid) {
            return;
        }
        try {
            storeConfiguration(PLUGIN, configuration.toJson());
        } catch (SQLException e) {
            logger.error(e.getMessage(), e);
        }
    }

    private String readConfigurationJson(final String id) {
        try {
            return dataSource.query("select configuration from configuration where id = ?",
                    new Object[] { id }, new ResultSetExtractor<String>() {
                        public String extractData(ResultSet resultSet) throws SQLException {
                            if (resultSet.next()) {
                                String json = resultSet.getString(1);
                                if (resultSet.next()) {
                                    logger.error("more than one configuration record for id '" + id
                                            + "'", new IllegalStateException());
                                }
                                return json;
                            } else {
                                // no results
                                return null;
                            }
                        }
                    });
        } catch (SQLException e) {
            logger.error(e.getMessage(), e);
            return null;
        }
    }

    private void storeConfiguration(String id, String configurationJson) throws SQLException {
        logger.debug("storeCoreConfiguration(): id={}, configurationJson={}", id,
                configurationJson);
        if (exists(id)) {
            update(id, configurationJson);
        } else {
            insert(id, configurationJson);
        }
    }

    private boolean exists(String id) throws SQLException {
        return dataSource.query("select 1 from configuration where id = ?",
                new Object[] { id }, new ResultSetExtractor<Boolean>() {
                    public Boolean extractData(ResultSet resultSet) throws SQLException {
                        return resultSet.next();
                    }
                });
    }

    private void update(String id, String configurationJson) throws SQLException {
        int rowCount = dataSource.update("update configuration set configuration = ? where id = ?",
                new Object[] { configurationJson, id });
        if (rowCount != 1) {
            logger.error("unexpected update row count '" + rowCount + "'",
                    new IllegalStateException());
        }
    }

    private void insert(String id, String configurationJson) throws SQLException {
        int rowCount = dataSource.update("insert into configuration (id, configuration) values"
                + " (?, ?)", new Object[] { id, configurationJson });
        if (rowCount != 1) {
            logger.error("unexpected insert row count '" + rowCount + "'",
                    new IllegalStateException());
        }
    }
}
