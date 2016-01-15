/*
 * Copyright 2012-2016 the original author or authors.
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
package org.glowroot.agent.fat.storage.util;

import java.io.File;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.ExecutionException;

import javax.annotation.Nullable;
import javax.annotation.concurrent.GuardedBy;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Joiner;
import com.google.common.base.Throwables;
import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;
import org.checkerframework.checker.tainting.qual.Untainted;
import org.h2.jdbc.JdbcConnection;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.glowroot.agent.fat.storage.util.Schemas.Column;
import org.glowroot.agent.fat.storage.util.Schemas.Index;
import org.glowroot.common.util.OnlyUsedByTests;

import static com.google.common.base.Preconditions.checkNotNull;

public class DataSource {

    private static final Logger logger = LoggerFactory.getLogger(DataSource.class);

    private static final int CACHE_SIZE =
            Integer.getInteger("glowroot.internal.h2.cacheSize", 8192);

    // null means use memDb
    private final @Nullable File dbFile;
    private final Thread shutdownHookThread;
    private final Object lock = new Object();
    @GuardedBy("lock")
    private Connection connection;
    private volatile int queryTimeoutSeconds;
    private volatile boolean closing = false;

    private final LoadingCache</*@Untainted*/ String, PreparedStatement> preparedStatementCache =
            CacheBuilder.newBuilder().weakValues()
                    .build(new CacheLoader</*@Untainted*/String, PreparedStatement>() {
                        @Override
                        public PreparedStatement load(@Untainted String sql) throws SQLException {
                            return connection.prepareStatement(sql);
                        }
                    });

    // creates an in-memory database
    public DataSource() throws SQLException {
        dbFile = null;
        connection = createConnection(null);
        shutdownHookThread = new ShutdownHookThread();
        Runtime.getRuntime().addShutdownHook(shutdownHookThread);
    }

    public DataSource(File dbFile) throws SQLException {
        this.dbFile = dbFile;
        connection = createConnection(dbFile);
        shutdownHookThread = new ShutdownHookThread();
        Runtime.getRuntime().addShutdownHook(shutdownHookThread);
    }

    void setQueryTimeoutSeconds(Integer queryTimeoutSeconds) {
        this.queryTimeoutSeconds = queryTimeoutSeconds;
    }

    public void defrag() throws SQLException {
        if (dbFile == null) {
            return;
        }
        synchronized (lock) {
            if (closing) {
                return;
            }
            execute("shutdown defrag");
            preparedStatementCache.invalidateAll();
            connection = createConnection(dbFile);
        }
    }

    public void execute(@Untainted String sql) throws SQLException {
        debug(sql);
        synchronized (lock) {
            if (closing) {
                return;
            }
            Statement statement = connection.createStatement();
            StatementCloser closer = new StatementCloser(statement);
            try {
                statement.execute(sql);
            } catch (Throwable t) {
                throw closer.rethrow(t);
            } finally {
                closer.close();
            }
        }
    }

    // warning: this method returns 0 when data source is closing
    public long queryForLong(final @Untainted String sql, Object... args) throws SQLException {
        Long value = queryForOptionalLong(sql, args);
        return value == null ? 0L : value;
    }

    public @Nullable Long queryForOptionalLong(final @Untainted String sql, Object... args)
            throws SQLException {
        debug(sql, args);
        synchronized (lock) {
            if (closing) {
                return null;
            }
            return queryUnderLock(sql, args, new ResultSetExtractor</*@Nullable*/ Long>() {
                @Override
                public @Nullable Long extractData(ResultSet resultSet) throws SQLException {
                    if (!resultSet.next()) {
                        return null;
                    }
                    long val = resultSet.getLong(1);
                    Long value = resultSet.wasNull() ? null : val;
                    if (resultSet.next()) {
                        logger.warn("more than one row returned: {}", sql);
                    }
                    return value;
                }
            });
        }
    }

    public boolean queryForExists(final @Untainted String sql, Object... args) throws SQLException {
        debug(sql, args);
        synchronized (lock) {
            if (closing) {
                return false;
            }
            return queryUnderLock(sql, args, new ResultSetExtractor<Boolean>() {
                @Override
                public Boolean extractData(ResultSet resultSet) throws SQLException {
                    return resultSet.next();
                }
            });
        }
    }

    public List<String> queryForStringList(final @Untainted String sql) throws SQLException {
        return query(new JdbcRowQuery<String>() {
            @Override
            public @Untainted String getSql() {
                return sql;
            }
            @Override
            public void bind(PreparedStatement preparedStatement) {}
            @Override
            public String mapRow(ResultSet resultSet) throws SQLException {
                return checkNotNull(resultSet.getString(1));
            }
        });
    }

    public <T> T query(JdbcQuery<T> jdbcQuery) throws Exception {
        synchronized (lock) {
            if (closing) {
                return jdbcQuery.valueIfDataSourceClosing();
            }
            PreparedStatement preparedStatement = prepareStatement(jdbcQuery.getSql());
            // setQueryTimeout() affects all statements of this connection (at least with h2)
            preparedStatement.setQueryTimeout(queryTimeoutSeconds);
            jdbcQuery.bind(preparedStatement);
            ResultSet resultSet = preparedStatement.executeQuery();
            ResultSetCloser closer = new ResultSetCloser(resultSet);
            try {
                return jdbcQuery.processResultSet(resultSet);
            } catch (Throwable t) {
                throw closer.rethrow(t);
            } finally {
                closer.close();
            }
            // don't need to close statement since they are all cached and used under lock
        }
    }

    public <T extends /*@NonNull*/ Object> /*@Nullable*/ T queryAtMostOne(JdbcRowQuery<T> jdbcQuery)
            throws SQLException {
        List<T> list = query(jdbcQuery);
        if (list.isEmpty()) {
            return null;
        }
        if (list.size() > 1) {
            logger.warn("more than one row returned: {}", jdbcQuery.getSql());
        }
        return list.get(0);
    }

    public <T extends /*@NonNull*/ Object> List<T> query(JdbcRowQuery<T> jdbcQuery)
            throws SQLException {
        synchronized (lock) {
            if (closing) {
                return ImmutableList.of();
            }
            PreparedStatement preparedStatement = prepareStatement(jdbcQuery.getSql());
            // setQueryTimeout() affects all statements of this connection (at least with h2)
            preparedStatement.setQueryTimeout(queryTimeoutSeconds);
            jdbcQuery.bind(preparedStatement);
            ResultSet resultSet = preparedStatement.executeQuery();
            ResultSetCloser closer = new ResultSetCloser(resultSet);
            try {
                List<T> mappedRows = Lists.newArrayList();
                while (resultSet.next()) {
                    mappedRows.add(jdbcQuery.mapRow(resultSet));
                }
                return ImmutableList.copyOf(mappedRows);
            } catch (Throwable t) {
                throw closer.rethrow(t);
            } finally {
                closer.close();
            }
            // don't need to close statement since they are all cached and used under lock
        }
    }

    public int update(final @Untainted String sql, final @Nullable Object... args)
            throws SQLException {
        return update(new JdbcUpdate() {
            @Override
            public @Untainted String getSql() {
                return sql;
            }
            @Override
            public void bind(PreparedStatement preparedStatement) throws SQLException {
                for (int i = 0; i < args.length; i++) {
                    preparedStatement.setObject(i + 1, args[i]);
                }
            }
        });
    }

    public int update(JdbcUpdate jdbcUpdate) throws SQLException {
        if (closing) {
            // this can get called a lot inserting traces, and these can get backlogged
            // on the lock below during jvm shutdown without pre-checking here (and backlogging
            // ends up generating warning messages from
            // TransactionCollectorImpl.logPendingLimitWarning())
            return 0;
        }
        synchronized (lock) {
            if (closing) {
                return 0;
            }
            PreparedStatement preparedStatement = prepareStatement(jdbcUpdate.getSql());
            jdbcUpdate.bind(preparedStatement);
            // setQueryTimeout() affects all statements of this connection (at least with h2)
            preparedStatement.setQueryTimeout(0);
            return preparedStatement.executeUpdate();
            // don't need to close statement since they are all cached and used under lock
        }
    }

    public int[] batchUpdate(JdbcUpdate jdbcUpdate) throws Exception {
        if (closing) {
            // this can get called a lot inserting traces, and these can get backlogged
            // on the lock below during jvm shutdown without pre-checking here (and backlogging
            // ends up generating warning messages from
            // TransactionCollectorImpl.logPendingLimitWarning())
            return new int[0];
        }
        synchronized (lock) {
            if (closing) {
                return new int[0];
            }
            PreparedStatement preparedStatement = prepareStatement(jdbcUpdate.getSql());
            jdbcUpdate.bind(preparedStatement);
            // setQueryTimeout() affects all statements of this connection (at least with h2)
            preparedStatement.setQueryTimeout(0);
            return preparedStatement.executeBatch();
            // don't need to close statement since they are all cached and used under lock
        }
    }

    public void deleteBefore(@Untainted String tableName, long captureTime) throws SQLException {
        // delete 100 at a time, which is both faster than deleting all at once, and doesn't
        // lock the single jdbc connection for one large chunk of time
        int deleted;
        do {
            deleted = update("delete from " + tableName + " where capture_time < ? limit 100",
                    captureTime);
        } while (deleted > 0);
    }

    public void syncTable(@Untainted String tableName, List<Column> columns) throws SQLException {
        synchronized (lock) {
            if (closing) {
                return;
            }
            Schemas.syncTable(tableName, columns, connection);
        }
    }

    public void syncIndexes(@Untainted String tableName, ImmutableList<Index> indexes)
            throws SQLException {
        synchronized (lock) {
            if (closing) {
                return;
            }
            Schemas.syncIndexes(tableName, indexes, connection);
        }
    }

    public boolean tableExists(String tableName) throws SQLException {
        synchronized (lock) {
            return !closing && Schemas.tableExists(tableName, connection);
        }
    }

    long getDbFileSize() {
        return dbFile == null ? 0 : dbFile.length();
    }

    // helpful for upgrading schema
    void renameTable(@Untainted String oldTableName, @Untainted String newTableName)
            throws SQLException {
        if (Schemas.tableExists(oldTableName, connection)) {
            execute("alter table " + oldTableName + " rename to " + newTableName);
        }
    }

    // helpful for upgrading schema
    void renameColumn(@Untainted String tableName, @Untainted String oldColumnName,
            @Untainted String newColumnName) throws SQLException {
        if (Schemas.columnExists(tableName, oldColumnName, connection)) {
            execute("alter table " + tableName + " alter column " + oldColumnName + " rename to "
                    + newColumnName);
        }
    }

    @OnlyUsedByTests
    public void close() throws SQLException {
        synchronized (lock) {
            if (closing) {
                return;
            }
            closing = true;
            connection.close();
        }
        Runtime.getRuntime().removeShutdownHook(shutdownHookThread);
    }

    // lock must be acquired prior to calling this method
    private <T extends /*@Nullable*/ Object> T queryUnderLock(@Untainted String sql, Object[] args,
            ResultSetExtractor<T> rse) throws SQLException {
        PreparedStatement preparedStatement = prepareStatement(sql);
        for (int i = 0; i < args.length; i++) {
            preparedStatement.setObject(i + 1, args[i]);
        }
        // setQueryTimeout() affects all statements of this connection (at least with h2)
        preparedStatement.setQueryTimeout(queryTimeoutSeconds);
        ResultSet resultSet = preparedStatement.executeQuery();
        ResultSetCloser closer = new ResultSetCloser(resultSet);
        try {
            return rse.extractData(resultSet);
        } catch (Throwable t) {
            throw closer.rethrow(t);
        } finally {
            closer.close();
        }
        // don't need to close statement since they are all cached and used under lock
    }

    private PreparedStatement prepareStatement(@Untainted String sql) throws SQLException {
        try {
            return preparedStatementCache.get(sql);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            Throwables.propagateIfPossible(cause, SQLException.class);
            // it should not really be possible to get here since the only checked exception that
            // preparedStatementCache's CacheLoader throws is SQLException
            logger.error(e.getMessage(), e);
            throw new SQLException(e);
        }
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

    public interface JdbcQuery<T> {
        @Untainted
        String getSql();
        void bind(PreparedStatement preparedStatement) throws Exception;
        T processResultSet(ResultSet resultSet) throws Exception;
        T valueIfDataSourceClosing();
    }

    public interface JdbcRowQuery<T> {
        @Untainted
        String getSql();
        void bind(PreparedStatement preparedStatement) throws SQLException;
        T mapRow(ResultSet resultSet) throws Exception;
    }

    public interface JdbcUpdate {
        @Untainted
        String getSql();
        void bind(PreparedStatement preparedStatement) throws SQLException;
    }

    interface ResultSetExtractor<T extends /*@Nullable*/ Object> {
        T extractData(ResultSet resultSet) throws Exception;
    }

    // this replaces H2's default shutdown hook (see jdbc connection db_close_on_exit=false above)
    // in order to prevent exceptions from occurring (and getting logged) during shutdown in the
    // case that there are still traces being written
    private class ShutdownHookThread extends Thread {
        @Override
        public void run() {
            try {
                // update flag outside of lock in case there is a backlog of threads already
                // waiting on the lock (once the flag is set, any threads in the backlog that
                // haven't acquired the lock will abort quickly once they do obtain the lock)
                closing = true;
                synchronized (lock) {
                    connection.close();
                }
            } catch (SQLException e) {
                logger.warn(e.getMessage(), e);
            }
        }
    }
}
