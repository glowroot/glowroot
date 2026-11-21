/*
 * Copyright 2026 the original author or authors.
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
package org.glowroot.microbenchmarks;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.concurrent.TimeUnit;

import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;

@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Fork(jvmArgsAppend = "-Xmx256m")
@State(Scope.Thread)
public class JdbcMemoryPressureBenchmark {

    @Param({"0", "262144", "2097152"})
    private int allocBytes;

    private Connection connection;
    private PreparedStatement preparedStatement;

    @Setup
    public void setup() throws SQLException {
        connection = DriverManager.getConnection("jdbc:hsqldb:mem:jdbc_mem_pressure", "sa", "");
        Statement statement = connection.createStatement();
        try {
            statement.execute("create table mock (name varchar(100))");
            for (int i = 0; i < 1000; i++) {
                statement.execute("insert into mock (name) values ('mock" + i + "')");
            }
        } finally {
            statement.close();
        }
        preparedStatement = connection.prepareStatement("select * from mock");
    }

    @TearDown
    public void tearDown() throws SQLException {
        preparedStatement.close();
        connection.close();
    }

    @Benchmark
    public int execute() throws Exception {
        return queryUnderTransaction();
    }

    // woven via META-INF/glowroot.plugin.json capturePoints
    public int queryUnderTransaction() throws SQLException {
        byte[] pressure = allocBytes == 0 ? null : new byte[allocBytes];
        int rows = 0;
        ResultSet resultSet = preparedStatement.executeQuery();
        try {
            while (resultSet.next()) {
                rows++;
            }
        } finally {
            resultSet.close();
        }
        return rows + (pressure == null ? 0 : pressure.length);
    }
}
