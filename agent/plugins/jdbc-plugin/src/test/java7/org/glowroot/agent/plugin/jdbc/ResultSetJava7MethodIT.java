/*
 * Copyright 2019 the original author or authors.
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
package org.glowroot.agent.plugin.jdbc;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Iterator;
import java.util.List;

import org.junit.After;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import org.glowroot.agent.it.harness.AppUnderTest;
import org.glowroot.agent.it.harness.Container;
import org.glowroot.agent.it.harness.Containers;
import org.glowroot.agent.it.harness.TransactionMarker;
import org.glowroot.wire.api.model.AggregateOuterClass.Aggregate;
import org.glowroot.wire.api.model.TraceOuterClass.Trace;

import static org.assertj.core.api.Assertions.assertThat;

public class ResultSetJava7MethodIT {

    private static Container container;

    @BeforeClass
    public static void setUp() throws Exception {
        container = Containers.create();
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
    public void testResultSetJava7Method() throws Exception {
        // when
        Trace trace =
                container.execute(ExecuteStatementAndIterateOverResultsUsingJava7Method.class);

        // then
        assertThat(trace.getHeader().hasError()).isFalse();

        Iterator<Trace.Entry> i = trace.getEntryList().iterator();
        List<Trace.SharedQueryText> sharedQueryTexts = trace.getSharedQueryTextList();

        Trace.Entry entry = i.next();
        assertThat(entry.getDepth()).isEqualTo(0);
        assertThat(entry.getMessage()).isEmpty();
        assertThat(sharedQueryTexts.get(entry.getQueryEntryMessage().getSharedQueryTextIndex())
                .getFullText()).isEqualTo("select * from employee");
        assertThat(entry.getQueryEntryMessage().getPrefix()).isEqualTo("jdbc query: ");
        assertThat(entry.getQueryEntryMessage().getSuffix()).isEqualTo(" => 1 row");

        assertThat(i.hasNext()).isFalse();

        Iterator<Aggregate.Query> j = trace.getQueryList().iterator();

        Aggregate.Query query = j.next();
        assertThat(query.getType()).isEqualTo("SQL");
        assertThat(sharedQueryTexts.get(query.getSharedQueryTextIndex()).getFullText())
                .isEqualTo("select * from employee");
        assertThat(query.getExecutionCount()).isEqualTo(1);
        assertThat(query.getTotalRows().getValue()).isEqualTo(1);

        assertThat(j.hasNext()).isFalse();
    }

    public static class ExecuteStatementAndIterateOverResultsUsingJava7Method
            implements AppUnderTest, TransactionMarker {
        private Connection connection;
        @Override
        public void executeApp() throws Exception {
            connection = Connections.createCommonsDbcpWrappedConnection();
            try {
                transactionMarker();
            } finally {
                Connections.closeConnection(connection);
            }
        }
        @Override
        public void transactionMarker() throws Exception {
            Statement statement = connection.createStatement();
            try {
                statement.execute("select * from employee");
                ResultSet rs = statement.getResultSet();
                rs.next();
                // this method introduced in Java 7, so it should throw AbstractMethodError when
                // executed against org.apache.commons.dbcp.DelegatingResultSet
                try {
                    rs.getObject(1, String.class);
                } catch (AbstractMethodError e) {
                    // this is expected
                    return;
                }
                // this is not expected
                throw new AssertionError("org.apache.commons.dbcp.DelegatingResultSet"
                        + ".getObject(int, Class) did not throw AbstractMethodError");
            } finally {
                statement.close();
            }
        }
    }
}
