/**
 * Copyright 2011 the original author or authors.
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

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import org.informantproject.util.JdbcHelper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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

    private final Connection connection;

    private final PreparedStatement insertPreparedStatement;
    private final PreparedStatement selectPreparedStatement;
    private final PreparedStatement existsPreparedStatement;
    private final PreparedStatement updatePreparedStatement;

    private final boolean valid;

    @Inject
    ConfigurationDao(Connection connection, JdbcHelper jdbcHelper) {

        this.connection = connection;
        PreparedStatement localInsertPreparedStatement = null;
        PreparedStatement localSelectPreparedStatement = null;
        PreparedStatement localExistsPreparedStatement = null;
        PreparedStatement localUpdatePreparedStatement = null;

        boolean localValid;

        try {
            if (!jdbcHelper.tableExists("configuration")) {
                // create table
                Statement statement = connection.createStatement();
                statement.execute("create table configuration (id varchar, configuration varchar)");
                statement.close();
            }

            localInsertPreparedStatement = connection.prepareStatement(
                    "insert into configuration (id, configuration) values (?, ?)");
            localSelectPreparedStatement = connection.prepareStatement(
                    "select configuration from configuration where id = ?");
            localExistsPreparedStatement = connection.prepareStatement(
                    "select 1 from configuration where id = ?");
            localUpdatePreparedStatement = connection.prepareStatement(
                    "update configuration set configuration = ? where id = ?");

            localValid = true;

        } catch (SQLException e) {
            logger.error(e.getMessage(), e);
            localValid = false;
        }

        insertPreparedStatement = localInsertPreparedStatement;
        selectPreparedStatement = localSelectPreparedStatement;
        existsPreparedStatement = localExistsPreparedStatement;
        updatePreparedStatement = localUpdatePreparedStatement;

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

    private String readConfigurationJson(String id) {
        synchronized (connection) {
            ResultSet resultSet = null;
            try {
                selectPreparedStatement.setString(1, id);
                resultSet = selectPreparedStatement.executeQuery();
                if (resultSet.next()) {
                    String json = resultSet.getString(1);
                    if (resultSet.next()) {
                        logger.error("more than one configuration record for id '" + id + "'",
                                new IllegalStateException());
                    }
                    return json;
                } else {
                    // no results
                    return null;
                }
            } catch (SQLException e) {
                logger.error(e.getMessage(), e);
                return null;
            } finally {
                if (resultSet != null) {
                    try {
                        resultSet.close();
                    } catch (SQLException e) {
                        logger.error(e.getMessage(), e);
                    }
                }
            }
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
        synchronized (connection) {
            existsPreparedStatement.setString(1, id);
            ResultSet existsResultSet = existsPreparedStatement.executeQuery();
            try {
                return existsResultSet.next();
            } finally {
                existsResultSet.close();
            }
        }
    }

    private void update(String id, String configurationJson) throws SQLException {
        synchronized (connection) {
            updatePreparedStatement.setString(1, configurationJson);
            updatePreparedStatement.setString(2, id);
            int rowCount = updatePreparedStatement.executeUpdate();
            if (rowCount != 1) {
                logger.error("unexpected update row count '" + rowCount + "'",
                        new IllegalStateException());
            }
        }
    }

    private void insert(String id, String configurationJson) throws SQLException {
        synchronized (connection) {
            insertPreparedStatement.setString(1, id);
            insertPreparedStatement.setString(2, configurationJson);
            int rowCount = insertPreparedStatement.executeUpdate();
            if (rowCount != 1) {
                logger.error("unexpected insert row count '" + rowCount + "'",
                        new IllegalStateException());
            }
        }
    }
}
