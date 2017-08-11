/**
 * Copyright 2015-2017 the original author or authors.
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

import java.lang.reflect.Method;

import com.datastax.driver.core.Cluster;
import com.datastax.driver.core.QueryOptions;
import com.datastax.driver.core.Session;
import com.datastax.driver.core.SocketOptions;
import com.datastax.driver.core.exceptions.NoHostAvailableException;

class Sessions {

    static Session createSession() throws Exception {
        Cluster cluster = Cluster.builder().addContactPoint("127.0.0.1")
                // long read timeout is sometimes needed on slow travis ci machines
                .withSocketOptions(new SocketOptions().setReadTimeoutMillis(30000))
                .withQueryOptions(getQueryOptions())
                .build();
        Session session = cluster.connect();
        session.execute("CREATE KEYSPACE IF NOT EXISTS test WITH REPLICATION ="
                + " { 'class' : 'SimpleStrategy', 'replication_factor' : 1 }");
        session.execute("CREATE TABLE IF NOT EXISTS test.users"
                + " (id int PRIMARY KEY, fname text, lname text)");
        try {
            session.execute("TRUNCATE test.users");
        } catch (NoHostAvailableException e) {
            // sometimes slow, so give it a second chance
            session.execute("TRUNCATE test.users");
        }
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

    // if possible, let driver know that only idempotent queries are used so it will retry on
    // timeout
    private static QueryOptions getQueryOptions() {
        QueryOptions queryOptions = new QueryOptions();
        Method setDefaultIdempotenceMethod;
        try {
            setDefaultIdempotenceMethod =
                    QueryOptions.class.getMethod("setDefaultIdempotence", boolean.class);
            setDefaultIdempotenceMethod.invoke(queryOptions, true);
        } catch (Exception e) {
            // early version of driver
        }
        return queryOptions;
    }
}
