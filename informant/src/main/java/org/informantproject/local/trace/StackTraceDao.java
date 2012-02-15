/**
 * Copyright 2012 the original author or authors.
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
package org.informantproject.local.trace;

import java.io.IOException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.informantproject.util.JdbcUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.base.Charsets;
import com.google.common.hash.Hashing;
import com.google.common.io.CharStreams;
import com.google.gson.stream.JsonWriter;
import com.google.inject.Inject;
import com.google.inject.Singleton;

/**
 * Data access object for storing and reading stack traces from the embedded H2 database.
 * 
 * @author Trask Stalnaker
 * @since 0.5
 */
@Singleton
public class StackTraceDao {

    private static final Logger logger = LoggerFactory.getLogger(StackTraceDao.class);

    private final Connection connection;

    private final PreparedStatement mergePreparedStatement;
    private final PreparedStatement selectPreparedStatement;

    private final boolean valid;

    private final Map<String, Boolean> storedHashes = new ConcurrentHashMap<String, Boolean>();

    @Inject
    StackTraceDao(Connection connection) {
        this.connection = connection;
        PreparedStatement mergePS = null;
        PreparedStatement selectPS = null;
        boolean errorOnInit = false;
        try {
            if (!JdbcUtil.tableExists("stacktrace", connection)) {
                createTable(connection);
            }
            mergePS = connection.prepareStatement("merge into stacktrace (hash, stacktrace) values"
                    + " (?, ?)");
            selectPS = connection.prepareStatement("select stacktrace from stacktrace where"
                    + " hash = ?");
        } catch (SQLException e) {
            errorOnInit = true;
            logger.error(e.getMessage(), e);
        }
        mergePreparedStatement = mergePS;
        selectPreparedStatement = selectPS;
        this.valid = !errorOnInit;
    }

    String storeStackTrace(StackTraceElement[] stackTraceElements) {
        logger.debug("storeStackTrace()");
        if (!valid) {
            return null;
        }
        String json;
        try {
            json = toJson(stackTraceElements);
        } catch (IOException e) {
            logger.error(e.getMessage(), e);
            return null;
        }
        String hex = Hashing.sha1().hashString(json, Charsets.UTF_8).toString();
        if (storedHashes.containsKey(hex)) {
            return hex;
        }
        synchronized (connection) {
            try {
                // TODO optimize with local cache
                mergePreparedStatement.setString(1, hex);
                mergePreparedStatement.setString(2, json);
                mergePreparedStatement.executeUpdate();
                storedHashes.put(hex, Boolean.TRUE);
                return hex;
            } catch (SQLException e) {
                logger.error(e.getMessage(), e);
                return null;
            }
        }
    }

    public String readStackTrace(String hash) {
        logger.debug("readStackTrace(): hash={}", hash);
        if (!valid) {
            return null;
        }
        synchronized (connection) {
            try {
                selectPreparedStatement.setString(1, hash);
                ResultSet resultSet = selectPreparedStatement.executeQuery();
                try {
                    if (resultSet.next()) {
                        return resultSet.getString(1);
                    } else {
                        return null;
                    }
                } finally {
                    resultSet.close();
                }
            } catch (SQLException e) {
                logger.error(e.getMessage(), e);
                return null;
            }
        }
    }

    private static void createTable(Connection connection) throws SQLException {
        Statement statement = connection.createStatement();
        try {
            statement.execute("create table stacktrace (hash varchar primary key, stacktrace"
                    + " clob)");
        } finally {
            statement.close();
        }
    }

    private static String toJson(StackTraceElement[] stackTraceElements) throws IOException {
        StringBuilder sb = new StringBuilder();
        JsonWriter jw = new JsonWriter(CharStreams.asWriter(sb));
        jw.beginArray();
        for (StackTraceElement stackTraceElement : stackTraceElements) {
            jw.value(stackTraceElement.toString());
        }
        jw.endArray();
        jw.close();
        return sb.toString();
    }
}
