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
package org.glowroot.agent.stress;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.locks.LockSupport;

final class Workloads {

    private static final String JDBC_URL = "jdbc:hsqldb:mem:glowroot_stress";
    // Pad length (chars) embedded in captured SQL so Glowroot's embedded H2 (compress=true)
    // stores large full-query-text rows. Entropy per op matters: a static run of 'x' compresses
    // away in data.mv.db. The mock DB below is HSQLDB in-memory — not that H2 file.
    private static final int PAD_CHARS = 64 * 1024;

    private final Connection bootstrap;
    private final ThreadLocal<Connection> connections = new ThreadLocal<Connection>() {
        @Override
        protected Connection initialValue() {
            try {
                return DriverManager.getConnection(JDBC_URL, "sa", "");
            } catch (SQLException e) {
                throw new IllegalStateException(e);
            }
        }
    };
    private final ThreadLocal<char[]> padBuffers = new ThreadLocal<char[]>() {
        @Override
        protected char[] initialValue() {
            return new char[PAD_CHARS];
        }
    };

    Workloads() throws SQLException {
        bootstrap = DriverManager.getConnection(JDBC_URL, "sa", "");
        Statement statement = bootstrap.createStatement();
        try {
            statement.execute("create table stress_mock (id int, name varchar(100))");
            for (int i = 0; i < 500; i++) {
                statement.execute("insert into stress_mock (id, name) values (" + i + ", 'n" + i
                        + "')");
            }
        } finally {
            statement.close();
        }
    }

    void close() throws SQLException {
        bootstrap.close();
    }

    private static final String FIXED_SQL = "select id, name from stress_mock where id >= 0";

    // woven via glowroot.plugin.json — steady dual load (no new full-query-text growth)
    int jdbcOnce(int allocKb) throws SQLException {
        byte[] pressure = allocKb <= 0 ? null : new byte[allocKb * 1024];
        PreparedStatement select = connections.get().prepareStatement(FIXED_SQL);
        int rows = 0;
        try {
            ResultSet rs = select.executeQuery();
            try {
                while (rs.next()) {
                    rows++;
                }
            } finally {
                rs.close();
            }
        } finally {
            select.close();
        }
        return rows + (pressure == null ? 0 : pressure.length);
    }

    private String uniqueSqlPad(long uniqueSalt) {
        char[] buf = padBuffers.get();
        ThreadLocalRandom rnd = ThreadLocalRandom.current();
        // Printable ASCII — high entropy so H2 page compression cannot collapse the LOB.
        for (int i = 0; i < buf.length; i++) {
            buf[i] = (char) (33 + rnd.nextInt(94));
        }
        return "pad-" + uniqueSalt + "-" + new String(buf);
    }

    private int runUniqueJdbc(int allocKb, long uniqueSalt) throws SQLException {
        byte[] pressure = allocKb <= 0 ? null : new byte[allocKb * 1024];
        String pad = uniqueSqlPad(uniqueSalt);
        // Comment body carries cardinality; keep the executable SQL tiny for the in-memory mock DB.
        String sql = "select id, name from stress_mock where id >= 0 /*" + pad + "*/";
        PreparedStatement uniqueSelect = connections.get().prepareStatement(sql);
        int rows = 0;
        try {
            ResultSet rs = uniqueSelect.executeQuery();
            try {
                while (rs.next()) {
                    rows++;
                }
            } finally {
                rs.close();
            }
        } finally {
            uniqueSelect.close();
        }
        // Pace unique heavy pads so aggregate flush/H2 write can keep up (avoids OOM backlog).
        LockSupport.parkNanos(2_000_000L);
        return rows + (pressure == null ? 0 : pressure.length);
    }

    // woven via glowroot.plugin.json
    int jdbcOnceUnique(int allocKb, long uniqueSalt) throws SQLException {
        return runUniqueJdbc(allocKb, uniqueSalt);
    }

    // woven via glowroot.plugin.json
    int jdbcOnceA(int allocKb, long uniqueSalt) throws SQLException {
        return runUniqueJdbc(allocKb, uniqueSalt);
    }

    // woven via glowroot.plugin.json
    int jdbcOnceB(int allocKb, long uniqueSalt) throws SQLException {
        return runUniqueJdbc(allocKb, uniqueSalt);
    }

    // woven via glowroot.plugin.json
    int jdbcOnceC(int allocKb, long uniqueSalt) throws SQLException {
        return runUniqueJdbc(allocKb, uniqueSalt);
    }

    // woven via glowroot.plugin.json
    int churnOnce(int allocKb) {
        byte[] pressure = allocKb <= 0 ? null : new byte[allocKb * 1024];
        return pressure == null ? 0 : pressure.length;
    }

    // woven via glowroot.plugin.json — holds an open transaction until release
    static void retainOnce(CountDownLatch release, CountDownLatch started)
            throws InterruptedException {
        started.countDown();
        release.await();
    }
}
