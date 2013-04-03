/**
 * Copyright 2012-2013 the original author or authors.
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
package io.informant.local.store;

import java.io.File;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.ExecutionException;

import checkers.igj.quals.ReadOnly;
import checkers.lock.quals.GuardedBy;
import checkers.nullness.quals.Nullable;
import com.google.common.base.Throwables;
import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;
import org.h2.jdbc.JdbcConnection;
import org.h2.store.FileLister;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.informant.local.store.Schemas.Column;
import io.informant.local.store.Schemas.Index;
import io.informant.markers.OnlyUsedByTests;
import io.informant.markers.ThreadSafe;

/**
 * DataSource is a cross between javax.sql.DataSource and spring's JdbcTemplate. Ideally would have
 * just used/wrapped JdbcTemplate but want to keep external dependencies down where reasonable.
 * 
 * @author Trask Stalnaker
 * @since 0.5
 */
@ThreadSafe
public class DataSource {

    private static final Logger logger = LoggerFactory.getLogger(DataSource.class);

    // null means use memDb
    @Nullable
    private final File dbFile;
    private final Thread shutdownHookThread;
    private final Object lock = new Object();
    @GuardedBy("lock")
    private Connection connection;
    private volatile boolean closing = false;

    private final LoadingCache<String, PreparedStatement> preparedStatementCache = CacheBuilder
            .newBuilder().weakKeys().build(new CacheLoader<String, PreparedStatement>() {
                @Override
                public PreparedStatement load(String sql) throws SQLException {
                    return connection.prepareStatement(sql);
                }
            });

    // creates an in-memory database
    DataSource() throws SQLException {
        dbFile = null;
        connection = createConnection(null);
        shutdownHookThread = new ShutdownHookThread();
        Runtime.getRuntime().addShutdownHook(shutdownHookThread);
    }

    DataSource(File dbFile) throws SQLException {
        if (dbFile.getPath().endsWith(".h2.db")) {
            this.dbFile = dbFile;
        } else {
            this.dbFile = new File(dbFile.getParent(), dbFile.getName() + ".h2.db");
        }
        connection = createConnection(dbFile);
        shutdownHookThread = new ShutdownHookThread();
        Runtime.getRuntime().addShutdownHook(shutdownHookThread);
    }

    public void compact() throws SQLException {
        if (dbFile == null) {
            return;
        }
        synchronized (lock) {
            if (closing) {
                return;
            }
            execute("shutdown compact");
            preparedStatementCache.invalidateAll();
            connection = createConnection(dbFile);
        }
    }

    void execute(String sql) throws SQLException {
        synchronized (lock) {
            if (closing) {
                return;
            }
            Statement statement = connection.createStatement();
            try {
                statement.execute(sql);
            } finally {
                statement.close();
            }
        }
    }

    long queryForLong(final String sql, Object... args) throws SQLException {
        synchronized (lock) {
            if (closing) {
                return 0;
            }
            return queryUnderLock(sql, args, new ResultSetExtractor<Long>() {
                public Long extractData(ResultSet resultSet) throws SQLException {
                    if (resultSet.next()) {
                        Long value = resultSet.getLong(1);
                        if (value == null) {
                            logger.warn("query '" + sql + "' returned a null sql value");
                            return 0L;
                        } else {
                            return value;
                        }
                    } else {
                        logger.warn("query '" + sql + "' didn't return any results");
                        return 0L;
                    }
                }
            });
        }
    }

    @ReadOnly
    <T> List<T> query(String sql, @ReadOnly List<?> args, RowMapper<T> rowMapper)
            throws SQLException {
        synchronized (lock) {
            if (closing) {
                return ImmutableList.of();
            }
            PreparedStatement preparedStatement = prepareStatement(sql);
            try {
                for (int i = 0; i < args.size(); i++) {
                    preparedStatement.setObject(i + 1, args.get(i));
                }
                ResultSet resultSet = preparedStatement.executeQuery();
                try {
                    List<T> mappedRows = Lists.newArrayList();
                    while (resultSet.next()) {
                        mappedRows.add(rowMapper.mapRow(resultSet));
                    }
                    return mappedRows;
                } finally {
                    resultSet.close();
                }
            } finally {
                closeStatement(preparedStatement);
            }
        }
    }

    int update(String sql, @Nullable Object... args) throws SQLException {
        if (closing) {
            // this can get called a lot inserting trace snapshots, and these can get backlogged
            // on the lock below during jvm shutdown without pre-checking here (and backlogging
            // ends up generating warning messages from TraceSinkLocal.logPendingLimitWarning())
            return 0;
        }
        synchronized (lock) {
            if (closing) {
                return 0;
            }
            PreparedStatement preparedStatement = prepareStatement(sql);
            try {
                for (int i = 0; i < args.length; i++) {
                    preparedStatement.setObject(i + 1, args[i]);
                }
                return preparedStatement.executeUpdate();
            } finally {
                closeStatement(preparedStatement);
            }
        }
    }

    void syncTable(String tableName, ImmutableList<Column> columns) throws SQLException {
        synchronized (lock) {
            if (closing) {
                return;
            }
            Schemas.syncTable(tableName, columns, connection);
        }
    }

    void syncIndexes(String tableName, ImmutableList<Index> indexes) throws SQLException {
        synchronized (lock) {
            if (closing) {
                return;
            }
            Schemas.syncIndexes(tableName, indexes, connection);
        }
    }

    ImmutableList<Column> getColumns(String tableName) throws SQLException {
        synchronized (lock) {
            if (closing) {
                return ImmutableList.of();
            }
            return Schemas.getColumns(tableName, connection);
        }
    }

    boolean tableExists(String tableName) throws SQLException {
        synchronized (lock) {
            if (closing) {
                return false;
            }
            return Schemas.tableExists(tableName, connection);
        }
    }

    @OnlyUsedByTests
    void close() throws SQLException {
        logger.debug("close()");
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
    private <T> T queryUnderLock(String sql, Object[] args, ResultSetExtractor<T> rse)
            throws SQLException {
        PreparedStatement preparedStatement = prepareStatement(sql);
        try {
            for (int i = 0; i < args.length; i++) {
                preparedStatement.setObject(i + 1, args[i]);
            }
            ResultSet resultSet = preparedStatement.executeQuery();
            try {
                return rse.extractData(resultSet);
            } finally {
                resultSet.close();
            }
        } finally {
            closeStatement(preparedStatement);
        }
    }

    private PreparedStatement prepareStatement(String sql) throws SQLException {
        try {
            return preparedStatementCache.get(sql);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            Throwables.propagateIfPossible(cause, SQLException.class);
            // it should not really be possible to get here since the only checked exception that
            // preparedStatementCache's CacheLoader throws is SQLException
            logger.error(e.getMessage(), e);
            SQLException f = new SQLException(e.getMessage());
            f.initCause(e);
            throw f;
        }
    }

    @SuppressWarnings("unused")
    private void closeStatement(PreparedStatement preparedStatement) throws SQLException {
        // does nothing since all prepared statements are pooled
    }

    public static boolean tryUnlockDatabase(File dbFile) {
        try {
            FileLister.tryUnlockDatabase(Lists.newArrayList(dbFile.getPath()), null);
            return true;
        } catch (SQLException e) {
            return false;
        }
    }

    private static Connection createConnection(@Nullable File dbFile)
            throws SQLException {
        // do not use java.sql.DriverManager or org.h2.Driver because these register the driver
        // globally with the JVM
        if (dbFile == null) {
            return new JdbcConnection("jdbc:h2:mem:", new Properties());
        } else {
            String dbPath = dbFile.getPath();
            dbPath = dbPath.replaceFirst(".h2.db$", "");
            Properties props = new Properties();
            props.setProperty("user", "sa");
            props.setProperty("password", "");
            // db_close_on_exit=false since jvm shutdown hook is handled by DataSource
            return new JdbcConnection("jdbc:h2:file:" + dbPath
                    + ";db_close_on_exit=false;compress_lob=lzf", props);
        }
    }

    interface RowMapper<T> {
        T mapRow(ResultSet resultSet) throws SQLException;
    }

    private interface ResultSetExtractor<T> {
        T extractData(ResultSet resultSet) throws SQLException;
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
