/**
 * Copyright 2015-2016 the original author or authors.
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
package org.glowroot.agent.plugin.cassandra;

import java.io.IOException;

import com.datastax.driver.core.Cluster;
import com.datastax.driver.core.Session;
import com.datastax.driver.core.SocketOptions;

class Sessions {

    static Session createSession() throws IOException {
        Cluster cluster = Cluster.builder().addContactPoint("127.0.0.1")
                // long read timeout is sometimes needed on slow travis ci machines
                .withSocketOptions(new SocketOptions().setReadTimeoutMillis(30000))
                .build();
        Session session = cluster.connect();
        session.execute("CREATE KEYSPACE IF NOT EXISTS test WITH REPLICATION ="
                + " { 'class' : 'SimpleStrategy', 'replication_factor' : 1 }");
        session.execute("CREATE TABLE IF NOT EXISTS test.users"
                + " (id int PRIMARY KEY, fname text, lname text)");
        session.execute("TRUNCATE test.users");
        for (int i = 0; i < 10; i++) {
            session.execute("INSERT INTO test.users (id, fname, lname) VALUES (" + i + ", 'f" + i
                    + "', 'l" + i + "')");
        }
        return session;
    }

    static void closeSession(Session session) {
        Cluster cluster = session.getCluster();
        session.close();
        cluster.close();
    }
}
