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
package org.glowroot.agent.plugin.cassandra;

import java.util.List;

import com.datastax.driver.core.BatchStatement;
import com.datastax.driver.core.BoundStatement;
import com.datastax.driver.core.PreparedStatement;
import com.datastax.driver.core.ResultSet;
import com.datastax.driver.core.Row;
import com.datastax.driver.core.Session;
import org.junit.After;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import org.glowroot.agent.harness.AppUnderTest;
import org.glowroot.agent.harness.Container;
import org.glowroot.agent.harness.TransactionMarker;
import org.glowroot.agent.harness.aggregate.Query;
import org.glowroot.agent.harness.trace.Trace;

import static org.assertj.core.api.Assertions.assertThat;

public class CassandraAsyncTest {

    private static Container container;

    @BeforeClass
    public static void setUp() throws Exception {
        container = SharedSetupRunListener.getContainer();
    }

    @AfterClass
    public static void tearDown() throws Exception {
        SharedSetupRunListener.close(container);
    }

    @After
    public void afterEachTest() throws Exception {
        container.checkAndReset();
    }

    @Test
    public void shouldAsyncExecuteStatement() throws Exception {
        container.executeAppUnderTest(ExecuteAsyncStatement.class);
        Trace.Header header = container.getTraceService().getLastHeader();
        List<Query> queries = container.getAggregateService().getQueries();
        assertThat(queries).hasSize(1);
        Query query = queries.get(0);
        assertThat(query.getQueryText()).isEqualTo("SELECT * FROM test.users");
        assertThat(query.getExecutionCount()).isEqualTo(1);
        assertThat(query.getTotalRows()).isEqualTo(10);
        List<Trace.Entry> entries = container.getTraceService().getEntries(header.id());
        assertThat(entries).hasSize(1);
        assertThat(entries.get(0).message())
                .isEqualTo("cql execution: SELECT * FROM test.users => 10 rows");
    }

    @Test
    public void shouldAsyncIterateUsingOneAndAll() throws Exception {
        container.executeAppUnderTest(AsyncIterateUsingOneAndAll.class);
        Trace.Header header = container.getTraceService().getLastHeader();
        List<Query> queries = container.getAggregateService().getQueries();
        assertThat(queries).hasSize(1);
        Query query = queries.get(0);
        assertThat(query.getQueryText()).isEqualTo("SELECT * FROM test.users");
        assertThat(query.getExecutionCount()).isEqualTo(1);
        assertThat(query.getTotalRows()).isEqualTo(10);
        List<Trace.Entry> entries = container.getTraceService().getEntries(header.id());
        assertThat(entries).hasSize(1);
        assertThat(entries.get(0).message())
                .isEqualTo("cql execution: SELECT * FROM test.users => 10 rows");
    }

    @Test
    public void shouldAsyncExecuteBoundStatement() throws Exception {
        container.executeAppUnderTest(AsyncExecuteBoundStatement.class);
        Trace.Header header = container.getTraceService().getLastHeader();
        List<Query> queries = container.getAggregateService().getQueries();
        assertThat(queries).hasSize(1);
        Query query = queries.get(0);
        assertThat(query.getQueryText())
                .isEqualTo("INSERT INTO test.users (id,  fname, lname) VALUES (?, ?, ?)");
        assertThat(query.getExecutionCount()).isEqualTo(1);
        assertThat(query.getTotalRows()).isEqualTo(0);
        List<Trace.Entry> entries = container.getTraceService().getEntries(header.id());
        assertThat(entries).hasSize(1);
        assertThat(entries.get(0).message()).isEqualTo(
                "cql execution: INSERT INTO test.users (id,  fname, lname) VALUES (?, ?, ?)");
    }

    @Test
    public void shouldAsyncExecuteBatchStatement() throws Exception {
        container.executeAppUnderTest(AsyncExecuteBatchStatement.class);
        Trace.Header header = container.getTraceService().getLastHeader();
        List<Query> queries = container.getAggregateService().getQueries();
        assertThat(queries).hasSize(1);
        Query query = queries.get(0);
        assertThat(query.getQueryText()).isEqualTo("<batch cql>");
        assertThat(query.getExecutionCount()).isEqualTo(1);
        assertThat(query.getTotalRows()).isEqualTo(0);
        List<Trace.Entry> entries = container.getTraceService().getEntries(header.id());
        assertThat(entries).hasSize(1);
        assertThat(entries.get(0).message()).isEqualTo("cql execution:"
                + " INSERT INTO test.users (id,  fname, lname) VALUES (100, 'f100', 'l100'),"
                + " INSERT INTO test.users (id,  fname, lname) VALUES (101, 'f101', 'l101'),"
                + " 10 x INSERT INTO test.users (id,  fname, lname) VALUES (?, ?, ?),"
                + " INSERT INTO test.users (id,  fname, lname) VALUES (300, 'f300', 'l300')");
    }

    public static class ExecuteAsyncStatement implements AppUnderTest, TransactionMarker {

        private Session session;

        @Override
        public void executeApp() throws Exception {
            session = Sessions.createSession();
            transactionMarker();
            Sessions.closeSession(session);
        }

        @Override
        public void transactionMarker() throws Exception {
            ResultSet results = session.executeAsync("SELECT * FROM test.users").get();
            for (Row row : results) {
                row.getInt("id");
            }
        }
    }

    public static class AsyncIterateUsingOneAndAll implements AppUnderTest, TransactionMarker {

        private Session session;

        @Override
        public void executeApp() throws Exception {
            session = Sessions.createSession();
            transactionMarker();
            Sessions.closeSession(session);
        }

        @Override
        public void transactionMarker() throws Exception {
            ResultSet results = session.executeAsync("SELECT * FROM test.users").get();
            results.one();
            results.one();
            results.one();
            results.all();
        }
    }

    public static class AsyncExecuteBoundStatement implements AppUnderTest, TransactionMarker {

        private Session session;

        @Override
        public void executeApp() throws Exception {
            session = Sessions.createSession();
            transactionMarker();
            Sessions.closeSession(session);
        }

        @Override
        public void transactionMarker() throws Exception {
            PreparedStatement preparedStatement =
                    session.prepare("INSERT INTO test.users (id,  fname, lname) VALUES (?, ?, ?)");
            BoundStatement boundStatement = new BoundStatement(preparedStatement);
            boundStatement.bind(100, "f100", "l100");
            session.executeAsync(boundStatement).get();
        }
    }

    public static class AsyncExecuteBatchStatement implements AppUnderTest, TransactionMarker {

        private Session session;

        @Override
        public void executeApp() throws Exception {
            session = Sessions.createSession();
            transactionMarker();
            Sessions.closeSession(session);
        }

        @Override
        public void transactionMarker() throws Exception {
            BatchStatement batchStatement = new BatchStatement();
            batchStatement.add(session.newSimpleStatement(
                    "INSERT INTO test.users (id,  fname, lname) VALUES (100, 'f100', 'l100')"));
            batchStatement.add(session.newSimpleStatement(
                    "INSERT INTO test.users (id,  fname, lname) VALUES (101, 'f101', 'l101')"));
            PreparedStatement preparedStatement =
                    session.prepare("INSERT INTO test.users (id,  fname, lname) VALUES (?, ?, ?)");
            for (int i = 200; i < 210; i++) {
                BoundStatement boundStatement = new BoundStatement(preparedStatement);
                boundStatement.bind(i, "f" + i, "l" + i);
                batchStatement.add(boundStatement);
            }
            batchStatement.add(session.newSimpleStatement(
                    "INSERT INTO test.users (id,  fname, lname) VALUES (300, 'f300', 'l300')"));
            session.executeAsync(batchStatement).get();
        }
    }
}
