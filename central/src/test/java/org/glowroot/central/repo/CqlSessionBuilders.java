/*
 * Copyright 2016 the original author or authors.
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
package org.glowroot.central.repo;


import com.datastax.oss.driver.api.core.CqlSession;
import com.datastax.oss.driver.api.core.CqlSessionBuilder;
import com.datastax.oss.driver.api.core.config.DefaultDriverOption;
import com.datastax.oss.driver.api.core.config.DriverConfigLoader;
import org.glowroot.central.util.CassandraProfile;

import java.net.InetSocketAddress;
import java.time.Duration;

class CqlSessionBuilders {

    static final int MAX_CONCURRENT_QUERIES = 1024;

    static CqlSessionBuilder newCqlSessionBuilder() {
        return CqlSession.builder()
                .addContactPoint(new InetSocketAddress("127.0.0.1", 9042))
                .withLocalDatacenter("datacenter1")
                .withConfigLoader(DriverConfigLoader.programmaticBuilder()
                        // long read timeout is sometimes needed on slow travis ci machines
                        .withDuration(DefaultDriverOption.REQUEST_TIMEOUT, Duration.ofMillis(30_000))
                        // let driver know that only idempotent queries are used so it will retry on timeout
                        .withBoolean(DefaultDriverOption.REQUEST_DEFAULT_IDEMPOTENCE, true)
                        .withBoolean(DefaultDriverOption.REQUEST_WARN_IF_SET_KEYSPACE, false)
                        .startProfile(CassandraProfile.SLOW.name())
                        .withDuration(DefaultDriverOption.REQUEST_TIMEOUT, Duration.ofSeconds(60))
                        .withBoolean(DefaultDriverOption.REQUEST_WARN_IF_SET_KEYSPACE, false)
                        .endProfile()
                .build());
    }
}
