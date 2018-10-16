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
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Semaphore;

import com.datastax.driver.core.BoundStatement;
import com.datastax.driver.core.Cluster;
import com.datastax.driver.core.ConsistencyLevel;
import com.datastax.driver.core.KeyspaceMetadata;
import com.datastax.driver.core.PreparedStatement;
import com.datastax.driver.core.ResultSet;
import com.datastax.driver.core.ResultSetFuture;
import com.datastax.driver.core.SimpleStatement;
import com.datastax.driver.core.Statement;
import com.datastax.driver.core.TableMetadata;
import com.datastax.driver.core.exceptions.InvalidConfigurationInQueryException;
import com.datastax.driver.core.exceptions.NoHostAvailableException;
import com.google.common.base.Function;
import com.google.common.base.Stopwatch;
import com.google.common.base.Throwables;
import com.google.common.collect.ImmutableList;
import com.google.common.util.concurrent.AsyncFunction;
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

    // limit concurrent async queries per thread (mainly so the rollup thread doesn't hog them all)
    @SuppressWarnings("nullness:type.argument.type.incompatible")
    private static final ThreadLocal<Semaphore> perThreadSemaphores = new ThreadLocal<Semaphore>() {
        @Override
        protected Semaphore initialValue() {
            return new Semaphore(512);
        }
    };

    private final com.datastax.driver.core.Session wrappedSession;
    private final String keyspaceName;
    private final @Nullable ConsistencyLevel writeConsistencyLevel;

    private final Queue<String> allTableNames = new ConcurrentLinkedQueue<>();

    private final CassandraWriteMetrics cassandraWriteMetrics;

    public Session(com.datastax.driver.core.Session wrappedSession, String keyspaceName,
            @Nullable ConsistencyLevel writeConsistencyLevel) throws InterruptedException {
        this.wrappedSession = wrappedSession;
        this.keyspaceName = keyspaceName;
        this.writeConsistencyLevel = writeConsistencyLevel;
        cassandraWriteMetrics = new CassandraWriteMetrics(wrappedSession, keyspaceName);

        updateSchemaWithRetry(wrappedSession, "create keyspace if not exists " + keyspaceName
                + " with replication = { 'class' : 'SimpleStrategy', 'replication_factor' : 1 }");
        wrappedSession.execute("use " + keyspaceName);
    }

    public CassandraWriteMetrics getCassandraWriteMetrics() {
        return cassandraWriteMetrics;
    }

    public PreparedStatement prepare(String query) {
        return wrappedSession.prepare(query);
    }

    public ResultSet read(String query) throws Exception {
        if (!query.startsWith("select ")) {
            throw new IllegalStateException("Unexpected read query: " + query);
        }
        // do not use session.execute() because that calls getUninterruptibly() which can cause
        // central shutdown to timeout while waiting for executor service to shutdown
        return readAsync(new SimpleStatement(query)).get();
    }

    public ResultSet read(Statement statement) throws Exception {
        if (statement instanceof BoundStatement) {
            BoundStatement boundStatement = (BoundStatement) statement;
            PreparedStatement preparedStatement = boundStatement.preparedStatement();
            String queryString = preparedStatement.getQueryString();
            if (!queryString.startsWith("select ")) {
                throw new IllegalStateException("Unexpected read query: " + queryString);
            }
        }
        // do not use session.execute() because that calls getUninterruptibly() which can cause
        // central shutdown to timeout while waiting for executor service to shutdown
        return readAsync(statement).get();
    }

    public void write(Statement statement) throws Exception {
        if (statement instanceof BoundStatement) {
            BoundStatement boundStatement = (BoundStatement) statement;
            PreparedStatement preparedStatement = boundStatement.preparedStatement();
            String queryString = preparedStatement.getQueryString();
            if (!queryString.startsWith("insert ") && !queryString.startsWith("delete ")) {
                throw new IllegalStateException("Unexpected write query: " + queryString);
            }
        }
        // do not use session.execute() because that calls getUninterruptibly() which can cause
        // central shutdown to timeout while waiting for executor service to shutdown
        writeAsync(statement).get();
    }

    public ResultSet update(Statement statement) throws Exception {
        if (statement instanceof BoundStatement) {
            BoundStatement boundStatement = (BoundStatement) statement;
            PreparedStatement preparedStatement = boundStatement.preparedStatement();
            String queryString = preparedStatement.getQueryString();
            if (!queryString.contains(" if ")) {
                throw new IllegalStateException("Unexpected update query: " + queryString);
            }
            if (!queryString.startsWith("update ") && !queryString.startsWith("insert ")) {
                throw new IllegalStateException("Unexpected update query: " + queryString);
            }
        }
        // do not use session.execute() because that calls getUninterruptibly() which can cause
        // central shutdown to timeout while waiting for executor service to shutdown
        return updateAsync(statement).get();
    }

    public ListenableFuture<ResultSet> readAsyncWarnIfNoRows(Statement statement,
            String warningMessage, Object... warningArguments) throws Exception {
        return Futures.transform(readAsync(statement),
                new Function<ResultSet, ResultSet>() {
                    @Override
                    public ResultSet apply(ResultSet results) {
                        if (results.isExhausted()) {
                            logger.warn(warningMessage, warningArguments);
                        }
                        return results;
                    }
                },
                MoreExecutors.directExecutor());
    }

    public ListenableFuture<ResultSet> readAsyncFailIfNoRows(Statement statement,
            String errorMessage) throws Exception {
        return Futures.transformAsync(readAsync(statement),
                new AsyncFunction<ResultSet, ResultSet>() {
                    @Override
                    public ListenableFuture<ResultSet> apply(ResultSet results) {
                        if (results.isExhausted()) {
                            return Futures.immediateFailedFuture(new Exception(errorMessage));
                        } else {
                            return Futures.immediateFuture(results);
                        }
                    }
                },
                MoreExecutors.directExecutor());
    }

    public ListenableFuture<ResultSet> readAsync(Statement statement) throws Exception {
        return throttle(() -> wrappedSession.executeAsync(statement));
    }

    public ListenableFuture<?> writeAsync(Statement statement) throws Exception {
        if (statement.getConsistencyLevel() == null && writeConsistencyLevel != null) {
            statement.setConsistencyLevel(writeConsistencyLevel);
        }
        return throttle(() -> {
            // for now, need to record metrics in the same method because CassandraWriteMetrics
            // relies on some thread locals
            cassandraWriteMetrics.recordMetrics(statement);
            return wrappedSession.executeAsync(statement);
        });
    }

    private ListenableFuture<ResultSet> updateAsync(Statement statement) throws Exception {
        return throttle(() -> wrappedSession.executeAsync(statement));
    }

    public Cluster getCluster() {
        return wrappedSession.getCluster();
    }

    public String getKeyspaceName() {
        return keyspaceName;
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

    public Collection<String> getAllTableNames() {
        return allTableNames;
    }

    private @Nullable KeyspaceMetadata getKeyspace() {
        return wrappedSession.getCluster().getMetadata().getKeyspace(keyspaceName);
    }

    public void close() throws InterruptedException {
        wrappedSession.close();
        cassandraWriteMetrics.close();
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
            createTableWithTracking(createTableQuery + " " + term + " "
                    + getTwcsCompactionClause(expirationHours) + " and gc_grace_seconds = "
                    + gcGraceSeconds);
        } catch (InvalidConfigurationInQueryException e) {
            logger.debug(e.getMessage(), e);
            if (fallbackToSTCS) {
                createTableWithTracking(createTableQuery + " " + term + " compaction = { 'class' :"
                        + " 'SizeTieredCompactionStrategy', 'unchecked_tombstone_compaction' :"
                        + " true } and gc_grace_seconds = " + gcGraceSeconds);
            } else {
                createTableWithTracking(createTableQuery + " " + term + " compaction = { 'class' :"
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
        createTableWithTracking(createTableQuery + " " + term + " compaction = { 'class' :"
                + " 'LeveledCompactionStrategy', 'unchecked_tombstone_compaction' : true }");
    }

    public void createTableWithSTCS(String createTableQuery) throws InterruptedException {
        createTableWithTracking(createTableQuery + " with compaction = { 'class' :"
                + " 'SizeTieredCompactionStrategy', 'unchecked_tombstone_compaction' : true }");
    }

    public void updateSchemaWithRetry(String query) throws InterruptedException {
        updateSchemaWithRetry(wrappedSession, query);
    }

    private void createTableWithTracking(String createTableQuery) throws InterruptedException {
        if (createTableQuery.startsWith("create table if not exists ")) {
            String remaining = createTableQuery.substring("create table if not exists ".length());
            int index = remaining.indexOf(' ');
            allTableNames.add(remaining.substring(0, index));
        } else {
            throw new IllegalStateException("create table query must use \"if not exists\" so that"
                    + " it can be safely retried on timeout");
        }
        updateSchemaWithRetry(createTableQuery);
    }

    public static Semaphore getPerThreadSemaphore() {
        return perThreadSemaphores.get();
    }

    public static void setPerThreadSemaphore(Semaphore semaphore) {
        perThreadSemaphores.set(semaphore);
    }

    private static ListenableFuture<ResultSet> throttle(DoUnderThrottle doUnderThrottle)
            throws Exception {
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

    private static @Nullable String getTableName(String createTableQuery, String prefix) {
        if (createTableQuery.startsWith(prefix)) {
            String suffix = createTableQuery.substring(prefix.length());
            int index = suffix.indexOf(' ');
            return suffix.substring(0, index);
        } else {
            return null;
        }
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
            SECONDS.sleep(1);
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
