/**
 * Copyright 2011 the original author or authors.
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
import org.informantproject.testkit.GetTracesResponse.Span;
import org.informantproject.testkit.GetTracesResponse.Trace;
import org.informantproject.testkit.InformantContainer;
import org.informantproject.testkit.MockEntryPoint;
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

    private static InformantContainer container;

    @BeforeClass
    public static void setUp() throws Exception {
        container = InformantContainer.newInstance();
    }

    @AfterClass
    public static void tearDown() throws Exception {
        container.close();
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
        Span mockSpan = trace.getSpans().get(0);
        assertThat(mockSpan.getDescription(), is("mock"));
        Span jdbcSpan = trace.getSpans().get(1);
        assertThat(jdbcSpan.getDescription(),
                is("jdbc execution: select * from employee => 1 row"));
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

    public static class ExecuteJdbcSelectAndIterateOverResults implements AppUnderTest {
        public void execute() throws Exception {
            new File("test.h2.db").delete();
            JdbcDataSource dataSource = new JdbcDataSource();
            dataSource.setURL("jdbc:h2:test");
            dataSource.setUser("sa");
            Connection connection = dataSource.getConnection();
            try {
                setUp(connection);
                try {
                    execute(connection);
                } finally {
                    tearDown(connection);
                }
            } finally {
                connection.close();
            }
        }
        private static void setUp(Connection connection) throws SQLException {
            Statement statement = connection.createStatement();
            try {
                statement.execute("create table employee (name varchar(100))");
                statement.execute("insert into employee (name) values ('john doe')");
            } finally {
                statement.close();
            }
        }
        private static void execute(final Connection connection) throws Exception {
            MockEntryPoint mockEntryPoint = new MockEntryPoint() {
                public void run() throws Exception {
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
            };
            mockEntryPoint.run();
        }
        private static void tearDown(Connection connection) throws SQLException {
            Statement statement;
            statement = connection.createStatement();
            try {
                statement.execute("drop table employee");
            } finally {
                statement.close();
            }
        }
    }
}
