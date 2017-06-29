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
package org.glowroot.central.util;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.Semaphore;

import com.datastax.driver.core.BoundStatement;
import com.datastax.driver.core.Cluster;
import com.datastax.driver.core.PreparedStatement;
import com.datastax.driver.core.ResultSet;
import com.datastax.driver.core.ResultSetFuture;
import com.datastax.driver.core.Statement;
import com.datastax.driver.core.exceptions.DriverException;
import com.datastax.driver.core.exceptions.InvalidConfigurationInQueryException;
import com.google.common.base.Throwables;
import com.google.common.util.concurrent.MoreExecutors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static com.google.common.base.Preconditions.checkNotNull;
import static java.util.concurrent.TimeUnit.DAYS;

public class Session {

    public static final int MAX_CONCURRENT_QUERIES = 4096;

    private static final Logger logger = LoggerFactory.getLogger(Session.class);

    private final com.datastax.driver.core.Session session;

    private final Semaphore overallSemaphore = new Semaphore(MAX_CONCURRENT_QUERIES);

    // limit concurrent async queries per thread (mainly so rollup thread doesn't hog all)
    @SuppressWarnings("nullness:type.argument.type.incompatible")
    private final ThreadLocal<Semaphore> perThreadSemaphores = new ThreadLocal<Semaphore>() {
        @Override
        protected Semaphore initialValue() {
            return new Semaphore(MAX_CONCURRENT_QUERIES / 8);
        }
    };

    public Session(com.datastax.driver.core.Session session) {
        this.session = session;
    }

    public PreparedStatement prepare(String query) {
        return session.prepare(query);
    }

    public ResultSetFuture executeAsync(Statement statement) throws Exception {
        return throttle(() -> session.executeAsync(statement));
    }

    public ResultSetFuture executeAsync(String query) throws Exception {
        return throttle(() -> session.executeAsync(query));
    }

    public ResultSet execute(Statement statement) throws Exception {
        try {
            // do not use session.execute() because that calls getUninterruptibly() which can cause
            // central shutdown to timeout while waiting for executor service to shutdown
            return executeAsync(statement).get();
        } catch (ExecutionException e) {
            propagateCauseIfPossible(e);
            throw e; // unusual case (cause is null or cause is not Exception or Error)
        }
    }

    public ResultSet execute(String query) throws Exception {
        try {
            // do not use session.execute() because that calls getUninterruptibly() which can cause
            // central shutdown to timeout while waiting for executor service to shutdown
            return executeAsync(query).get();
        } catch (ExecutionException e) {
            propagateCauseIfPossible(e);
            throw e; // unusual case (cause is null or cause is not Exception or Error)
        }
    }

    public Cluster getCluster() {
        return session.getCluster();
    }

    public void close() {
        session.close();
    }

    public void createKeyspaceIfNotExists(String keyspace) throws Exception {
        session.execute("create keyspace if not exists " + keyspace + " with replication"
                + " = { 'class' : 'SimpleStrategy', 'replication_factor' : 1 }");
    }

    public void createTableWithTWCS(String createTableQuery, int expirationHours) throws Exception {
        createTableWithTWCS(createTableQuery, expirationHours, false);
    }

    public void createTableWithTWCS(String createTableQuery, int expirationHours,
            boolean useAndInsteadOfWith) throws Exception {
        // "Ideally, operators should select a compaction_window_unit and compaction_window_size
        // pair that produces approximately 20-30 windows"
        // (http://cassandra.apache.org/doc/latest/operating/compaction.html)
        int windowSizeHours = expirationHours / 24;

        // as long as gc_grace_seconds is less than TTL, then tombstones can be collected
        // immediately (https://issues.apache.org/jira/browse/CASSANDRA-4917)
        //
        // not using gc_grace_seconds of 0 since that disables hinted handoff
        // (http://www.uberobert.com/cassandra_gc_grace_disables_hinted_handoff)
        //
        // it seems any value over max_hint_window_in_ms (which defaults to 3 hours) is good
        long gcGraceSeconds = DAYS.toSeconds(1);
        String term = useAndInsteadOfWith ? "and" : "with";
        try {
            session.execute(createTableQuery + " " + term + " compaction = { 'class' :"
                    + " 'TimeWindowCompactionStrategy', 'compaction_window_unit' : 'HOURS',"
                    + " 'compaction_window_size' : '" + windowSizeHours + "' }"
                    + " and gc_grace_seconds = " + gcGraceSeconds);
        } catch (InvalidConfigurationInQueryException e) {
            logger.debug(e.getMessage(), e);
            session.execute(createTableQuery
                    + " " + term + " compaction = { 'class' : 'DateTieredCompactionStrategy' }"
                    + " and gc_grace_seconds = " + gcGraceSeconds);
        }
    }

    public ResultSetFuture executeAsyncWithOnFailure(BoundStatement boundStatement,
            Runnable onFailure) {
        ResultSetFuture future = session.executeAsync(boundStatement);
        future.addListener(new Runnable() {
            @Override
            public void run() {
                try {
                    // TODO report checker framework issue that occurs without checkNotNull
                    checkNotNull(future).getUninterruptibly();
                } catch (Exception e) {
                    logger.debug(e.getMessage(), e);
                    onFailure.run();
                }
            }
        }, MoreExecutors.directExecutor());
        return future;
    }

    private ResultSetFuture throttle(DoUnderThrottle doUnderThrottle) throws Exception {
        Semaphore perThreadSemaphore = perThreadSemaphores.get();
        perThreadSemaphore.acquire();
        overallSemaphore.acquire();
        ResultSetFuture future;
        try {
            future = doUnderThrottle.execute();
        } catch (Throwable t) {
            overallSemaphore.release();
            perThreadSemaphore.release();
            Throwables.propagateIfPossible(t, Exception.class);
            throw new Exception(t);
        }
        future.addListener(new Runnable() {
            @Override
            public void run() {
                overallSemaphore.release();
                perThreadSemaphore.release();
            }
        }, MoreExecutors.directExecutor());
        return future;
    }

    private static void propagateCauseIfPossible(ExecutionException e) throws Exception {
        Throwable cause = e.getCause();
        if (cause instanceof DriverException) {
            // see com.datastax.driver.core.DriverThrowables.propagateCause()
            throw ((DriverException) cause).copy();
        } else if (cause instanceof Exception) {
            throw (Exception) cause;
        } else if (cause instanceof Error) {
            throw (Error) cause;
        }
    }

    private interface DoUnderThrottle {
        ResultSetFuture execute();
    }
}
