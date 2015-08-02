/*
 * Copyright 2014-2015 the original author or authors.
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
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.concurrent.TimeUnit;

import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OperationsPerInvocation;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;

import org.glowroot.plugin.api.Agent;
import org.glowroot.plugin.api.transaction.MessageSupplier;
import org.glowroot.plugin.api.transaction.TimerName;
import org.glowroot.plugin.api.transaction.TraceEntry;
import org.glowroot.plugin.api.transaction.TransactionService;
import org.glowroot.plugin.api.weaving.Pointcut;
import org.glowroot.plugin.jdbc.support.MockConnection;

@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@State(Scope.Thread)
public class ResultSetBenchmark {

    private static final TransactionService transactionService = Agent.getTransactionService();

    private static final TimerName timerName =
            transactionService.getTimerName(OnlyForTheTimerName.class);

    @Param
    private Database database;

    private Connection connection;
    private PreparedStatement preparedStatement;

    @Setup
    public void setup() throws SQLException {
        switch (database) {
            case HSQLDB:
                connection = DriverManager.getConnection("jdbc:hsqldb:mem:benchmark", "sa", "");
                Statement statement = connection.createStatement();
                try {
                    statement.execute("create table mock (name varchar(100))");
                    for (int i = 0; i < 10000; i++) {
                        statement.execute("insert into mock (name) values ('mock" + 1 + "')");
                    }
                } finally {
                    statement.close();
                }
                break;
            case MOCK:
                connection = new MockConnection();
                break;
        }
        preparedStatement = connection.prepareStatement("select * from mock");
    }

    @TearDown
    public void tearDown() throws SQLException {
        preparedStatement.close();
        connection.close();
    }

    @Benchmark
    @OperationsPerInvocation(10000)
    public void next() throws Exception {
        TraceEntry traceEntry = transactionService.startTransaction("Microbenchmark",
                "micro transaction", MessageSupplier.from("micro transaction"), timerName);
        ResultSet resultSet = preparedStatement.executeQuery();
        for (int i = 0; i < 10000; i++) {
            resultSet.next();
        }
        resultSet.close();
        traceEntry.end();
    }

    public static enum Database {
        HSQLDB, MOCK
    }

    @Pointcut(className = "dummy", methodName = "dummy", methodParameterTypes = {},
            timerName = "micro transaction")
    private static class OnlyForTheTimerName {}
}
