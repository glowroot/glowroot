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

import org.informantproject.core.configuration.ConfigurationService;
import org.informantproject.core.configuration.ImmutableCoreConfiguration.CoreConfigurationBuilder;
import org.informantproject.core.util.RollingFile;
import org.informantproject.local.ui.HttpServer.JsonService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.inject.Inject;
import com.google.inject.Singleton;

/**
 * Json service to read configuration data.
 * 
 * @author Trask Stalnaker
 * @since 0.5
 */
@Singleton
public class ConfigurationJsonService implements JsonService {

    private static final Logger logger = LoggerFactory.getLogger(ConfigurationJsonService.class);

    private final ConfigurationService configurationService;
    private final RollingFile rollingFile;

    @Inject
    public ConfigurationJsonService(ConfigurationService configurationService,
            RollingFile rollingFile) {

        this.configurationService = configurationService;
        this.rollingFile = rollingFile;
    }

    // called dynamically from HttpServer
    public String getConfiguration(String message) {
        logger.debug("handleRead(): message={}", message);
        double rollingSizeMb = rollingFile.getRollingSizeKb() / 1024;
        return "{\"coreConfiguration\":" + configurationService.getCoreConfiguration().toJson()
                + ",\"pluginConfiguration\":"
                + configurationService.getPluginConfiguration().toJson()
                + ",\"actualRollingSizeMb\":" + rollingSizeMb + "}";
    }

    // called dynamically from HttpServer
    public void storeConfiguration(String message) {
        logger.debug("handleUpdate(): message={}", message);
        CoreConfigurationBuilder builder = new CoreConfigurationBuilder(
                configurationService.getCoreConfiguration());
        JsonObject messageJson = new JsonParser().parse(message).getAsJsonObject();
        builder.setFromJson(messageJson);
        configurationService.updateCoreConfiguration(builder.build());
        if (messageJson.get("rollingSizeMb") != null) {
            int newRollingSizeMb = messageJson.get("rollingSizeMb").getAsInt();
            try {
                rollingFile.resize(newRollingSizeMb * 1024);
            } catch (IOException e) {
                logger.error(e.getMessage(), e);
                // TODO return HTTP 500 Internal Server Error?
                return;
            }
        }
    }
}
