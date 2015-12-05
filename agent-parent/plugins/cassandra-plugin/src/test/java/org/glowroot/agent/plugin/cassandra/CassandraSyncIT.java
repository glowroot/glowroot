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

import org.glowroot.agent.it.harness.AppUnderTest;
import org.glowroot.agent.it.harness.Container;
import org.glowroot.agent.it.harness.TransactionMarker;
import org.glowroot.wire.api.model.TraceOuterClass.Trace;

import static org.assertj.core.api.Assertions.assertThat;

public class CassandraSyncIT {

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
    public void shouldExecuteStatement() throws Exception {
        Trace trace = container.execute(ExecuteStatement.class);
        List<Trace.Entry> entries = trace.getEntryList();
        assertThat(entries).hasSize(1);
        assertThat(entries.get(0).getMessage())
                .isEqualTo("cql execution: SELECT * FROM test.users => 10 rows");
    }

    @Test
    public void shouldExecuteStatementReturningNoRecords() throws Exception {
        Trace trace = container.execute(ExecuteStatementReturningNoRecords.class);
        List<Trace.Entry> entries = trace.getEntryList();
        assertThat(entries).hasSize(1);
        assertThat(entries.get(0).getMessage())
                .isEqualTo("cql execution: SELECT * FROM test.users where id = 12345 => 0 rows");
    }

    @Test
    public void shouldIterateUsingOneAndAll() throws Exception {
        Trace trace = container.execute(IterateUsingOneAndAll.class);
        List<Trace.Entry> entries = trace.getEntryList();
        assertThat(entries).hasSize(1);
        assertThat(entries.get(0).getMessage())
                .isEqualTo("cql execution: SELECT * FROM test.users => 10 rows");
    }

    @Test
    public void shouldExecuteBoundStatement() throws Exception {
        Trace trace = container.execute(ExecuteBoundStatement.class);
        List<Trace.Entry> entries = trace.getEntryList();
        assertThat(entries).hasSize(1);
        assertThat(entries.get(0).getMessage()).isEqualTo(
                "cql execution: INSERT INTO test.users (id,  fname, lname) VALUES (?, ?, ?)");
    }

    @Test
    public void shouldExecuteBatchStatement() throws Exception {
        Trace trace = container.execute(ExecuteBatchStatement.class);
        List<Trace.Entry> entries = trace.getEntryList();
        assertThat(entries).hasSize(1);
        assertThat(entries.get(0).getMessage()).isEqualTo("cql execution:"
                + " INSERT INTO test.users (id,  fname, lname) VALUES (100, 'f100', 'l100'),"
                + " INSERT INTO test.users (id,  fname, lname) VALUES (101, 'f101', 'l101'),"
                + " 10 x INSERT INTO test.users (id,  fname, lname) VALUES (?, ?, ?),"
                + " INSERT INTO test.users (id,  fname, lname) VALUES (300, 'f300', 'l300')");
    }

    public static class ExecuteStatement implements AppUnderTest, TransactionMarker {

        private Session session;

        @Override
        public void executeApp() throws Exception {
            session = Sessions.createSession();
            transactionMarker();
            Sessions.closeSession(session);
        }

        @Override
        public void transactionMarker() throws Exception {
            ResultSet results = session.execute("SELECT * FROM test.users");
            for (Row row : results) {
                row.getInt("id");
            }
        }
    }

    public static class ExecuteStatementReturningNoRecords
            implements AppUnderTest, TransactionMarker {

        private Session session;

        @Override
        public void executeApp() throws Exception {
            session = Sessions.createSession();
            transactionMarker();
            Sessions.closeSession(session);
        }

        @Override
        public void transactionMarker() throws Exception {
            ResultSet results = session.execute("SELECT * FROM test.users where id = 12345");
            for (Row row : results) {
                row.getInt("id");
            }
        }
    }

    public static class IterateUsingOneAndAll implements AppUnderTest, TransactionMarker {

        private Session session;

        @Override
        public void executeApp() throws Exception {
            session = Sessions.createSession();
            transactionMarker();
            Sessions.closeSession(session);
        }

        @Override
        public void transactionMarker() throws Exception {
            ResultSet results = session.execute("SELECT * FROM test.users");
            results.one();
            results.one();
            results.one();
            results.all();
        }
    }

    public static class ExecuteBoundStatement implements AppUnderTest, TransactionMarker {

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
            session.execute(boundStatement);
        }
    }

    public static class ExecuteBatchStatement implements AppUnderTest, TransactionMarker {

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
            session.execute(batchStatement);
        }
    }
}
