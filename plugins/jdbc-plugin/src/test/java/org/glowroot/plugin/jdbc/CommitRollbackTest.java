/*
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
package org.glowroot.plugin.jdbc;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;

import org.apache.commons.dbcp.DelegatingConnection;
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

public class CommitRollbackTest {

    private static Container container;

    @BeforeClass
    public static void setUp() throws Exception {
        container = Containers.getSharedContainer();
    }

    @AfterClass
    public static void tearDown() throws Exception {
        container.close();
    }

    @After
    public void afterEachTest() throws Exception {
        container.checkAndReset();
    }

    @Test
    public void testCommit() throws Exception {
        // given
        // when
        container.executeAppUnderTest(ExecuteJdbcCommit.class);
        // then
        Trace trace = container.getTraceService().getLastTrace();
        List<TraceEntry> entries = container.getTraceService().getEntries(trace.getId());
        assertThat(entries).hasSize(3);
        TraceEntry jdbcInsertEntry = entries.get(1);
        assertThat(jdbcInsertEntry.getMessage().getText()).isEqualTo(
                "jdbc execution: insert into employee (name) values ('john doe')");
        TraceEntry jdbcCommitEntry = entries.get(2);
        assertThat(jdbcCommitEntry.getMessage().getText()).isEqualTo("jdbc commit");
        assertThat(trace.getRootTimer().getNestedTimers()).hasSize(2);
        // ordering is by total desc, so not fixed (though root timer will be first since it
        // encompasses all other timings)
        assertThat(trace.getRootTimer().getName()).isEqualTo("mock trace marker");
        assertThat(trace.getRootTimer().getNestedTimerNames())
                .containsOnly("jdbc execute", "jdbc commit");
    }

    @Test
    public void testCommitThrowing() throws Exception {
        // given
        // when
        container.executeAppUnderTest(ExecuteJdbcCommitThrowing.class);
        // then
        Trace trace = container.getTraceService().getLastTrace();
        List<TraceEntry> entries = container.getTraceService().getEntries(trace.getId());
        assertThat(entries).hasSize(3);
        TraceEntry jdbcInsertEntry = entries.get(1);
        assertThat(jdbcInsertEntry.getMessage().getText()).isEqualTo(
                "jdbc execution: insert into employee (name) values ('john doe')");
        TraceEntry jdbcCommitEntry = entries.get(2);
        assertThat(jdbcCommitEntry.getMessage().getText()).isEqualTo("jdbc commit");
        assertThat(jdbcCommitEntry.getError().getText()).isEqualTo(
                "java.sql.SQLException: A commit failure");
        assertThat(trace.getRootTimer().getNestedTimers()).hasSize(2);
        // ordering is by total desc, so not fixed (though root timer will be first since it
        // encompasses all other timings)
        assertThat(trace.getRootTimer().getName()).isEqualTo("mock trace marker");
        assertThat(trace.getRootTimer().getNestedTimerNames())
                .containsOnly("jdbc execute", "jdbc commit");
    }

    @Test
    public void testRollback() throws Exception {
        // given
        // when
        container.executeAppUnderTest(ExecuteJdbcRollback.class);
        // then
        Trace trace = container.getTraceService().getLastTrace();
        List<TraceEntry> entries = container.getTraceService().getEntries(trace.getId());
        assertThat(entries).hasSize(3);
        TraceEntry jdbcInsertEntry = entries.get(1);
        assertThat(jdbcInsertEntry.getMessage().getText()).isEqualTo(
                "jdbc execution: insert into employee (name) values ('john doe')");
        TraceEntry jdbcCommitEntry = entries.get(2);
        assertThat(jdbcCommitEntry.getMessage().getText()).isEqualTo("jdbc rollback");
        assertThat(trace.getRootTimer().getNestedTimers()).hasSize(2);
        // ordering is by total desc, so not fixed (though root timer will be first since it
        // encompasses all other timings)
        assertThat(trace.getRootTimer().getName()).isEqualTo("mock trace marker");
        assertThat(trace.getRootTimer().getNestedTimerNames())
                .containsOnly("jdbc execute", "jdbc rollback");
    }

    @Test
    public void testRollbackThrowing() throws Exception {
        // given
        // when
        container.executeAppUnderTest(ExecuteJdbcRollbackThrowing.class);
        // then
        Trace trace = container.getTraceService().getLastTrace();
        List<TraceEntry> entries = container.getTraceService().getEntries(trace.getId());
        assertThat(entries).hasSize(3);
        TraceEntry jdbcInsertEntry = entries.get(1);
        assertThat(jdbcInsertEntry.getMessage().getText()).isEqualTo(
                "jdbc execution: insert into employee (name) values ('john doe')");
        TraceEntry jdbcCommitEntry = entries.get(2);
        assertThat(jdbcCommitEntry.getMessage().getText()).isEqualTo("jdbc rollback");
        assertThat(jdbcCommitEntry.getError().getText()).isEqualTo(
                "java.sql.SQLException: A rollback failure");
        assertThat(trace.getRootTimer().getNestedTimers()).hasSize(2);
        // ordering is by total desc, so not fixed (though root timer will be first since it
        // encompasses all other timings)
        assertThat(trace.getRootTimer().getName()).isEqualTo("mock trace marker");
        assertThat(trace.getRootTimer().getNestedTimerNames())
                .containsOnly("jdbc execute", "jdbc rollback");
    }

    public abstract static class ExecuteJdbcCommitBase implements AppUnderTest, TraceMarker {
        protected Connection connection;
        @Override
        public void executeApp() throws Exception {
            connection = Connections.createConnection();
            connection.setAutoCommit(false);
            try {
                traceMarker();
            } finally {
                Connections.closeConnection(connection);
            }
        }
        void executeInsert() throws Exception {
            Statement statement = connection.createStatement();
            try {
                statement.execute("insert into employee (name) values ('john doe')");
            } finally {
                statement.close();
            }
        }
    }

    public abstract static class ExecuteJdbcCommitThrowingBase implements AppUnderTest,
            TraceMarker {
        protected Connection connection;
        @Override
        public void executeApp() throws Exception {
            connection = new DelegatingConnection(Connections.createConnection()) {
                @Override
                public void commit() throws SQLException {
                    throw new SQLException("A commit failure");
                }
                @Override
                public void rollback() throws SQLException {
                    throw new SQLException("A rollback failure");
                }
            };
            connection.setAutoCommit(false);
            try {
                traceMarker();
            } finally {
                Connections.closeConnection(connection);
            }
        }
        void executeInsert() throws Exception {
            Statement statement = connection.createStatement();
            try {
                statement.execute("insert into employee (name) values ('john doe')");
            } finally {
                statement.close();
            }
        }
    }

    public static class ExecuteJdbcCommit extends ExecuteJdbcCommitBase {
        @Override
        public void traceMarker() throws Exception {
            executeInsert();
            connection.commit();
        }
    }

    public static class ExecuteJdbcRollback extends ExecuteJdbcCommitBase {
        @Override
        public void traceMarker() throws Exception {
            executeInsert();
            connection.rollback();
        }
    }

    public static class ExecuteJdbcCommitThrowing extends ExecuteJdbcCommitThrowingBase {
        @Override
        public void traceMarker() throws Exception {
            executeInsert();
            try {
                connection.commit();
            } catch (SQLException e) {
            }
        }
    }

    public static class ExecuteJdbcRollbackThrowing extends ExecuteJdbcCommitThrowingBase {
        @Override
        public void traceMarker() throws Exception {
            executeInsert();
            try {
                connection.rollback();
            } catch (SQLException e) {
            }
        }
    }
}
