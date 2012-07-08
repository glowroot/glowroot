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
package org.informantproject.core.configuration;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;

import javax.annotation.Nullable;

import org.informantproject.core.util.DataSource;
import org.informantproject.core.util.DataSource.Column;
import org.informantproject.core.util.DataSource.PrimaryKeyColumn;
import org.informantproject.core.util.DataSource.ResultSetExtractor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.collect.ImmutableList;
import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonSyntaxException;
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

    private static ImmutableList<Column> columns = ImmutableList.of(
            new PrimaryKeyColumn("ID", Types.VARCHAR),
            new Column("ENABLED", Types.VARCHAR),
            new Column("PROPERTIES", Types.VARCHAR));

    private final DataSource dataSource;

    private final boolean valid;

    @Inject
    ConfigurationDao(DataSource dataSource) {
        this.dataSource = dataSource;
        boolean localValid;
        try {
            if (!dataSource.tableExists("configuration")) {
                dataSource.createTable("configuration", columns);
            } else if (dataSource.tableNeedsUpgrade("configuration", columns)) {
                logger.warn("upgrading configuration table schema, which unfortunately at this"
                        + " point just means dropping and re-create the table (losing existing"
                        + " data)");
                dataSource.execute("drop table configuration");
                dataSource.createTable("configuration", columns);
                logger.warn("the schema for the configuration table was outdated so it was dropped"
                        + " and re-created, existing configuration data was lost");
            }
            localValid = true;
        } catch (SQLException e) {
            logger.error(e.getMessage(), e);
            localValid = false;
        }
        valid = localValid;
    }

    @Nullable
    ImmutableCoreConfiguration readCoreConfiguration() {
        logger.debug("readCoreConfiguration()");
        if (!valid) {
            return null;
        }
        try {
            return dataSource.query("select enabled, properties from configuration where id = ?",
                    new Object[] { CORE },
                    new ResultSetExtractor<ImmutableCoreConfiguration>() {
                        @Nullable
                        public ImmutableCoreConfiguration extractData(ResultSet resultSet)
                                throws SQLException {
                            if (resultSet.next()) {
                                Object enabledObject = resultSet.getObject(1);
                                // default value for enabled is true
                                boolean enabled = true;
                                if (enabledObject != null) {
                                    enabled = resultSet.getBoolean(1);
                                }
                                String json = resultSet.getString(2);
                                return ImmutableCoreConfiguration.create(enabled, json);
                            } else {
                                return null;
                            }
                        }
                    });
        } catch (SQLException e) {
            logger.error(e.getMessage(), e);
            return null;
        }
    }

    @Nullable
    ImmutablePluginConfiguration readPluginConfiguration(final PluginDescriptor pluginDescriptor) {
        logger.debug("readPluginConfiguration(): pluginDescriptor.id={}", pluginDescriptor.getId());
        if (!valid) {
            return null;
        }
        try {
            return dataSource.query("select enabled, properties from configuration where id = ?",
                    new Object[] { pluginDescriptor.getId() },
                    new ResultSetExtractor<ImmutablePluginConfiguration>() {
                        @Nullable
                        public ImmutablePluginConfiguration extractData(ResultSet resultSet)
                                throws SQLException {
                            if (resultSet.next()) {
                                return buildPluginConfiguration(pluginDescriptor, resultSet);
                            } else {
                                // no existing plugin configuration record
                                return null;
                            }
                        }
                    });
        } catch (SQLException e) {
            logger.error(e.getMessage(), e);
            return null;
        }
    }

    void setCoreEnabled(boolean enabled) {
        logger.debug("setCoreEnabled(): enabled={}", enabled);
        try {
            dataSource.update("merge into configuration (id, enabled) values (?, ?)", new Object[] {
                    CORE, enabled });
        } catch (SQLException e) {
            logger.error(e.getMessage(), e);
        }
    }

    void setPluginEnabled(String pluginId, boolean enabled) {
        logger.debug("setPluginEnabled(): pluginId={}, enabled={}", pluginId, enabled);
        try {
            dataSource.update("merge into configuration (id, enabled) values (?, ?)", new Object[] {
                    pluginId, enabled });
        } catch (SQLException e) {
            logger.error(e.getMessage(), e);
        }
    }

    void storeCoreProperties(String propertiesJson) {
        logger.debug("storeCoreProperties(): propertiesJson={}", propertiesJson);
        if (!valid) {
            return;
        }
        try {
            dataSource.update("merge into configuration (id, properties) values (?, ?)",
                    new Object[] { CORE, propertiesJson });
        } catch (SQLException e) {
            logger.error(e.getMessage(), e);
        }
    }

    void storePluginProperties(String pluginId, String propertiesJson) {
        logger.debug("storePluginProperties(): pluginId={}, propertiesJson={}", pluginId,
                propertiesJson);
        if (!valid) {
            return;
        }
        try {
            dataSource.update("merge into configuration (id, properties) values (?, ?)",
                    new Object[] { pluginId, propertiesJson });
        } catch (SQLException e) {
            logger.error(e.getMessage(), e);
        }
    }

    private static ImmutablePluginConfiguration buildPluginConfiguration(
            PluginDescriptor pluginDescriptor, ResultSet resultSet) throws SQLException {

        Object enabledObject = resultSet.getObject(1);
        // default value for enabled is true
        boolean enabled = true;
        if (enabledObject != null) {
            enabled = resultSet.getBoolean(1);
        }
        String json = resultSet.getString(2);
        if (json == null) {
            return ImmutablePluginConfiguration.create(pluginDescriptor, enabled);
        }
        JsonElement propertiesElement;
        try {
            propertiesElement = new Gson().fromJson(json, JsonElement.class);
        } catch (JsonSyntaxException e) {
            logger.error(e.getMessage(), e);
            return ImmutablePluginConfiguration.create(pluginDescriptor, enabled);
        }
        if (propertiesElement.isJsonObject()) {
            return ImmutablePluginConfiguration.create(pluginDescriptor, enabled, propertiesElement
                    .getAsJsonObject());
        } else {
            logger.error("configuration for plugin id '{}' is not json object", pluginDescriptor
                    .getId());
            return ImmutablePluginConfiguration.create(pluginDescriptor, enabled);
        }
    }
}
