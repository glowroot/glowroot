/*
 * Copyright 2012-2015 the original author or authors.
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
package org.glowroot.storage.simplerepo.util;

import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Properties;

import javax.annotation.Nullable;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Joiner;
import com.google.common.base.MoreObjects;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;
import org.checkerframework.checker.tainting.qual.Untainted;
import org.h2.jdbc.JdbcConnection;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.glowroot.common.util.OnlyUsedByTests;
import org.glowroot.storage.simplerepo.util.ConnectionPool.ConnectionFactory;
import org.glowroot.storage.simplerepo.util.ConnectionPool.PreparedStatementCallback;
import org.glowroot.storage.simplerepo.util.ConnectionPool.StatementCallback;

public class DataSource {

    private static final boolean POSTGRES = false;
    private static final boolean SINGLE_SERVER = true;

    private static final Logger logger = LoggerFactory.getLogger(DataSource.class);

    private static final int CACHE_SIZE =
            Integer.getInteger("glowroot.internal.h2.cacheSize", 8192);

    private static final int QUERY_TIMEOUT_SECONDS =
            Integer.getInteger("glowroot.internal.h2.queryTimeout", 60);

    // null means use memDb
    private final @Nullable File dbFile;

    private final ConnectionPool connectionPool;

    // creates an in-memory database
    public DataSource() throws SQLException {
        dbFile = null;
        connectionPool = new ConnectionPool(new ConnectionFactoryImpl(null));
    }

    public DataSource(File dbFile) throws SQLException {
        this.dbFile = dbFile;
        connectionPool = new ConnectionPool(new ConnectionFactoryImpl(dbFile));
    }

    public Schema getSchema() {
        return new Schema(connectionPool, POSTGRES);
    }

    public void defrag() throws Exception {
        if (dbFile == null || POSTGRES) {
            return;
        }
        debug("shutdown defrag");
        connectionPool.executeAndReleaseAll(new StatementCallback() {
            @Override
            public void doWithStatement(Statement statement) throws SQLException {
                statement.execute("shutdown defrag");
            }
        });
    }

    public void execute(final @Untainted String sql) throws Exception {
        debug(sql);
        connectionPool.execute(new StatementCallback() {
            @Override
            public void doWithStatement(Statement statement) throws SQLException {
                statement.execute(sql);
            }
        });
    }

    public long queryForLong(final @Untainted String sql, Object... args) throws Exception {
        Long value = query(sql, new ResultSetExtractor<Long>() {
            @Override
            public Long extractData(ResultSet resultSet) throws SQLException {
                if (resultSet.next()) {
                    return resultSet.getLong(1);
                } else {
                    logger.warn("query didn't return any results: {}", sql);
                    return 0L;
                }
            }
        }, args);
        return MoreObjects.firstNonNull(value, 0L);
    }

    public boolean queryForExists(final @Untainted String sql, Object... args) throws Exception {
        Boolean exists = query(sql, new ResultSetExtractor<Boolean>() {
            @Override
            public Boolean extractData(ResultSet resultSet) throws SQLException {
                return resultSet.next();
            }
        }, args);
        return MoreObjects.firstNonNull(exists, false);
    }

    public <T extends /*@NonNull*/Object> List<T> query(final @Untainted String sql,
            final RowMapper<T> rowMapper, final Object... args) throws Exception {
        List<T> list = query(sql, new ResultSetExtractor<List<T>>() {
            @Override
            public List<T> extractData(ResultSet resultSet) throws SQLException {
                return mapRows(resultSet, rowMapper);
            }
        }, args);
        return list == null ? ImmutableList.<T>of() : list;
    }

    public <T> /*@Nullable*/T query(final @Untainted String sql, final ResultSetExtractor<T> rse,
            final Object... args) throws Exception {
        debug(sql, args);
        return connectionPool.execute(sql, new PreparedStatementCallback</*@Nullable*/T>() {
            @Override
            public T doWithPreparedStatement(PreparedStatement preparedStatement)
                    throws SQLException {
                preparedStatement.setQueryTimeout(QUERY_TIMEOUT_SECONDS);
                for (int i = 0; i < args.length; i++) {
                    preparedStatement.setObject(i + 1, args[i]);
                }
                debug(sql, args);
                ResultSet resultSet = preparedStatement.executeQuery();
                ResultSetCloser closer = new ResultSetCloser(resultSet);
                try {
                    return rse.extractData(resultSet);
                } catch (Throwable t) {
                    throw closer.rethrow(t);
                } finally {
                    closer.close();
                }
            }
        }, null);
    }

    public int update(final @Untainted String sql, final @Nullable Object... args)
            throws Exception {
        debug(sql, args);
        return connectionPool.execute(sql, new PreparedStatementCallback<Integer>() {
            @Override
            public Integer doWithPreparedStatement(PreparedStatement preparedStatement)
                    throws SQLException {
                preparedStatement.setQueryTimeout(0);
                for (int i = 0; i < args.length; i++) {
                    preparedStatement.setObject(i + 1, args[i]);
                }
                return preparedStatement.executeUpdate();
            }
        }, 0);
    }

    public int update(final @Untainted String sql, final PreparedStatementBinder binder)
            throws Exception {
        debug(sql);
        return connectionPool.execute(sql, new PreparedStatementCallback<Integer>() {
            @Override
            public Integer doWithPreparedStatement(PreparedStatement preparedStatement)
                    throws Exception {
                preparedStatement.setQueryTimeout(0);
                binder.bind(preparedStatement);
                return preparedStatement.executeUpdate();
            }
        }, 0);
    }

    public void batchUpdate(final @Untainted String sql, final PreparedStatementBinder binder)
            throws Exception {
        debug(sql);
        connectionPool.execute(sql, new PreparedStatementCallback</*@Nullable*/Void>() {
            @Override
            public @Nullable Void doWithPreparedStatement(PreparedStatement preparedStatement)
                    throws Exception {
                preparedStatement.setQueryTimeout(0);
                binder.bind(preparedStatement);
                preparedStatement.executeBatch();
                return null;
            }
        }, null);
    }

    public void deleteAll(@Untainted String tableName, @Untainted String columnName,
            String serverGroup) throws Exception {
        if (SINGLE_SERVER) {
            execute("truncate table " + tableName);
        } else {
            batchDelete(tableName, columnName + " = ?", serverGroup);
        }
    }

    public void deleteBefore(@Untainted String tableName, @Untainted String columnName,
            String columnValue, long captureTime) throws Exception {
        batchDelete(tableName, columnName + " = ? and capture_time < ?", columnValue, captureTime);
    }

    public void batchDelete(@Untainted String tableName, @Untainted String whereClause,
            Object... args) throws Exception {
        // delete 100 at a time, which is both faster than deleting all at once, and doesn't
        // lock the single jdbc connection for one large chunk of time
        int deleted;
        do {
            if (POSTGRES) {
                deleted = update(
                        "delete from " + tableName + " where ctid = any(array(select ctid from "
                                + tableName + " where " + whereClause + " limit 100))",
                        args);
            } else {
                deleted = update(
                        "delete from " + tableName + " where " + whereClause + " limit 100", args);
            }
        } while (deleted != 0);
    }

    long getDbFileSize() {
        return dbFile == null ? 0 : dbFile.length();
    }

    @OnlyUsedByTests
    public void close() throws SQLException {
        connectionPool.close();
    }

    private static Connection createConnection(@Nullable File dbFile) throws SQLException {
        try {
            Class.forName("org.h2.Driver");
        } catch (ClassNotFoundException e) {
            throw new SQLException(e);
        }
        if (dbFile == null) {
            // db_close_on_exit=false since jvm shutdown hook is handled by DataSource
            return new JdbcConnection("jdbc:h2:mem:;compress=true;db_close_on_exit=false",
                    new Properties());
        } else {
            String dbPath = dbFile.getPath();
            dbPath = dbPath.replaceFirst(".h2.db$", "");
            Properties props = new Properties();
            props.setProperty("user", "sa");
            props.setProperty("password", "");
            // db_close_on_exit=false since jvm shutdown hook is handled by DataSource
            String url = "jdbc:h2:" + dbPath + ";compress=true;db_close_on_exit=false;cache_size="
                    + CACHE_SIZE;
            return new JdbcConnection(url, props);
        }
    }

    private static <T extends /*@NonNull*/Object> List<T> mapRows(ResultSet resultSet,
            RowMapper<T> rowMapper) throws SQLException {
        List<T> mappedRows = Lists.newArrayList();
        boolean errorLogged = false;
        while (resultSet.next()) {
            try {
                mappedRows.add(rowMapper.mapRow(resultSet));
            } catch (Exception e) {
                // this can happen when copying h2 database while glowroot is running, and
                // h2 database is corrupted sometimes, then running h2 recover tool, and
                // then still see error when running java -jar glowroot.jar,
                // e.g. "Missing lob entry, block: 504196"
                if (!errorLogged) {
                    // just log the first error
                    logger.error(e.getMessage(), e);
                    errorLogged = true;
                }
            }
        }
        return ImmutableList.copyOf(mappedRows);
    }

    private static void debug(String sql, @Nullable Object... args) {
        debug(logger, sql, args);
    }

    @VisibleForTesting
    static void debug(Logger logger, String sql, @Nullable Object... args) {
        if (!logger.isDebugEnabled()) {
            return;
        }
        if (args.length == 0) {
            logger.debug(sql);
            return;
        }
        List<String> argStrings = Lists.newArrayList();
        for (Object arg : args) {
            if (arg instanceof String) {
                argStrings.add('\'' + (String) arg + '\'');
            } else if (arg == null) {
                argStrings.add("NULL");
            } else {
                argStrings.add(arg.toString());
            }
        }
        logger.debug("{} [{}]", sql, Joiner.on(", ").join(argStrings));
    }

    private static class ConnectionFactoryImpl implements ConnectionFactory {

        private final @Nullable File dbFile;

        private ConnectionFactoryImpl(@Nullable File dbFile) {
            this.dbFile = dbFile;
        }

        @Override
        public Connection createConnection() throws SQLException {
            if (POSTGRES) {
                try {
                    Class.forName("org.postgresql.Driver");
                } catch (ClassNotFoundException e) {
                    throw new SQLException(e);
                }
                return DriverManager.getConnection("jdbc:postgresql://localhost/glowroot",
                        "glowroot", "glowroot");
            } else {
                return DataSource.createConnection(dbFile);
            }
        }
    }

    public interface PreparedStatementBinder {
        void bind(PreparedStatement preparedStatement) throws Exception;
    }

    public interface RowMapper<T> {
        T mapRow(ResultSet resultSet) throws Exception;
    }

    public interface ResultSetExtractor<T extends /*@Nullable*/Object> {
        T extractData(ResultSet resultSet) throws Exception;
    }
}
