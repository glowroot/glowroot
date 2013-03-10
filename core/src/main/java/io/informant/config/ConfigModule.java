/**
 * Copyright 2011-2013 the original author or authors.
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
package io.informant.config;

import io.informant.marker.ThreadSafe;
import io.informant.util.Clock;

import java.io.File;
import java.util.Map;

import checkers.igj.quals.ReadOnly;

import com.google.common.base.Ticker;

/**
 * @author Trask Stalnaker
 * @since 0.5
 */
@ThreadSafe
public class ConfigModule {

    private final Ticker ticker;
    private final Clock clock;
    private final File dataDir;
    private final PluginDescriptorCache pluginDescriptorCache;
    private final ConfigService configService;

    public ConfigModule(@ReadOnly Map<String, String> properties) {
        ticker = Ticker.systemTicker();
        clock = Clock.systemClock();
        dataDir = DataDir.getDataDir(properties);
        pluginDescriptorCache = new PluginDescriptorCache();
        configService = new ConfigService(dataDir, pluginDescriptorCache);
    }

    public Ticker getTicker() {
        return ticker;
    }

    public Clock getClock() {
        return clock;
    }

    public File getDataDir() {
        return dataDir;
    }

    public PluginDescriptorCache getPluginDescriptorCache() {
        return pluginDescriptorCache;
    }

    public ConfigService getConfigService() {
        return configService;
    }
}
