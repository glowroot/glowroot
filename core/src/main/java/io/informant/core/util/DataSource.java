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
package io.informant.core.util;

import io.informant.api.Logger;
import io.informant.api.LoggerFactory;
import io.informant.core.util.Schemas.Column;
import io.informant.core.util.Schemas.Index;

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
import javax.annotation.concurrent.ThreadSafe;

import org.h2.jdbc.JdbcConnection;

import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;

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

    @Nullable
    private final File dbFile;
    private final boolean memDb;
    @GuardedBy("lock")
    private Connection connection;
    private final Object lock = new Object();
    private volatile boolean jvmShutdownInProgress = false;

    private final LoadingCache<String, PreparedStatement> preparedStatementCache = CacheBuilder
            .newBuilder().weakKeys().build(new CacheLoader<String, PreparedStatement>() {
                @Override
                public PreparedStatement load(String sql) throws SQLException {
                    return connection.prepareStatement(sql);
                }
            });

    // creates an in-memory database
    public DataSource() {
        this.dbFile = null;
        this.memDb = true;
        try {
            connection = createConnection();
        } catch (SQLException e) {
            logger.error(e.getMessage(), e);
            throw new IllegalStateException(e);
        }
    }

    public DataSource(File dbFile) {
        if (dbFile.getPath().endsWith(".h2.db")) {
            this.dbFile = dbFile;
        } else {
            this.dbFile = new File(dbFile.getParent(), dbFile.getName() + ".h2.db");
        }
        this.memDb = false;
        try {
            connection = createConnection();
        } catch (SQLException e) {
            logger.error(e.getMessage(), e);
            throw new IllegalStateException(e);
        }
        // implement jvm shutdown hook here instead of using default H2 jvm shutdown hook so that
        // DataSource is aware of the shutdown and won't execute any sql during/after H2 shutdown
        Runtime.getRuntime().addShutdownHook(new Thread() {
            @Override
            public void run() {
                try {
                    // update flag outside of lock in case there is a backlog of threads already
                    // waiting on the lock (once the flag is set, any threads in the backlog that
                    // haven't acquired the lock will abort quickly once they do obtain the lock)
                    jvmShutdownInProgress = true;
                    synchronized (lock) {
                        connection.close();
                    }
                } catch (SQLException e) {
                    logger.warn(e.getMessage(), e);
                }
            }
        });
    }

    public void close() throws SQLException {
        logger.debug("close()");
        synchronized (lock) {
            if (jvmShutdownInProgress) {
                return;
            }
            connection.close();
        }
    }

    public void compact() throws SQLException {
        if (memDb) {
            return;
        }
        synchronized (lock) {
            if (jvmShutdownInProgress) {
                return;
            }
            execute("shutdown compact");
            preparedStatementCache.invalidateAll();
            connection = createConnection();
        }
    }

    public void execute(String sql) throws SQLException {
        synchronized (lock) {
            if (jvmShutdownInProgress) {
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

    public long queryForLong(final String sql, Object... args) throws SQLException {
        synchronized (lock) {
            if (jvmShutdownInProgress) {
                return 0;
            }
            return queryUnderLock(sql, args, new ResultSetExtractor<Long>() {
                public Long extractData(ResultSet resultSet) throws SQLException {
                    if (resultSet.next()) {
                        Long value = resultSet.getLong(1);
                        if (value == null) {
                            logger.error("query '" + sql + "' returned a null sql value");
                            return 0L;
                        } else {
                            return value;
                        }
                    } else {
                        logger.error("query '" + sql + "' didn't return any results");
                        return 0L;
                    }
                }
            });
        }
    }

    public <T> List<T> query(String sql, Object[] args, RowMapper<T> rowMapper)
            throws SQLException {

        synchronized (lock) {
            if (jvmShutdownInProgress) {
                return ImmutableList.of();
            }
            PreparedStatement preparedStatement = prepareStatement(sql);
            try {
                for (int i = 0; i < args.length; i++) {
                    preparedStatement.setObject(i + 1, args[i]);
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

    public int update(String sql, Object... args) throws SQLException {
        if (jvmShutdownInProgress) {
            // this can get called a lot inserting trace snapshots, and these can get backlogged
            // on the lock below during jvm shutdown without pre-checking here (and backlogging
            // ends up generating warning messages from TraceSinkLocal.logPendingLimitWarning())
            return 0;
        }
        synchronized (lock) {
            if (jvmShutdownInProgress) {
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

    public void syncTable(String tableName, ImmutableList<Column> columns) throws SQLException {
        synchronized (lock) {
            if (jvmShutdownInProgress) {
                return;
            }
            Schemas.syncTable(tableName, columns, connection);
        }
    }

    public void syncIndexes(String tableName, ImmutableList<Index> indexes) throws SQLException {
        synchronized (lock) {
            if (jvmShutdownInProgress) {
                return;
            }
            Schemas.syncIndexes(tableName, indexes, connection);
        }
    }

    public ImmutableList<Column> getColumns(String tableName) throws SQLException {
        synchronized (lock) {
            if (jvmShutdownInProgress) {
                return ImmutableList.of();
            }
            return Schemas.getColumns(tableName, connection);
        }
    }

    @OnlyUsedByTests
    public boolean tableExists(String tableName) throws SQLException {
        synchronized (lock) {
            if (jvmShutdownInProgress) {
                return false;
            }
            return Schemas.tableExists(tableName, connection);
        }
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
            if (e.getCause() instanceof SQLException) {
                throw (SQLException) e.getCause();
            } else {
                logger.error(e.getMessage(), e.getCause());
                SQLException f = new SQLException("Unexpected not-really-a-sql-exception");
                f.initCause(e);
                throw f;
            }
        }
    }

    @SuppressWarnings("unused")
    private void closeStatement(PreparedStatement preparedStatement) throws SQLException {
        // does nothing since all prepared statements are pooled
    }

    private Connection createConnection() throws SQLException {
        // do not use java.sql.DriverManager or org.h2.Driver because these register the driver
        // globally with the JVM
        if (memDb) {
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

    public interface RowMapper<T> {
        T mapRow(ResultSet resultSet) throws SQLException;
    }

    private interface ResultSetExtractor<T> {
        T extractData(ResultSet resultSet) throws SQLException;
    }
}
