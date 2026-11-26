/*
 * Copyright 2026 the original author or authors.
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
package org.glowroot.central;

import java.time.Duration;

import com.datastax.oss.driver.api.core.config.DefaultDriverOption;
import com.datastax.oss.driver.api.core.config.DriverConfig;
import com.datastax.oss.driver.api.core.config.DriverConfigLoader;
import com.datastax.oss.driver.api.core.config.DriverExecutionProfile;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

public class DatastaxDriverConfTest {

    @Test
    public void defaultDriverConfigurationUsesBoundedPoolAndRequestConcurrency() {
        try (DriverConfigLoader loader = DriverConfigLoader.fromClasspath("datastax-driver.conf")) {
            DriverConfig config = loader.getInitialConfig();
            DriverExecutionProfile defaultProfile = config.getDefaultProfile();

            int localPoolSize = defaultProfile.getInt(DefaultDriverOption.CONNECTION_POOL_LOCAL_SIZE);
            int maxRequestsPerConnection =
                    defaultProfile.getInt(DefaultDriverOption.CONNECTION_MAX_REQUESTS);
            int maxConcurrentRequests = defaultProfile
                    .getInt(DefaultDriverOption.REQUEST_THROTTLER_MAX_CONCURRENT_REQUESTS);

            assertThat(localPoolSize).isEqualTo(2);
            assertThat(defaultProfile.getInt(DefaultDriverOption.CONNECTION_POOL_REMOTE_SIZE))
                    .isEqualTo(1);
            assertThat(maxRequestsPerConnection).isEqualTo(1024);
            assertThat(defaultProfile.getString(DefaultDriverOption.REQUEST_THROTTLER_CLASS))
                    .isEqualTo("ConcurrencyLimitingRequestThrottler");
            assertThat(maxConcurrentRequests).isEqualTo(512);
            assertThat(defaultProfile.getInt(DefaultDriverOption.REQUEST_THROTTLER_MAX_QUEUE_SIZE))
                    .isEqualTo(100000);
            // Single-node Cassandra stream capacity must stay above throttler concurrency limit
            assertThat(localPoolSize * maxRequestsPerConnection)
                    .isGreaterThanOrEqualTo(maxConcurrentRequests);

            assertThat(config.getProfile("slow").getDuration(DefaultDriverOption.REQUEST_TIMEOUT))
                    .isEqualTo(Duration.ofSeconds(300));
            assertThat(
                    config.getProfile("collector").getDuration(DefaultDriverOption.REQUEST_TIMEOUT))
                            .isEqualTo(Duration.ofSeconds(30));
            assertThat(config.getProfile("rollup").getDuration(DefaultDriverOption.REQUEST_TIMEOUT))
                    .isEqualTo(Duration.ofSeconds(20));
            assertThat(config.getProfile("web").getDuration(DefaultDriverOption.REQUEST_TIMEOUT))
                    .isEqualTo(Duration.ofSeconds(20));
        }
    }
}
