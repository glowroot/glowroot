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

import java.util.Collection;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Semaphore;

import com.datastax.driver.core.Cluster;
import com.datastax.driver.core.KeyspaceMetadata;
import com.datastax.driver.core.PreparedStatement;
import com.datastax.driver.core.ResultSet;
import com.datastax.driver.core.ResultSetFuture;
import com.datastax.driver.core.Statement;
import com.datastax.driver.core.TableMetadata;
import com.datastax.driver.core.exceptions.InvalidConfigurationInQueryException;
import com.datastax.driver.core.exceptions.NoHostAvailableException;
import com.google.common.base.Stopwatch;
import com.google.common.base.Throwables;
import com.google.common.collect.ImmutableList;
import com.google.common.util.concurrent.FutureCallback;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.MoreExecutors;
import com.google.common.util.concurrent.SettableFuture;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static java.util.concurrent.TimeUnit.HOURS;
import static java.util.concurrent.TimeUnit.SECONDS;

public class Session {

    private static final Logger logger = LoggerFactory.getLogger(Session.class);

    private final com.datastax.driver.core.Session wrappedSession;
    private final String keyspace;

    // limit concurrent async queries per thread (mainly so the rollup thread doesn't hog them all)
    @SuppressWarnings("nullness:type.argument.type.incompatible")
    private final ThreadLocal<Semaphore> perThreadSemaphores = new ThreadLocal<Semaphore>() {
        @Override
        protected Semaphore initialValue() {
            return new Semaphore(512);
        }
    };

    private final CassandraWriteMetrics cassandraWriteMetrics;

    public Session(com.datastax.driver.core.Session wrappedSession, String keyspace)
            throws InterruptedException {
        this.wrappedSession = wrappedSession;
        this.keyspace = keyspace;
        cassandraWriteMetrics = new CassandraWriteMetrics(wrappedSession, keyspace);

        updateSchemaWithRetry(wrappedSession, "create keyspace if not exists " + keyspace
                + " with replication = { 'class' : 'SimpleStrategy', 'replication_factor' : 1 }");
        wrappedSession.execute("use " + keyspace);
    }

    public CassandraWriteMetrics getCassandraWriteMetrics() {
        return cassandraWriteMetrics;
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
            throw MoreFutures.unwrapDriverException(e);
        }
    }

    public ResultSet execute(String query) throws Exception {
        try {
            // do not use session.execute() because that calls getUninterruptibly() which can cause
            // central shutdown to timeout while waiting for executor service to shutdown
            return executeAsync(query).get();
        } catch (ExecutionException e) {
            throw MoreFutures.unwrapDriverException(e);
        }
    }

    public ListenableFuture<ResultSet> executeAsync(Statement statement) throws Exception {
        return throttle(() -> {
            // for now, need to record metrics in the same method because CassandraWriteMetrics
            // relies on some thread locals
            cassandraWriteMetrics.recordMetrics(statement);
            return wrappedSession.executeAsync(statement);
        });
    }

    private ListenableFuture<ResultSet> executeAsync(String query) throws Exception {
        return throttle(() -> wrappedSession.executeAsync(query));
    }

    public Cluster getCluster() {
        return wrappedSession.getCluster();
    }

    public @Nullable TableMetadata getTable(String name) {
        KeyspaceMetadata keyspace = getKeyspace();
        if (keyspace == null) {
            return null;
        } else {
            return keyspace.getTable(name);
        }
    }

    public Collection<TableMetadata> getTables() {
        KeyspaceMetadata keyspace = getKeyspace();
        if (keyspace == null) {
            return ImmutableList.of();
        } else {
            return keyspace.getTables();
        }
    }

    private @Nullable KeyspaceMetadata getKeyspace() {
        return wrappedSession.getCluster().getMetadata().getKeyspace(keyspace);
    }

    public void close() {
        wrappedSession.close();
    }

    public void createTableWithTWCS(String createTableQuery, int expirationHours)
            throws InterruptedException {
        createTableWithTWCS(createTableQuery, expirationHours, false);
    }

    public void createTableWithTWCS(String createTableQuery, int expirationHours,
            boolean useAndInsteadOfWith) throws InterruptedException {
        createTableWithTWCS(createTableQuery, expirationHours, useAndInsteadOfWith, false);
    }

    public void createTableWithTWCS(String createTableQuery, int expirationHours,
            boolean useAndInsteadOfWith, boolean fallbackToSTCS) throws InterruptedException {
        // as long as gc_grace_seconds is less than TTL, then tombstones can be collected
        // immediately (https://issues.apache.org/jira/browse/CASSANDRA-4917)
        //
        // not using gc_grace_seconds of 0 since that disables hinted handoff
        // (http://www.uberobert.com/cassandra_gc_grace_disables_hinted_handoff)
        //
        // it seems any value over max_hint_window_in_ms (which defaults to 3 hours) is good
        long gcGraceSeconds = HOURS.toSeconds(4);

        // using unchecked_tombstone_compaction=true for better tombstone purging
        // see http://thelastpickle.com/blog/2016/12/08/TWCS-part1.html
        String term = useAndInsteadOfWith ? "and" : "with";
        try {
            // using small min_sstable_size to avoid scenario where small sstables get written and
            // continually merged with "large" (but under default min_sstable_size of 50mb) sstable,
            // essentially recompacting the data in that "large" sstable over and over until it
            // finally reaches the default min_sstable_size of 50mb
            //
            // it's ok if a few smaller sstables don't get compacted due to reduced min_sstable_size
            // since major compaction is never too far away at the end of the window
            // bucket_high is increased a bit to compensate for lower min_sstable_size so that worst
            // case number of sstables will be three 5mb sstables, three 10mb sstables, three 20mb
            // sstables, three 40mb sstables, etc
            createIfNotExists(createTableQuery + " " + term + " "
                    + getTwcsCompactionClause(expirationHours) + " and gc_grace_seconds = "
                    + gcGraceSeconds);
        } catch (InvalidConfigurationInQueryException e) {
            logger.debug(e.getMessage(), e);
            if (fallbackToSTCS) {
                createIfNotExists(createTableQuery + " " + term + " compaction = { 'class' :"
                        + " 'SizeTieredCompactionStrategy', 'unchecked_tombstone_compaction' :"
                        + " true } and gc_grace_seconds = " + gcGraceSeconds);
            } else {
                createIfNotExists(createTableQuery + " " + term + " compaction = { 'class' :"
                        + " 'DateTieredCompactionStrategy', 'unchecked_tombstone_compaction' :"
                        + " true } and gc_grace_seconds = " + gcGraceSeconds);
            }
        }
    }

    public void updateTableTwcsProperties(String tableName, int expirationHours) {
        wrappedSession.execute(
                "alter table " + tableName + " with " + getTwcsCompactionClause(expirationHours));
    }

    public void updateTableTwcsProperties(String tableName, String compactionWindowUnit,
            int compactionWindowSize) {
        wrappedSession.execute("alter table " + tableName + " with "
                + getTwcsCompactionClause(compactionWindowUnit, compactionWindowSize));
    }

    public void createTableWithLCS(String createTableQuery) throws InterruptedException {
        createTableWithLCS(createTableQuery, false);
    }

    public void createTableWithLCS(String createTableQuery, boolean useAndInsteadOfWith)
            throws InterruptedException {
        String term = useAndInsteadOfWith ? "and" : "with";
        createIfNotExists(createTableQuery + " " + term + " compaction = { 'class' :"
                + " 'LeveledCompactionStrategy', 'unchecked_tombstone_compaction' : true }");
    }

    public void createTableWithSTCS(String createTableQuery) throws InterruptedException {
        createIfNotExists(createTableQuery + " with compaction = { 'class' :"
                + " 'SizeTieredCompactionStrategy', 'unchecked_tombstone_compaction' : true }");
    }

    public void updateSchemaWithRetry(String query) throws InterruptedException {
        updateSchemaWithRetry(wrappedSession, query);
    }

    private ListenableFuture<ResultSet> throttle(DoUnderThrottle doUnderThrottle) throws Exception {
        Semaphore perThreadSemaphore = perThreadSemaphores.get();
        perThreadSemaphore.acquire();
        SettableFuture<ResultSet> outerFuture = SettableFuture.create();
        ResultSetFuture innerFuture;
        try {
            innerFuture = doUnderThrottle.execute();
        } catch (Throwable t) {
            perThreadSemaphore.release();
            Throwables.propagateIfPossible(t, Exception.class);
            throw new Exception(t);
        }
        Futures.addCallback(innerFuture, new FutureCallback<ResultSet>() {
            @Override
            public void onSuccess(ResultSet result) {
                perThreadSemaphore.release();
                outerFuture.set(result);
            }
            @Override
            public void onFailure(Throwable t) {
                perThreadSemaphore.release();
                outerFuture.setException(t);
            }
        }, MoreExecutors.directExecutor());
        return outerFuture;
    }

    private void createIfNotExists(String query) throws InterruptedException {
        updateSchemaWithRetry(wrappedSession, query);
    }

    // creating/dropping/truncating keyspaces/tables can timeout, throwing NoHostAvailableException
    // (see https://github.com/glowroot/glowroot/issues/351)
    private static void updateSchemaWithRetry(com.datastax.driver.core.Session wrappedSession,
            String query) throws InterruptedException {
        Stopwatch stopwatch = Stopwatch.createStarted();
        while (stopwatch.elapsed(SECONDS) < 60) {
            try {
                wrappedSession.execute(query);
                return;
            } catch (NoHostAvailableException e) {
                logger.debug(e.getMessage(), e);
            }
            Thread.sleep(1000);
        }
        // try one last time and let exception bubble up
        wrappedSession.execute(query);
    }

    public static int getCompactionWindowSizeHours(int expirationHours) {
        // "Ideally, operators should select a compaction_window_unit and compaction_window_size
        // pair that produces approximately 20-30 windows"
        // (http://cassandra.apache.org/doc/latest/operating/compaction.html)
        //
        // "You should target fewer than 50 buckets per table based on your TTL"
        // (https://github.com/jeffjirsa/twcs)
        //
        // "With a 10 year retention, just ignore the target sstable count (I should remove that
        // guidance, to be honest)"
        // (https://groups.google.com/d/msg/nosql-databases/KiKVqD0Oe98/TJD0mnJFEAAJ)
        if (expirationHours == 0) {
            // one month buckets seems like a sensible maximum
            return 30 * 24;
        } else {
            // one month buckets seems like a sensible maximum
            return Math.min(expirationHours / 24, 30 * 24);
        }
    }

    private static String getTwcsCompactionClause(String compactionWindowUnit,
            int compactionWindowSize) {
        // tombstone_threshold = 0.8 is recommended by Jeff Jirsa, see
        // https://www.slideshare.net/JeffJirsa1/using-time-window-compaction-strategy-for-time-series-workloads
        // (related, by setting this it enables single-sstable tombstone compaction, see
        // https://issues.apache.org/jira/browse/CASSANDRA-9234 and
        // https://github.com/apache/cassandra/blob/cassandra-3.11.1/src/java/org/apache/cassandra/db/compaction/TimeWindowCompactionStrategy.java#L62,
        // which helps keep things orderly, e.g. if expiration is temporarily set to large value,
        // then that data will prevent all newer data from being collected until it expires without
        // single sstable tombstone, and other reasons)
        return "compaction = { 'class' : 'TimeWindowCompactionStrategy', 'compaction_window_unit'"
                + " : '" + compactionWindowUnit + "', 'compaction_window_size' : "
                + compactionWindowSize + ", 'unchecked_tombstone_compaction' : true,"
                + " 'tombstone_threshold' : 0.8, 'min_sstable_size' : " + (5 * 1024 * 1024)
                + ", 'bucket_high' : 2 }";
    }

    private static String getTwcsCompactionClause(int expirationHours) {
        return getTwcsCompactionClause("HOURS", getCompactionWindowSizeHours(expirationHours));
    }

    private interface DoUnderThrottle {
        ResultSetFuture execute();
    }
}
