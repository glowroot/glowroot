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

import org.informantproject.core.configuration.ImmutableCoreConfiguration;
import org.informantproject.core.configuration.ImmutableCoreConfiguration.CoreConfigurationBuilder;

/**
 * @author Trask Stalnaker
 * @since 0.5
 */
public class CoreConfigurationTestData {

    public ImmutableCoreConfiguration getRandomCoreConfiguration() {

        ImmutableCoreConfiguration defaultCoreConfiguration = new ImmutableCoreConfiguration();

        CoreConfigurationBuilder builder = new CoreConfigurationBuilder(defaultCoreConfiguration);

        // cover all fields
        builder = builder.setEnabled(!defaultCoreConfiguration.isEnabled());
        builder = builder.setThresholdMillis(defaultCoreConfiguration.getThresholdMillis() + 1);
        builder = builder.setStuckThresholdSeconds(defaultCoreConfiguration
                .getStuckThresholdSeconds() + 1);
        builder = builder.setProfilerInitialDelayMillis(defaultCoreConfiguration
                .getProfilerInitialDelayMillis() + 1);
        builder = builder.setProfilerIntervalMillis(defaultCoreConfiguration
                .getProfilerIntervalMillis() + 1);
        builder = builder.setMaxEntries(defaultCoreConfiguration.getMaxEntries() + 1);
        builder = builder.setWarnOnEntryOutsideTrace(!defaultCoreConfiguration
                .isWarnOnEntryOutsideTrace());

        return builder.build();
    }
}
