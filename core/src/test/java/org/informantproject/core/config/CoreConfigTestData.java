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

import org.informantproject.core.config.CoreConfig.CoreConfigBuilder;

/**
 * @author Trask Stalnaker
 * @since 0.5
 */
public class CoreConfigTestData {

    public CoreConfig getRandomCoreConfig() {
        CoreConfig defaultCoreConfig = new CoreConfig();
        CoreConfigBuilder builder = new CoreConfigBuilder(defaultCoreConfig);

        // cover all fields
        builder = builder.setEnabled(!defaultCoreConfig.isEnabled());
        builder = builder.setThresholdMillis(defaultCoreConfig.getThresholdMillis() + 1);
        builder = builder
                .setStuckThresholdSeconds(defaultCoreConfig.getStuckThresholdSeconds() + 1);
        builder = builder.setProfilerInitialDelayMillis(defaultCoreConfig
                .getProfilerInitialDelayMillis() + 1);
        builder = builder
                .setProfilerIntervalMillis(defaultCoreConfig.getProfilerIntervalMillis() + 1);
        builder = builder.setMaxEntries(defaultCoreConfig.getMaxEntries() + 1);
        builder = builder
                .setWarnOnEntryOutsideTrace(!defaultCoreConfig.isWarnOnEntryOutsideTrace());

        return builder.build();
    }
}
