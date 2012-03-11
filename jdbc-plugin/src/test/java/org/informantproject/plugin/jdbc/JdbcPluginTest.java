/**
 * Copyright 2011-2012 the original author or authors.
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
package org.informantproject.plugin.jdbc;

import static org.hamcrest.CoreMatchers.is;
import static org.junit.Assert.assertThat;

import java.io.File;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;

import org.h2.jdbcx.JdbcDataSource;
import org.informantproject.testkit.AppUnderTest;
import org.informantproject.testkit.InformantContainer;
import org.informantproject.testkit.RootSpanMarker;
import org.informantproject.testkit.Trace;
import org.informantproject.testkit.Trace.Span;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

/**
 * Basic test of the jdbc plugin.
 * 
 * @author Trask Stalnaker
 * @since 0.5
 */
// TODO use p6spy to run tests against a proxied jdbc connections
// which are common in application server environments
public class JdbcPluginTest {

    private static final String DB_NAME = "test";
    private static InformantContainer container;

    @BeforeClass
    public static void setUp() throws Exception {
        container = InformantContainer.create();
        // set up database
        new File(DB_NAME + ".h2.db").delete();
        Connection connection = createConnection();
        Statement statement = null;
        try {
            statement = connection.createStatement();
            statement.execute("create table employee (name varchar(100))");
            statement.execute("insert into employee (name) values ('john doe')");
        } finally {
            if (statement != null) {
                statement.close();
            }
            connection.close();
        }
    }

    @AfterClass
    public static void tearDown() throws Exception {
        container.shutdown();
    }

    @Test
    public void testStatement() throws Exception {
        // given
        container.getInformant().setThresholdMillis(0);
        // when
        container.executeAppUnderTest(ExecuteJdbcSelectAndIterateOverResults.class);
        // then
        List<Trace> traces = container.getInformant().getAllTraces();
        assertThat(traces.size(), is(1));
        Trace trace = traces.get(0);
        assertThat(trace.getSpans().size(), is(2));
        Span rootSpan = trace.getSpans().get(0);
        assertThat(rootSpan.getDescription(), is("mock root span"));
        Span jdbcSpan = trace.getSpans().get(1);
        assertThat(jdbcSpan.getDescription(),
                is("jdbc execution: select * from employee => 1 row"));
    }

    @Test
    public void testCommit() throws Exception {
        // given
        container.getInformant().setThresholdMillis(0);
        // when
        container.executeAppUnderTest(ExecuteJdbcCommit.class);
        // then
        List<Trace> traces = container.getInformant().getAllTraces();
        assertThat(traces.size(), is(1));
        Trace trace = traces.get(0);
        assertThat(trace.getSpans().size(), is(3));
        Span rootSpan = trace.getSpans().get(0);
        assertThat(rootSpan.getDescription(), is("mock root span"));
        Span jdbcInsertSpan = trace.getSpans().get(1);
        assertThat(jdbcInsertSpan.getDescription(), is("jdbc execution: insert into employee"
                + " (name) values ('john doe')"));
        Span jdbcCommitSpan = trace.getSpans().get(2);
        assertThat(jdbcCommitSpan.getDescription(), is("jdbc commit"));
        assertThat(trace.getMetrics().size(), is(3));
        assertThat(trace.getMetrics().get(0).getName(), is("mock root span"));
        assertThat(trace.getMetrics().get(1).getName(), is("jdbc execute"));
        assertThat(trace.getMetrics().get(2).getName(), is("jdbc commit"));
    }

    // TODO testPreparedStatement
    // select * from employee where name like ?
    // [john%]

    // TODO testPreparedStatementWithSetNull
    // insert into employee (name) values (?)

    // TODO testCallableStatement
    // select * from employee where name = ?
    // [john%]

    // TODO make a release build profile that runs all tests against
    // Hsqldb, Oracle, SQLServer, MySQL, ...

    // TODO testSqlServerIssue
    // exploit issue with SQLServer PreparedStatement.getParameterMetaData(), result being that
    // getParameterMetaData() cannot be used in the jdbc plugin, this test (if it can be run
    // against SQLServer) ensures that getParameterMetaData() doesn't sneak back in the future
    // select * from employee where (name like ?)
    // [john%]

    private static Connection createConnection() throws SQLException {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:" + DB_NAME);
        dataSource.setUser("sa");
        return dataSource.getConnection();
    }

    public static class ExecuteJdbcSelectAndIterateOverResults implements AppUnderTest,
            RootSpanMarker {

        private Connection connection;
        public void executeApp() throws Exception {
            connection = createConnection();
            try {
                rootSpanMarker();
            } finally {
                connection.close();
            }
        }
        public void rootSpanMarker() throws Exception {
            Statement statement = connection.createStatement();
            try {
                statement.execute("select * from employee");
                ResultSet rs = statement.getResultSet();
                while (rs.next()) {
                }
            } finally {
                statement.close();
            }
        }
    }

    public static class ExecuteJdbcCommit implements AppUnderTest, RootSpanMarker {
        private Connection connection;
        public void executeApp() throws Exception {
            connection = createConnection();
            connection.setAutoCommit(false);
            try {
                rootSpanMarker();
            } finally {
                connection.close();
            }
        }
        public void rootSpanMarker() throws Exception {
            Statement statement = connection.createStatement();
            try {
                statement.execute("insert into employee (name) values ('john doe')");
            } finally {
                statement.close();
            }
            connection.commit();
        }
    }
}
