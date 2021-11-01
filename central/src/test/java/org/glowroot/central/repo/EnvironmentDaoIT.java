/*
 * Copyright 2016-2019 the original author or authors.
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

import com.datastax.driver.core.Cluster;
import com.datastax.driver.core.PoolingOptions;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import org.glowroot.central.util.Session;
import org.glowroot.wire.api.model.CollectorServiceOuterClass.InitMessage.Environment;
import org.glowroot.wire.api.model.CollectorServiceOuterClass.InitMessage.Environment.HostInfo;

import static org.assertj.core.api.Assertions.assertThat;

public class EnvironmentDaoIT {

    private static Cluster cluster;
    private static Session session;
    private static EnvironmentDao environmentDao;

    @BeforeAll
    public static void setUp() throws Exception {
        SharedSetupRunListener.startCassandra();
        cluster = Clusters.newCluster();
        session = new Session(cluster.newSession(), "glowroot_unit_tests", null,
                PoolingOptions.DEFAULT_MAX_QUEUE_SIZE, 0);

        environmentDao = new EnvironmentDao(session);
    }

    @AfterAll
    public static void tearDown() throws Exception {
        session.close();
        cluster.close();
        SharedSetupRunListener.stopCassandra();
    }

    @BeforeEach
    public void before() throws Exception {
        session.updateSchemaWithRetry("truncate environment");
    }

    @Test
    public void shouldStoreEnvironment() throws Exception {
        // given
        Environment environment = Environment.newBuilder()
                .setHostInfo(HostInfo.newBuilder()
                        .setHostname("hosty"))
                .build();
        environmentDao.store("a", environment);
        // when
        Environment readEnvironment = environmentDao.read("a");
        // then
        assertThat(readEnvironment).isEqualTo(environment);
    }
}
