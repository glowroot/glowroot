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

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;

import javax.annotation.Nullable;

import org.informantproject.core.util.DataSource;
import org.informantproject.core.util.DataSource.Column;
import org.informantproject.core.util.DataSource.NullableResultSetExtractor;
import org.informantproject.core.util.DataSource.PrimaryKeyColumn;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.base.Objects;
import com.google.common.collect.ImmutableList;
import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonSyntaxException;
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

    private static final ImmutableList<Column> columns = ImmutableList.of(
            new PrimaryKeyColumn("ID", Types.VARCHAR),
            new Column("ENABLED", Types.VARCHAR),
            new Column("PROPERTIES", Types.VARCHAR));

    private final DataSource dataSource;
    private final boolean valid;
    private final Gson gson = new Gson();

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

    @Nullable
    CoreConfig readCoreConfig() {
        logger.debug("readCoreConfig()");
        if (!valid) {
            return null;
        }
        try {
            return dataSource.query("select enabled, properties from config where id = ?",
                    new Object[] { CORE }, new NullableResultSetExtractor<CoreConfig>() {
                        @Nullable
                        public CoreConfig extractData(ResultSet resultSet) throws SQLException {
                            if (resultSet.next()) {
                                // default value for enabled is true (so can't just use
                                // ResultSet.getBoolean() which defaults to false)
                                boolean enabled;
                                if (resultSet.getObject(1) == null) {
                                    enabled = true;
                                } else {
                                    enabled = resultSet.getBoolean(1);
                                }
                                // default value for propertiesJson is {} which doesn't override any
                                // of the default property values
                                String propertiesJson = Objects.firstNonNull(
                                        resultSet.getString(2), "{}");
                                CoreConfig.Builder builder = gson.fromJson(propertiesJson,
                                        CoreConfig.Builder.class);
                                return builder.enabled(enabled).build();
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
    PluginConfig readPluginConfig(final PluginDescriptor pluginDescriptor) {
        logger.debug("readPluginConfig(): pluginDescriptor.id={}", pluginDescriptor.getId());
        if (!valid) {
            return null;
        }
        try {
            return dataSource.query("select enabled, properties from config where id = ?",
                    new Object[] { pluginDescriptor.getId() },
                    new NullableResultSetExtractor<PluginConfig>() {
                        @Nullable
                        public PluginConfig extractData(ResultSet resultSet) throws SQLException {
                            if (resultSet.next()) {
                                return buildPluginConfig(pluginDescriptor, resultSet);
                            } else {
                                // no existing plugin config record
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
            dataSource.update("merge into config (id, enabled) values (?, ?)", new Object[] { CORE,
                    enabled });
        } catch (SQLException e) {
            logger.error(e.getMessage(), e);
        }
    }

    void setPluginEnabled(String pluginId, boolean enabled) {
        logger.debug("setPluginEnabled(): pluginId={}, enabled={}", pluginId, enabled);
        try {
            dataSource.update("merge into config (id, enabled) values (?, ?)", new Object[] {
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
            dataSource.update("merge into config (id, properties) values (?, ?)", new Object[] {
                    CORE, propertiesJson });
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
            dataSource.update("merge into config (id, properties) values (?, ?)", new Object[] {
                    pluginId, propertiesJson });
        } catch (SQLException e) {
            logger.error(e.getMessage(), e);
        }
    }

    private PluginConfig buildPluginConfig(PluginDescriptor pluginDescriptor, ResultSet resultSet)
            throws SQLException {

        Object enabledObject = resultSet.getObject(1);
        // default value for enabled is true
        boolean enabled = true;
        if (enabledObject != null) {
            enabled = resultSet.getBoolean(1);
        }
        String json = resultSet.getString(2);
        if (json == null) {
            return defaultPluginConfig(pluginDescriptor, enabled);
        }
        JsonElement propertiesElement;
        try {
            propertiesElement = gson.fromJson(json, JsonElement.class);
        } catch (JsonSyntaxException e) {
            logger.error(e.getMessage(), e);
            return defaultPluginConfig(pluginDescriptor, enabled);
        }
        if (propertiesElement.isJsonObject()) {
            return PluginConfig.builder(pluginDescriptor)
                    .setEnabled(enabled)
                    .setProperties(propertiesElement.getAsJsonObject())
                    .build();
        } else {
            logger.error("config for plugin id '{}' is not json object", pluginDescriptor.getId());
            return defaultPluginConfig(pluginDescriptor, enabled);
        }
    }

    private PluginConfig defaultPluginConfig(PluginDescriptor pluginDescriptor, boolean enabled) {
        return PluginConfig.builder(pluginDescriptor).setEnabled(enabled).build();
    }
}
