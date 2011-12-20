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
package org.informantproject.local.configuration;

import org.informantproject.configuration.ConfigurationService;
import org.informantproject.local.ui.LocalHttpHandler.JsonService;

import com.google.inject.Inject;
import com.google.inject.Singleton;

/**
 * Json service to update configuration data. Bound to url "/configuration/update" in LocalModule.
 * 
 * @author Trask Stalnaker
 * @since 0.5
 */
@Singleton
public class UpdateConfigurationJsonService implements JsonService {

    private final ConfigurationService configurationService;

    @Inject
    public UpdateConfigurationJsonService(ConfigurationService configurationService) {
        this.configurationService = configurationService;
    }

    public String handleRequest(String message) {
        configurationService.updateConfiguration(message);
        return null;
    }
}
