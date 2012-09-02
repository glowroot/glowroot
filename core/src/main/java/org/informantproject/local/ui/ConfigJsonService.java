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
package org.informantproject.local.ui;

import java.io.IOException;
import java.util.List;

import org.informantproject.core.config.CoarseProfilingConfig;
import org.informantproject.core.config.ConfigService;
import org.informantproject.core.config.CoreConfig;
import org.informantproject.core.config.FineProfilingConfig;
import org.informantproject.core.config.PluginConfig;
import org.informantproject.core.config.PluginDescriptor;
import org.informantproject.core.config.Plugins;
import org.informantproject.core.util.RollingFile;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.collect.Iterables;
import com.google.common.collect.Lists;
import com.google.common.io.CharStreams;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.stream.JsonWriter;
import com.google.inject.Inject;
import com.google.inject.Singleton;

/**
 * Json service to read config data.
 * 
 * @author Trask Stalnaker
 * @since 0.5
 */
@Singleton
class ConfigJsonService implements JsonService {

    private static final Logger logger = LoggerFactory.getLogger(ConfigJsonService.class);

    private final ConfigService configService;
    private final RollingFile rollingFile;
    private final Gson gson = new Gson();

    @Inject
    ConfigJsonService(ConfigService configService, RollingFile rollingFile) {
        this.configService = configService;
        this.rollingFile = rollingFile;
    }

    @JsonServiceMethod
    void enableCore() {
        configService.setCoreEnabled(true);
    }

    @JsonServiceMethod
    void disableCore() {
        configService.setCoreEnabled(false);
    }

    @JsonServiceMethod
    void enableCoarseProfiling() {
        configService.setCoarseProfilingEnabled(true);
    }

    @JsonServiceMethod
    void disableCoarseProfiling() {
        configService.setCoarseProfilingEnabled(false);
    }

    @JsonServiceMethod
    void enableFineProfiling() {
        configService.setFineProfilingEnabled(true);
    }

    @JsonServiceMethod
    void disableFineProfiling() {
        configService.setFineProfilingEnabled(false);
    }

    @JsonServiceMethod
    void enablePlugin(String pluginId) {
        configService.setPluginEnabled(pluginId, true);
    }

    @JsonServiceMethod
    void disablePlugin(String pluginId) {
        configService.setPluginEnabled(pluginId, false);
    }

    @JsonServiceMethod
    String getConfig() {
        logger.debug("getConfig()");
        List<PluginDescriptor> pluginDescriptors = Lists.newArrayList(Iterables.concat(
                Plugins.getPackagedPluginDescriptors(), Plugins.getInstalledPluginDescriptors()));
        double rollingSizeMb = rollingFile.getRollingSizeKb() / 1024.0;
        return "{\"coreConfig\":" + configService.getCoreConfig().toJson()
                + ",\"coarseProfilingConfig\":" + configService.getCoarseProfilingConfig().toJson()
                + ",\"fineProfilingConfig\":" + configService.getFineProfilingConfig().toJson()
                + ",\"pluginConfigs\":" + getPluginConfigsJson()
                + ",\"pluginDescriptors\":" + gson.toJson(pluginDescriptors)
                + ",\"actualRollingSizeMb\":" + rollingSizeMb + "}";
    }

    @JsonServiceMethod
    void storeCoreConfig(String configJson) {
        logger.debug("storeCoreConfig(): configJson={}", configJson);
        CoreConfig config = configService.getCoreConfig();
        CoreConfig.Builder builder = CoreConfig.builder(config);
        overlayOntoBuilder(builder, configJson);
        configService.storeCoreConfig(builder.build());
        try {
            // resize() doesn't do anything if the new and old value are the same
            rollingFile.resize(configService.getCoreConfig().getRollingSizeMb() * 1024);
        } catch (IOException e) {
            logger.error(e.getMessage(), e);
            // TODO return HTTP 500 Internal Server Error?
            return;
        }
    }

    @JsonServiceMethod
    void storeCoarseProfilingConfig(String configJson) {
        logger.debug("storeCoarseProfilingConfig(): configJson={}", configJson);
        CoarseProfilingConfig config = configService.getCoarseProfilingConfig();
        CoarseProfilingConfig.Builder builder = CoarseProfilingConfig.builder(config);
        overlayOntoBuilder(builder, configJson);
        configService.storeCoarseProfilingConfig(builder.build());
    }

    @JsonServiceMethod
    void storeFineProfilingConfig(String configJson) {
        logger.debug("storeFineProfilingConfig(): configJson={}", configJson);
        FineProfilingConfig config = configService.getFineProfilingConfig();
        FineProfilingConfig.Builder builder = FineProfilingConfig.builder(config);
        overlayOntoBuilder(builder, configJson);
        configService.storeFineProfilingConfig(builder.build());
    }

    @JsonServiceMethod
    void storePluginConfig(String pluginId, String configJson) {
        logger.debug("storePluginConfig(): pluginId={}, configJson={}", pluginId, configJson);
        PluginConfig config = configService.getPluginConfig(pluginId);
        PluginConfig.Builder builder = PluginConfig.builder(pluginId, config);
        builder.overlay(configJson);
        configService.storePluginConfig(pluginId, builder.build());
    }

    private void overlayOntoBuilder(CoreConfig.Builder builder, String overlayJson) {
        JsonObject jsonObject = new JsonParser().parse(overlayJson).getAsJsonObject();
        if (jsonObject.get("enabled") != null) {
            builder.enabled(jsonObject.get("enabled").getAsBoolean());
        }
        if (jsonObject.get("persistenceThresholdMillis") != null) {
            builder.persistenceThresholdMillis(jsonObject.get("persistenceThresholdMillis")
                    .getAsInt());
        }
        if (jsonObject.get("stuckThresholdSeconds") != null) {
            builder.stuckThresholdSeconds(jsonObject.get("stuckThresholdSeconds").getAsInt());
        }
        if (jsonObject.get("spanStackTraceThresholdMillis") != null) {
            builder.spanStackTraceThresholdMillis(jsonObject.get("spanStackTraceThresholdMillis")
                    .getAsInt());
        }
        if (jsonObject.get("maxEntries") != null) {
            builder.maxEntries(jsonObject.get("maxEntries").getAsInt());
        }
        if (jsonObject.get("rollingSizeMb") != null) {
            builder.rollingSizeMb(jsonObject.get("rollingSizeMb").getAsInt());
        }
        if (jsonObject.get("warnOnEntryOutsideTrace") != null) {
            builder.warnOnEntryOutsideTrace(jsonObject.get("warnOnEntryOutsideTrace")
                    .getAsBoolean());
        }
        if (jsonObject.get("metricPeriodMillis") != null) {
            builder.metricPeriodMillis(jsonObject.get("metricPeriodMillis").getAsInt());
        }
    }

    private void overlayOntoBuilder(CoarseProfilingConfig.Builder builder, String overlayJson) {
        JsonObject jsonObject = new JsonParser().parse(overlayJson).getAsJsonObject();
        if (jsonObject.get("enabled") != null) {
            builder.enabled(jsonObject.get("enabled").getAsBoolean());
        }
        if (jsonObject.get("initialDelayMillis") != null) {
            builder.initialDelayMillis(jsonObject.get("initialDelayMillis").getAsInt());
        }
        if (jsonObject.get("intervalMillis") != null) {
            builder.intervalMillis(jsonObject.get("intervalMillis").getAsInt());
        }
        if (jsonObject.get("totalSeconds") != null) {
            builder.totalSeconds(jsonObject.get("totalSeconds").getAsInt());
        }
    }

    private void overlayOntoBuilder(FineProfilingConfig.Builder builder, String overlayJson) {
        JsonObject jsonObject = new JsonParser().parse(overlayJson).getAsJsonObject();
        if (jsonObject.get("enabled") != null) {
            builder.enabled(jsonObject.get("enabled").getAsBoolean());
        }
        if (jsonObject.get("tracePercentage") != null) {
            builder.tracePercentage(jsonObject.get("tracePercentage").getAsDouble());
        }
        if (jsonObject.get("intervalMillis") != null) {
            builder.intervalMillis(jsonObject.get("intervalMillis").getAsInt());
        }
        if (jsonObject.get("totalSeconds") != null) {
            builder.totalSeconds(jsonObject.get("totalSeconds").getAsInt());
        }
        if (jsonObject.get("persistenceThresholdMillis") != null) {
            builder.persistenceThresholdMillis(jsonObject.get("persistenceThresholdMillis")
                    .getAsInt());
        }
    }

    private String getPluginConfigsJson() {
        StringBuilder sb = new StringBuilder();
        JsonWriter jw = new JsonWriter(CharStreams.asWriter(sb));
        try {
            jw.beginObject();
            Iterable<PluginDescriptor> pluginDescriptors = Iterables.concat(
                    Plugins.getPackagedPluginDescriptors(),
                    Plugins.getInstalledPluginDescriptors());
            for (PluginDescriptor pluginDescriptor : pluginDescriptors) {
                jw.name(pluginDescriptor.getId());
                PluginConfig pluginConfig = configService.getPluginConfig(pluginDescriptor.getId());
                pluginConfig.toJson(jw);
            }
            jw.endObject();
            jw.close();
        } catch (IOException e) {
            logger.error(e.getMessage(), e);
        }
        return sb.toString();
    }
}
