/*
 * Copyright 2017 the original author or authors.
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
package org.glowroot.agent.sql;

import java.io.PrintStream;
import java.io.PrintWriter;
import java.sql.Connection;
import java.sql.Driver;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.Collections;
import java.util.Enumeration;
import java.util.Properties;

import javax.annotation.Nullable;

public class DriverManager {

    private static final Driver driver = new org.h2.Driver();

    private static volatile int loginTimeout;
    private static volatile @Nullable PrintWriter logWriter;
    private static volatile @Nullable PrintStream logStream;

    private DriverManager() {}

    public static Connection getConnection(String url) throws SQLException {
        return getConnection(url, new Properties());
    }

    public static Connection getConnection(String url, String user, String password)
            throws SQLException {
        Properties info = new Properties();
        info.put("user", user);
        info.put("password", password);
        return getConnection(url, info);
    }

    public static Connection getConnection(String url, Properties info) throws SQLException {
        return driver.connect(url, info);
    }

    public static Driver getDriver(@SuppressWarnings("unused") String url) {
        return driver;
    }

    public static void registerDriver(@SuppressWarnings("unused") Driver driver)
            throws SQLException {}

    public static void deregisterDriver(@SuppressWarnings("unused") Driver driver) {}

    public static Enumeration<Driver> getDrivers() {
        return Collections.enumeration(Arrays.asList(driver));
    }

    public static int getLoginTimeout() {
        return loginTimeout;
    }

    public static void setLoginTimeout(int loginTimeout) {
        DriverManager.loginTimeout = loginTimeout;
    }

    public static @Nullable PrintWriter getLogWriter() {
        return logWriter;
    }

    public static void setLogWriter(@Nullable PrintWriter logWriter) {
        DriverManager.logWriter = logWriter;
    }

    public static void setLogStream(@Nullable PrintStream logStream) {
        DriverManager.logStream = logStream;
    }

    public static @Nullable PrintStream getLogStream() {
        return logStream;
    }

    public static void println(@SuppressWarnings("unused") String message) {}
}
