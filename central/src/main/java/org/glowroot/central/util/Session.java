/*
 * Copyright 2017-2018 the original author or authors.
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

import com.datastax.driver.core.Cluster;
import com.datastax.driver.core.PreparedStatement;
import com.datastax.driver.core.ResultSet;
import com.datastax.driver.core.ResultSetFuture;
import com.datastax.driver.core.Statement;
import com.datastax.driver.core.exceptions.DriverException;
import com.datastax.driver.core.exceptions.InvalidConfigurationInQueryException;
import com.google.common.base.Throwables;
import com.google.common.util.concurrent.FutureCallback;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.MoreExecutors;
import com.google.common.util.concurrent.SettableFuture;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static java.util.concurrent.TimeUnit.DAYS;

public class Session {

    public static final int MAX_CONCURRENT_QUERIES = 4096;

    private static final Logger logger = LoggerFactory.getLogger(Session.class);

    private final com.datastax.driver.core.Session wrappedSession;

    private final Semaphore overallSemaphore = new Semaphore(MAX_CONCURRENT_QUERIES);

    // limit concurrent async queries per thread (mainly so rollup thread doesn't hog all)
    @SuppressWarnings("nullness:type.argument.type.incompatible")
    private final ThreadLocal<Semaphore> perThreadSemaphores = new ThreadLocal<Semaphore>() {
        @Override
        protected Semaphore initialValue() {
            return new Semaphore(MAX_CONCURRENT_QUERIES / 8);
        }
    };

    public Session(com.datastax.driver.core.Session wrappedSession) {
        this.wrappedSession = wrappedSession;
    }

    public PreparedStatement prepare(String query) {
        return wrappedSession.prepare(query);
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

    public ListenableFuture<ResultSet> executeAsync(Statement statement) throws Exception {
        return throttle(() -> wrappedSession.executeAsync(statement));
    }

    private ListenableFuture<ResultSet> executeAsync(String query) throws Exception {
        return throttle(() -> wrappedSession.executeAsync(query));
    }

    public Cluster getCluster() {
        return wrappedSession.getCluster();
    }

    public void close() {
        wrappedSession.close();
    }

    public void createKeyspaceIfNotExists(String keyspace) {
        wrappedSession.execute("create keyspace if not exists " + keyspace + " with replication"
                + " = { 'class' : 'SimpleStrategy', 'replication_factor' : 1 }");
    }

    public void createTableWithTWCS(String createTableQuery, int expirationHours) {
        createTableWithTWCS(createTableQuery, expirationHours, false);
    }

    public void createTableWithTWCS(String createTableQuery, int expirationHours,
            boolean useAndInsteadOfWith) {
        // as long as gc_grace_seconds is less than TTL, then tombstones can be collected
        // immediately (https://issues.apache.org/jira/browse/CASSANDRA-4917)
        //
        // not using gc_grace_seconds of 0 since that disables hinted handoff
        // (http://www.uberobert.com/cassandra_gc_grace_disables_hinted_handoff)
        //
        // it seems any value over max_hint_window_in_ms (which defaults to 3 hours) is good
        long gcGraceSeconds = DAYS.toSeconds(1);

        // using unchecked_tombstone_compaction=true for better tombstone purging
        // see http://thelastpickle.com/blog/2016/12/08/TWCS-part1.html
        String term = useAndInsteadOfWith ? "and" : "with";
        try {
            wrappedSession.execute(createTableQuery + " " + term + " compaction = { 'class' :"
                    + " 'TimeWindowCompactionStrategy', 'compaction_window_unit' : 'HOURS',"
                    + " 'compaction_window_size' : '"
                    + getCompactionWindowSizeHours(expirationHours)
                    + "', 'unchecked_tombstone_compaction' : true } and gc_grace_seconds = "
                    + gcGraceSeconds);
        } catch (InvalidConfigurationInQueryException e) {
            logger.debug(e.getMessage(), e);
            wrappedSession.execute(createTableQuery + " " + term
                    + " compaction = { 'class' : 'DateTieredCompactionStrategy',"
                    + " 'unchecked_tombstone_compaction' : true } and gc_grace_seconds = "
                    + gcGraceSeconds);
        }
    }

    private ListenableFuture<ResultSet> throttle(DoUnderThrottle doUnderThrottle) throws Exception {
        Semaphore perThreadSemaphore = perThreadSemaphores.get();
        perThreadSemaphore.acquire();
        overallSemaphore.acquire();
        SettableFuture<ResultSet> outerFuture = SettableFuture.create();
        ResultSetFuture innerFuture;
        try {
            innerFuture = doUnderThrottle.execute();
        } catch (Throwable t) {
            overallSemaphore.release();
            perThreadSemaphore.release();
            Throwables.propagateIfPossible(t, Exception.class);
            throw new Exception(t);
        }
        Futures.addCallback(innerFuture, new FutureCallback<ResultSet>() {
            @Override
            public void onSuccess(ResultSet result) {
                overallSemaphore.release();
                perThreadSemaphore.release();
                outerFuture.set(result);
            }
            @Override
            public void onFailure(Throwable t) {
                overallSemaphore.release();
                perThreadSemaphore.release();
                outerFuture.setException(t);
            }
        }, MoreExecutors.directExecutor());
        return outerFuture;
    }

    public static int getCompactionWindowSizeHours(int expirationHours) {
        // "Ideally, operators should select a compaction_window_unit and compaction_window_size
        // pair that produces approximately 20-30 windows"
        // (http://cassandra.apache.org/doc/latest/operating/compaction.html)
        if (expirationHours == 0) {
            // treat as expiration 10 years
            return 10 * 365;
        } else {
            return expirationHours / 24;
        }
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
