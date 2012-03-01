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

import org.informantproject.core.configuration.ConfigurationService;
import org.informantproject.core.util.RollingFile;
import org.informantproject.local.ui.HttpServer.JsonService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.inject.Inject;
import com.google.inject.Singleton;

/**
 * Json service to read configuration data. Bound to url "/configuration" in HttpServer.
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
    public String handleRead(String message) {
        logger.debug("handleRead(): message={}", message);
        double rollingSizeMb = rollingFile.getRollingSizeKb() / 1024;
        return "{\"coreConfiguration\":" + configurationService.getCoreConfiguration().toJson()
                + ",\"pluginConfiguration\":"
                + configurationService.getPluginConfiguration().toJson()
                + ",\"actualRollingSizeMb\":" + rollingSizeMb + "}";
    }

    // called dynamically from HttpServer
    public void handleUpdate(String message) {
        logger.debug("handleUpdate(): message={}", message);
        configurationService.updateCoreConfiguration(message);
    }
}
