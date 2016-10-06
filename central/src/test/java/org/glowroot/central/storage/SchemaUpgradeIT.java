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
package org.glowroot.central.storage;

import com.datastax.driver.core.Cluster;
import com.datastax.driver.core.KeyspaceMetadata;
import com.datastax.driver.core.Session;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import org.glowroot.central.storage.SchemaUpgrade;

public class SchemaUpgradeIT {

    private static Cluster cluster;
    private static Session session;

    @BeforeClass
    public static void setUp() throws Exception {
        SharedSetupRunListener.startCassandra();
        cluster = Clusters.newCluster();
        session = cluster.newSession();
        session.execute("create keyspace if not exists glowroot_unit_tests with replication ="
                + " { 'class' : 'SimpleStrategy', 'replication_factor' : 1 }");
        session.execute("use glowroot_unit_tests");
    }

    @AfterClass
    public static void tearDown() throws Exception {
        session.close();
        cluster.close();
        SharedSetupRunListener.stopCassandra();
    }

    @Test
    public void shouldRead() throws Exception {
        // given
        KeyspaceMetadata keyspace = cluster.getMetadata().getKeyspace("glowroot_unit_tests");
        // when
        new SchemaUpgrade(session, keyspace);
        // then don't throw exception
    }
}
