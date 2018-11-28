/**
 * Copyright 2015-2018 the original author or authors.
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

import java.util.Collections;
import java.util.Iterator;
import java.util.List;

import com.datastax.driver.core.BatchStatement;
import com.datastax.driver.core.BoundStatement;
import com.datastax.driver.core.PreparedStatement;
import com.datastax.driver.core.ResultSet;
import com.datastax.driver.core.ResultSetFuture;
import com.datastax.driver.core.Row;
import com.datastax.driver.core.Session;
import com.datastax.driver.core.SimpleStatement;
import com.google.common.collect.Lists;
import org.junit.After;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import org.glowroot.agent.it.harness.AppUnderTest;
import org.glowroot.agent.it.harness.Container;
import org.glowroot.agent.it.harness.TransactionMarker;
import org.glowroot.wire.api.model.AggregateOuterClass.Aggregate;
import org.glowroot.wire.api.model.TraceOuterClass.Trace;

import static org.assertj.core.api.Assertions.assertThat;

public class CassandraAsyncIT {

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
        // when
        Trace trace = container.execute(ExecuteAsyncStatement.class);

        // then
        checkTimers(trace, false, 1);

        Iterator<Trace.Entry> i = trace.getEntryList().iterator();
        List<Trace.SharedQueryText> sharedQueryTexts = trace.getSharedQueryTextList();

        Trace.Entry entry = i.next();
        assertThat(entry.getDepth()).isEqualTo(0);
        assertThat(entry.getMessage()).isEmpty();
        assertThat(sharedQueryTexts.get(entry.getQueryEntryMessage().getSharedQueryTextIndex())
                .getFullText()).isEqualTo("SELECT * FROM test.users");
        assertThat(entry.getQueryEntryMessage().getPrefix()).isEqualTo("cassandra query: ");
        assertThat(entry.getQueryEntryMessage().getSuffix()).isEqualTo(" => 10 rows");

        assertThat(i.hasNext()).isFalse();

        Iterator<Aggregate.Query> j = trace.getQueryList().iterator();

        Aggregate.Query query = j.next();
        assertThat(query.getType()).isEqualTo("CQL");
        assertThat(sharedQueryTexts.get(query.getSharedQueryTextIndex()).getFullText())
                .isEqualTo("SELECT * FROM test.users");
        assertThat(query.getExecutionCount()).isEqualTo(1);
        assertThat(query.getTotalRows().getValue()).isEqualTo(10);

        assertThat(j.hasNext()).isFalse();
    }

    @Test
    public void shouldConcurrentlyAsyncExecuteSameStatement() throws Exception {
        // when
        Trace trace = container.execute(ConcurrentlyExecuteSameAsyncStatement.class);

        // then
        checkTimers(trace, false, 100);

        Iterator<Trace.Entry> i = trace.getEntryList().iterator();
        List<Trace.SharedQueryText> sharedQueryTexts = trace.getSharedQueryTextList();

        for (int j = 0; j < 100; j++) {
            Trace.Entry entry = i.next();
            assertThat(entry.getDepth()).isEqualTo(0);
            assertThat(entry.getMessage()).isEmpty();
            assertThat(sharedQueryTexts.get(entry.getQueryEntryMessage().getSharedQueryTextIndex())
                    .getFullText()).isEqualTo("SELECT * FROM test.users");
            assertThat(entry.getQueryEntryMessage().getPrefix()).isEqualTo("cassandra query: ");
            assertThat(entry.getQueryEntryMessage().getSuffix()).isEqualTo(" => 10 rows");
        }
        assertThat(i.hasNext()).isFalse();

        Iterator<Aggregate.Query> j = trace.getQueryList().iterator();

        Aggregate.Query query = j.next();
        assertThat(query.getType()).isEqualTo("CQL");
        assertThat(sharedQueryTexts.get(query.getSharedQueryTextIndex()).getFullText())
                .isEqualTo("SELECT * FROM test.users");
        assertThat(query.getExecutionCount()).isEqualTo(100);
        assertThat(query.getTotalRows().getValue()).isEqualTo(1000);

        assertThat(j.hasNext()).isFalse();
    }

    @Test
    public void shouldAsyncExecuteStatementReturningNoRecords() throws Exception {
        // when
        Trace trace = container.execute(ExecuteAsyncStatementReturningNoRecords.class);

        // then
        checkTimers(trace, false, 1);

        Iterator<Trace.Entry> i = trace.getEntryList().iterator();
        List<Trace.SharedQueryText> sharedQueryTexts = trace.getSharedQueryTextList();

        Trace.Entry entry = i.next();
        assertThat(entry.getDepth()).isEqualTo(0);
        assertThat(entry.getMessage()).isEmpty();
        assertThat(sharedQueryTexts.get(entry.getQueryEntryMessage().getSharedQueryTextIndex())
                .getFullText()).isEqualTo("SELECT * FROM test.users where id = 12345");
        assertThat(entry.getQueryEntryMessage().getPrefix()).isEqualTo("cassandra query: ");
        assertThat(entry.getQueryEntryMessage().getSuffix()).isEqualTo(" => 0 rows");

        assertThat(i.hasNext()).isFalse();

        Iterator<Aggregate.Query> j = trace.getQueryList().iterator();

        Aggregate.Query query = j.next();
        assertThat(query.getType()).isEqualTo("CQL");
        assertThat(sharedQueryTexts.get(query.getSharedQueryTextIndex()).getFullText())
                .isEqualTo("SELECT * FROM test.users where id = 12345");
        assertThat(query.getExecutionCount()).isEqualTo(1);
        assertThat(query.hasTotalRows()).isTrue();
        assertThat(query.getTotalRows().getValue()).isEqualTo(0);

        assertThat(j.hasNext()).isFalse();
    }

    @Test
    public void shouldAsyncIterateUsingOneAndAll() throws Exception {
        // when
        Trace trace = container.execute(AsyncIterateUsingOneAndAll.class);

        // then
        checkTimers(trace, false, 1);

        Iterator<Trace.Entry> i = trace.getEntryList().iterator();
        List<Trace.SharedQueryText> sharedQueryTexts = trace.getSharedQueryTextList();

        Trace.Entry entry = i.next();
        assertThat(entry.getDepth()).isEqualTo(0);
        assertThat(entry.getMessage()).isEmpty();
        assertThat(sharedQueryTexts.get(entry.getQueryEntryMessage().getSharedQueryTextIndex())
                .getFullText()).isEqualTo("SELECT * FROM test.users");
        assertThat(entry.getQueryEntryMessage().getPrefix()).isEqualTo("cassandra query: ");
        assertThat(entry.getQueryEntryMessage().getSuffix()).isEqualTo(" => 10 rows");

        assertThat(i.hasNext()).isFalse();

        Iterator<Aggregate.Query> j = trace.getQueryList().iterator();

        Aggregate.Query query = j.next();
        assertThat(query.getType()).isEqualTo("CQL");
        assertThat(sharedQueryTexts.get(query.getSharedQueryTextIndex()).getFullText())
                .isEqualTo("SELECT * FROM test.users");
        assertThat(query.getExecutionCount()).isEqualTo(1);
        assertThat(query.getTotalRows().getValue()).isEqualTo(10);

        assertThat(j.hasNext()).isFalse();
    }

    @Test
    public void shouldAsyncExecuteBoundStatement() throws Exception {
        // when
        Trace trace = container.execute(AsyncExecuteBoundStatement.class);

        // then
        checkTimers(trace, true, 1);

        Iterator<Trace.Entry> i = trace.getEntryList().iterator();
        List<Trace.SharedQueryText> sharedQueryTexts = trace.getSharedQueryTextList();

        Trace.Entry entry = i.next();
        assertThat(entry.getDepth()).isEqualTo(0);
        assertThat(entry.getMessage()).isEmpty();
        assertThat(sharedQueryTexts.get(entry.getQueryEntryMessage().getSharedQueryTextIndex())
                .getFullText())
                        .isEqualTo("INSERT INTO test.users (id,  fname, lname) VALUES (?, ?, ?)");
        assertThat(entry.getQueryEntryMessage().getPrefix()).isEqualTo("cassandra query: ");
        assertThat(entry.getQueryEntryMessage().getSuffix()).isEmpty();

        assertThat(i.hasNext()).isFalse();

        Iterator<Aggregate.Query> j = trace.getQueryList().iterator();

        Aggregate.Query query = j.next();
        assertThat(query.getType()).isEqualTo("CQL");
        assertThat(sharedQueryTexts.get(query.getSharedQueryTextIndex()).getFullText())
                .isEqualTo("INSERT INTO test.users (id,  fname, lname) VALUES (?, ?, ?)");
        assertThat(query.getExecutionCount()).isEqualTo(1);
        assertThat(query.hasTotalRows()).isFalse();

        assertThat(j.hasNext()).isFalse();
    }

    @Test
    public void shouldAsyncExecuteBatchStatement() throws Exception {
        // when
        Trace trace = container.execute(AsyncExecuteBatchStatement.class);

        // then
        checkTimers(trace, true, 1);

        Iterator<Trace.Entry> i = trace.getEntryList().iterator();
        List<Trace.SharedQueryText> sharedQueryTexts = trace.getSharedQueryTextList();

        Trace.Entry entry = i.next();
        assertThat(entry.getDepth()).isEqualTo(0);
        assertThat(entry.getMessage()).isEmpty();
        assertThat(sharedQueryTexts.get(entry.getQueryEntryMessage().getSharedQueryTextIndex())
                .getFullText()).isEqualTo("[batch] INSERT INTO test.users (id,  fname, lname)"
                        + " VALUES (100, 'f100', 'l100'),"
                        + " INSERT INTO test.users (id,  fname, lname)"
                        + " VALUES (101, 'f101', 'l101'),"
                        + " 10 x INSERT INTO test.users (id,  fname, lname) VALUES (?, ?, ?),"
                        + " INSERT INTO test.users (id,  fname, lname)"
                        + " VALUES (300, 'f300', 'l300')");
        assertThat(entry.getQueryEntryMessage().getPrefix()).isEqualTo("cassandra query: ");
        assertThat(entry.getQueryEntryMessage().getSuffix()).isEmpty();

        assertThat(i.hasNext()).isFalse();

        Iterator<Aggregate.Query> j = trace.getQueryList().iterator();

        Aggregate.Query query = j.next();
        assertThat(query.getType()).isEqualTo("CQL");
        assertThat(sharedQueryTexts.get(query.getSharedQueryTextIndex()).getFullText())
                .isEqualTo("[batch] INSERT INTO test.users (id,  fname, lname)"
                        + " VALUES (100, 'f100', 'l100'),"
                        + " INSERT INTO test.users (id,  fname, lname)"
                        + " VALUES (101, 'f101', 'l101'),"
                        + " 10 x INSERT INTO test.users (id,  fname, lname) VALUES (?, ?, ?),"
                        + " INSERT INTO test.users (id,  fname, lname)"
                        + " VALUES (300, 'f300', 'l300')");
        assertThat(query.getExecutionCount()).isEqualTo(1);
        assertThat(query.hasTotalRows()).isFalse();

        assertThat(j.hasNext()).isFalse();
    }

    private static void checkTimers(Trace trace, boolean prepared, int count) {
        Trace.Timer rootTimer = trace.getHeader().getMainThreadRootTimer();
        List<String> timerNames = Lists.newArrayList();
        for (Trace.Timer timer : rootTimer.getChildTimerList()) {
            timerNames.add(timer.getName());
        }
        Collections.sort(timerNames);
        if (prepared) {
            assertThat(timerNames).containsExactly("cassandra query", "cql prepare");
        } else {
            assertThat(timerNames).containsExactly("cassandra query");
        }
        for (Trace.Timer timer : rootTimer.getChildTimerList()) {
            assertThat(timer.getChildTimerList()).isEmpty();
        }
        assertThat(trace.getHeader().getAsyncTimerCount()).isEqualTo(1);
        Trace.Timer asyncTimer = trace.getHeader().getAsyncTimer(0);
        assertThat(asyncTimer.getChildTimerCount()).isZero();
        assertThat(asyncTimer.getName()).isEqualTo("cassandra query");
        assertThat(asyncTimer.getCount()).isEqualTo(count);
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
            ResultSetFuture future = session.executeAsync("SELECT * FROM test.users");
            ResultSet results = future.get();
            for (Row row : results) {
                row.getInt("id");
            }
        }
    }

    public static class ConcurrentlyExecuteSameAsyncStatement
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
            List<ResultSetFuture> futures = Lists.newArrayList();
            for (int i = 0; i < 100; i++) {
                futures.add(session.executeAsync("SELECT * FROM test.users"));
            }
            for (ResultSetFuture future : futures) {
                ResultSet results = future.get();
                for (Row row : results) {
                    row.getInt("id");
                }
            }
        }
    }

    public static class ExecuteAsyncStatementReturningNoRecords
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
            ResultSet results =
                    session.executeAsync("SELECT * FROM test.users where id = 12345").get();
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
            batchStatement.add(new SimpleStatement(
                    "INSERT INTO test.users (id,  fname, lname) VALUES (100, 'f100', 'l100')"));
            batchStatement.add(new SimpleStatement(
                    "INSERT INTO test.users (id,  fname, lname) VALUES (101, 'f101', 'l101')"));
            PreparedStatement preparedStatement =
                    session.prepare("INSERT INTO test.users (id,  fname, lname) VALUES (?, ?, ?)");
            for (int i = 200; i < 210; i++) {
                BoundStatement boundStatement = new BoundStatement(preparedStatement);
                boundStatement.bind(i, "f" + i, "l" + i);
                batchStatement.add(boundStatement);
            }
            batchStatement.add(new SimpleStatement(
                    "INSERT INTO test.users (id,  fname, lname) VALUES (300, 'f300', 'l300')"));
            session.executeAsync(batchStatement).get();
        }
    }
}
