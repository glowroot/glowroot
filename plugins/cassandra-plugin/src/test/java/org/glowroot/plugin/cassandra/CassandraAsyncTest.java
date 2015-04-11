/**
 * Copyright 2015 the original author or authors.
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
package org.glowroot.plugin.cassandra;

import java.util.List;

import com.datastax.driver.core.BatchStatement;
import com.datastax.driver.core.BoundStatement;
import com.datastax.driver.core.PreparedStatement;
import com.datastax.driver.core.ResultSet;
import com.datastax.driver.core.Row;
import com.datastax.driver.core.Session;
import com.datastax.driver.core.SimpleStatement;
import org.junit.After;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import org.glowroot.Containers;
import org.glowroot.container.AppUnderTest;
import org.glowroot.container.Container;
import org.glowroot.container.TraceMarker;
import org.glowroot.container.trace.Trace;
import org.glowroot.container.trace.TraceEntry;

import static org.assertj.core.api.Assertions.assertThat;

public class CassandraAsyncTest {

    private static Container container;

    @BeforeClass
    public static void setUp() throws Exception {
        CassandraWrapper.start();
        container = Containers.getSharedContainer();
        TempWorkaround.applyWorkaround(container);
    }

    @AfterClass
    public static void tearDown() throws Exception {
        container.close();
        CassandraWrapper.stop();
    }

    @After
    public void afterEachTest() throws Exception {
        container.checkAndReset();
    }

    @Test
    public void shouldAsyncExecuteStatement() throws Exception {
        container.executeAppUnderTest(ExecuteAsyncStatement.class);
        Trace trace = container.getTraceService().getLastTrace();
        List<TraceEntry> entries = container.getTraceService().getEntries(trace.getId());
        assertThat(entries).hasSize(1);
        assertThat(entries.get(0).getMessage().getText()).isEqualTo(
                "cql execution: SELECT * FROM test.users => 10 rows");
    }

    @Test
    public void shouldAsyncIterateUsingOneAndAll() throws Exception {
        container.executeAppUnderTest(AsyncIterateUsingOneAndAll.class);
        Trace trace = container.getTraceService().getLastTrace();
        List<TraceEntry> entries = container.getTraceService().getEntries(trace.getId());
        assertThat(entries).hasSize(1);
        assertThat(entries.get(0).getMessage().getText()).isEqualTo(
                "cql execution: SELECT * FROM test.users => 10 rows");
    }

    @Test
    public void shouldAsyncExecuteBoundStatement() throws Exception {
        container.executeAppUnderTest(AsyncExecuteBoundStatement.class);
        Trace trace = container.getTraceService().getLastTrace();
        List<TraceEntry> entries = container.getTraceService().getEntries(trace.getId());
        assertThat(entries).hasSize(1);
        assertThat(entries.get(0).getMessage().getText()).isEqualTo(
                "cql execution: INSERT INTO test.users (id,  fname, lname) VALUES (?, ?, ?)");
    }

    @Test
    public void shouldAsyncExecuteBatchStatement() throws Exception {
        container.executeAppUnderTest(AsyncExecuteBatchStatement.class);
        Trace trace = container.getTraceService().getLastTrace();
        List<TraceEntry> entries = container.getTraceService().getEntries(trace.getId());
        assertThat(entries).hasSize(1);
        assertThat(entries.get(0).getMessage().getText()).isEqualTo("cql execution:"
                + " INSERT INTO test.users (id,  fname, lname) VALUES (100, 'f100', 'l100'),"
                + " INSERT INTO test.users (id,  fname, lname) VALUES (101, 'f101', 'l101'),"
                + " 10 x INSERT INTO test.users (id,  fname, lname) VALUES (?, ?, ?),"
                + " INSERT INTO test.users (id,  fname, lname) VALUES (300, 'f300', 'l300')");
    }

    public static class ExecuteAsyncStatement implements AppUnderTest, TraceMarker {

        private Session session;

        @Override
        public void executeApp() throws Exception {
            session = Sessions.createSession();
            traceMarker();
            Sessions.closeSession(session);
        }

        @Override
        public void traceMarker() throws Exception {
            ResultSet results =
                    session.executeAsync("SELECT * FROM test.users").getUninterruptibly();
            for (Row row : results) {
                row.getInt("id");
            }
        }
    }

    public static class AsyncIterateUsingOneAndAll implements AppUnderTest,
            TraceMarker {

        private Session session;

        @Override
        public void executeApp() throws Exception {
            session = Sessions.createSession();
            traceMarker();
            Sessions.closeSession(session);
        }

        @Override
        public void traceMarker() throws Exception {
            ResultSet results =
                    session.executeAsync("SELECT * FROM test.users").getUninterruptibly();
            results.one();
            results.one();
            results.one();
            results.all();
        }
    }

    public static class AsyncExecuteBoundStatement implements AppUnderTest, TraceMarker {

        private Session session;

        @Override
        public void executeApp() throws Exception {
            session = Sessions.createSession();
            traceMarker();
            Sessions.closeSession(session);
        }

        @Override
        public void traceMarker() throws Exception {
            PreparedStatement preparedStatement = session.prepare(
                    "INSERT INTO test.users (id,  fname, lname) VALUES (?, ?, ?)");
            BoundStatement boundStatement = new BoundStatement(preparedStatement);
            boundStatement.bind(100, "f100", "l100");
            session.executeAsync(boundStatement).getUninterruptibly();
        }
    }

    public static class AsyncExecuteBatchStatement implements AppUnderTest, TraceMarker {

        private Session session;

        @Override
        public void executeApp() throws Exception {
            session = Sessions.createSession();
            traceMarker();
            Sessions.closeSession(session);
        }

        @Override
        public void traceMarker() throws Exception {
            BatchStatement batchStatement = new BatchStatement();
            batchStatement.add(new SimpleStatement(
                    "INSERT INTO test.users (id,  fname, lname) VALUES (100, 'f100', 'l100')"));
            batchStatement.add(new SimpleStatement(
                    "INSERT INTO test.users (id,  fname, lname) VALUES (101, 'f101', 'l101')"));
            PreparedStatement preparedStatement = session.prepare(
                    "INSERT INTO test.users (id,  fname, lname) VALUES (?, ?, ?)");
            for (int i = 200; i < 210; i++) {
                BoundStatement boundStatement = new BoundStatement(preparedStatement);
                boundStatement.bind(i, "f" + i, "l" + i);
                batchStatement.add(boundStatement);
            }
            batchStatement.add(new SimpleStatement(
                    "INSERT INTO test.users (id,  fname, lname) VALUES (300, 'f300', 'l300')"));
            session.executeAsync(batchStatement).getUninterruptibly();
        }
    }
}
