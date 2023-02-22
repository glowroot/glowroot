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

import com.datastax.driver.core.Cluster;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import org.glowroot.central.Main;

public class MiscToolIT {

    @BeforeAll
    public static void setUp() throws Exception {
        SharedSetupRunListener.startCassandra();
        Cluster cluster = Clusters.newCluster();
        SchemaUpgradeIT.updateSchemaWithRetry(cluster.newSession(),
                "drop keyspace if exists glowroot_tools_test");
        cluster.close();

        System.setProperty("glowroot.cassandra.keyspace", "glowroot_tools_test");
        Main.main(new String[] {"create-schema"});
    }

    @AfterAll
    public static void tearDown() throws Exception {
        System.clearProperty("glowroot.cassandra.keyspace");
        if (!SharedSetupRunListener.isStarted()) {
            return;
        }
        SharedSetupRunListener.stopCassandra();
    }

    @Test
    public void runSetupAdminUser() throws Exception {
        Main.main(new String[] {"setup-admin-user", "me", "pw"});
    }

    @Test
    public void runTruncateAllData() throws Exception {
        Main.main(new String[] {"truncate-all-data"});
    }

    @Disabled("this requires range deletes which are only supported in Cassandra 3.x")
    @Test
    public void runExecuteRangeDeletes() throws Exception {

        Main.main(new String[] {"execute-range-deletes", "query", "3"});
        Main.main(new String[] {"execute-range-deletes", "profile", "3"});
    }
}
