/*
 * Copyright 2015 the original author or authors.
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

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Semaphore;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import javax.annotation.Nullable;

import com.google.common.collect.Queues;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.glowroot.common.util.OnlyUsedByTests;

class ConnectionPool {

    private static final Logger logger = LoggerFactory.getLogger(ConnectionPool.class);

    private final ReadWriteLock readWriteLock = new ReentrantReadWriteLock(true);
    private final Lock readLock = readWriteLock.readLock();
    private final Lock writeLock = readWriteLock.writeLock();

    private volatile boolean closing = false;

    private final ConnectionFactory connectionFactory;

    private final Semaphore limiter = new Semaphore(1, true);

    private final ConcurrentLinkedQueue<Connection> pool = Queues.newConcurrentLinkedQueue();

    private final ThreadLocal</*@Nullable*/Connection> boundConnection =
            new ThreadLocal</*@Nullable*/Connection>();

    private final Thread shutdownHookThread;

    ConnectionPool(ConnectionFactory connectionFactory) throws SQLException {
        this.connectionFactory = connectionFactory;
        pool.offer(connectionFactory.createConnection());
        shutdownHookThread = new ShutdownHookThread();
        Runtime.getRuntime().addShutdownHook(shutdownHookThread);
    }

    <T> T execute(ConnectionCallback<T> callback, T valueOnClosing) throws Exception {
        return executeInternal(callback, valueOnClosing, false);
    }

    void execute(StatementCallback callback) throws Exception {
        execute(callback, false);
    }

    void executeAndReleaseAll(StatementCallback callback) throws Exception {
        execute(callback, true);
    }

    // convenience method
    <T> T execute(final String sql, final PreparedStatementCallback<T> callback, T valueOnClosing)
            throws Exception {
        return execute(new ConnectionCallback<T>() {
            @Override
            public T doWithConnection(Connection connection) throws SQLException {
                PreparedStatement preparedStatement = connection.prepareStatement(sql);
                StatementCloser closer = new StatementCloser(preparedStatement);
                try {
                    return callback.doWithPreparedStatement(preparedStatement);
                    // don't need to close statement since they are all cached and used under lock
                } catch (Throwable t) {
                    throw closer.rethrow(t);
                } finally {
                    closer.close();
                }
            }
        }, valueOnClosing);
    }

    private void execute(final StatementCallback callback, boolean releaseAllAfterwards)
            throws Exception {
        executeInternal(new ConnectionCallback</*@Nullable*/Void>() {
            @Override
            public @Nullable Void doWithConnection(Connection connection) throws SQLException {
                Statement statement = connection.createStatement();
                StatementCloser closer = new StatementCloser(statement);
                try {
                    callback.doWithStatement(statement);
                } catch (Throwable t) {
                    throw closer.rethrow(t);
                } finally {
                    closer.close();
                }
                return null;
            }
        }, null, releaseAllAfterwards);
    }

    private <T> T executeInternal(ConnectionCallback<T> callback, T valueOnClosing,
            boolean releaseAllAfterwards) throws Exception {
        if (closing) {
            // this can get called a lot inserting traces, and these can get backlogged
            // on the lock below during jvm shutdown without pre-checking here (and backlogging
            // ends up generating warning messages from
            // TransactionCollectorImpl.logPendingLimitWarning())
            return valueOnClosing;
        }
        Lock lock = releaseAllAfterwards ? this.writeLock : readLock;
        lock.lock();
        try {
            if (closing) {
                return valueOnClosing;
            }
            T value = executeInternal(callback);
            if (releaseAllAfterwards) {
                Connection connection;
                while ((connection = pool.poll()) != null) {
                    connection.close();
                }
                pool.offer(connectionFactory.createConnection());
            }
            return value;
        } finally {
            lock.unlock();
        }
    }

    private <T> T executeInternal(ConnectionCallback<T> callback) throws Exception {
        Connection connection = boundConnection.get();
        boolean releasePermit = false;
        boolean unbindConnection = false;
        if (connection == null) {
            limiter.acquire();
            releasePermit = true;

            connection = pool.poll();
            if (connection == null) {
                // create a new connection which will be returned to pool at the end
                connection = connectionFactory.createConnection();
            }
            boundConnection.set(connection);
            unbindConnection = true;
        }
        boolean returnConnection = unbindConnection;
        try {
            return callback.doWithConnection(connection);
        } catch (SQLException e) {
            // close the connection in case it is disconnected
            connection.close();
            returnConnection = false;
            throw e;
        } finally {
            if (unbindConnection) {
                boundConnection.remove();
            }
            if (returnConnection) {
                pool.offer(connection);
            }
            if (releasePermit) {
                limiter.release();
            }
        }
    }

    @OnlyUsedByTests
    public void close() throws SQLException {
        closeInternal();
        Runtime.getRuntime().removeShutdownHook(shutdownHookThread);
    }

    private void closeInternal() throws SQLException {
        // update flag outside of lock in case there is a backlog of threads already
        // waiting on the lock (once the flag is set, any threads in the backlog that
        // haven't acquired the lock will abort quickly once they do obtain the lock)
        closing = true;
        // write lock will wait for all connections to be returned to pool
        writeLock.lock();
        try {
            Connection connection;
            while ((connection = pool.poll()) != null) {
                connection.close();
            }
        } finally {
            writeLock.unlock();
        }
    }

    interface ConnectionFactory {
        Connection createConnection() throws SQLException;
    }

    interface ConnectionCallback<T> {
        T doWithConnection(Connection connection) throws SQLException;
    }

    interface PreparedStatementCallback<T> {
        T doWithPreparedStatement(PreparedStatement preparedStatement) throws Exception;
    }

    interface StatementCallback {
        void doWithStatement(Statement statement) throws SQLException;
    }

    // this replaces H2's default shutdown hook (see jdbc connection db_close_on_exit=false above)
    // in order to prevent exceptions from occurring (and getting logged) during shutdown in the
    // case that there are still traces being written
    private class ShutdownHookThread extends Thread {
        @Override
        public void run() {
            try {
                closeInternal();
            } catch (SQLException e) {
                logger.warn(e.getMessage(), e);
            }
        }
    }
}
