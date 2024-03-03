/*
 * Copyright 2018 the original author or authors.
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
import com.datastax.oss.driver.api.core.config.DriverConfigLoader;
import org.glowroot.central.Main;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.CassandraContainer;
import org.testcontainers.containers.wait.CassandraQueryWaitStrategy;

public class MiscToolIT {
    public static final CassandraContainer cassandra
            = (CassandraContainer) new CassandraContainer("cassandra:3.11.15").withExposedPorts(9042);

    @BeforeAll
    public static void setUp() throws Exception {
        cassandra.start();
        CqlSession session = CqlSession
                .builder()
                .addContactPoint(cassandra.getContactPoint())
                .withLocalDatacenter(cassandra.getLocalDatacenter())
                .withConfigLoader(DriverConfigLoader.fromClasspath("datastax-driver.conf")).build();
        SchemaUpgradeIT.updateSchemaWithRetry(session,
                "drop keyspace if exists glowroot_tools_test");
        session.close();

        System.setProperty("glowroot.cassandra.keyspace", "glowroot_tools_test");
        System.setProperty("glowroot.cassandra.localDatacenter", cassandra.getLocalDatacenter());
        System.setProperty("glowroot.cassandra.contactPoints", cassandra.getContactPoint().getHostString());
        System.setProperty("glowroot.cassandra.port", cassandra.getMappedPort(9042).toString());
        Main.main(new String[] {"create-schema"});
    }

    @AfterAll
    public static void tearDown() throws Exception {
        cassandra.stop();
        System.clearProperty("glowroot.cassandra.keyspace");
    }

    @Test
    public void runSetupAdminUser() throws Exception {
        Main.main(new String[] {"setup-admin-user", "me", "pw"});
    }

    @Test
    public void runTruncateAllData() throws Exception {
        Main.main(new String[] {"truncate-all-data"});
    }

    @Test
    public void runExecuteRangeDeletes() throws Exception {

        Main.main(new String[] {"execute-range-deletes", "query", "3"});
        Main.main(new String[] {"execute-range-deletes", "profile", "3"});
    }
}
