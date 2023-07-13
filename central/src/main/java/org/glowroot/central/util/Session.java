/*
 * Copyright 2017-2023 the original author or authors.
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

import java.lang.management.ManagementFactory;
import java.util.Collection;
import java.util.Optional;
import java.util.Queue;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Semaphore;

import javax.management.MBeanServer;
import javax.management.ObjectName;

import com.datastax.oss.driver.api.core.AllNodesFailedException;
import com.datastax.oss.driver.api.core.CqlSession;
import com.datastax.oss.driver.api.core.config.DriverExecutionProfile;
import com.datastax.oss.driver.api.core.cql.*;
import com.datastax.oss.driver.api.core.metadata.Metadata;
import com.datastax.oss.driver.api.core.servererrors.InvalidConfigurationInQueryException;
import com.datastax.oss.driver.api.core.ConsistencyLevel;
import com.datastax.oss.driver.api.core.metadata.schema.KeyspaceMetadata;
import com.datastax.oss.driver.api.core.metadata.schema.TableMetadata;
import com.google.common.base.Stopwatch;
import com.google.common.collect.ImmutableList;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static java.util.concurrent.TimeUnit.SECONDS;

public class Session implements AutoCloseable {

    private static final Logger logger = LoggerFactory.getLogger(Session.class);

    @SuppressWarnings("nullness:type.argument.type.incompatible")
    private static final ThreadLocal<Boolean> inRollupThread = ThreadLocal.withInitial(() -> false);

    // limit concurrent queries to avoid BusyPoolException
    // separate read/write query limits in order to give some preference to UI requests which are
    // primarily read queries, compared to the bulk of the concurrent queries which are primarily
    // write queries
    // separate rollup query limit in order to prevent rollup from hogging too many, and also to
    // prevent rollup from not getting enough
    private final Semaphore readQuerySemaphore;
    private final Semaphore writeQuerySemaphore;
    private final Semaphore rollupQuerySemaphore;

    private final CqlSession wrappedSession;
    private final String keyspaceName;
    private final @Nullable ConsistencyLevel writeConsistencyLevel;
    private final int gcGraceSeconds;

    private final Queue<String> allTableNames = new ConcurrentLinkedQueue<>();

    private final CassandraWriteMetrics cassandraWriteMetrics;

    public Session(CqlSession wrappedSession, String keyspaceName,
                   @Nullable ConsistencyLevel writeConsistencyLevel, int maxConcurrentRequests, int gcGraceSeconds)
            throws Exception {
        this.wrappedSession = wrappedSession;
        this.keyspaceName = keyspaceName;
        this.writeConsistencyLevel = writeConsistencyLevel;

        readQuerySemaphore = new Semaphore(maxConcurrentRequests / 4);
        writeQuerySemaphore = new Semaphore(maxConcurrentRequests / 2);
        rollupQuerySemaphore = new Semaphore(maxConcurrentRequests / 4);
        this.gcGraceSeconds = gcGraceSeconds;

        cassandraWriteMetrics = new CassandraWriteMetrics(wrappedSession, keyspaceName);

        if (!wrappedSession.getMetadata().getKeyspace(keyspaceName).isPresent()) {
            // "create keyspace if not exists" requires create permission on all keyspaces
            // so only run it if needed, to allow the central collector to be run under more
            // restrictive Cassandra permission if desired
            updateSchemaWithRetry(wrappedSession, "create keyspace if not exists " + keyspaceName
                    + " with replication = { 'class' : 'SimpleStrategy', 'replication_factor'"
                    + " : 1 }");
        }
        wrappedSession.execute(SimpleStatement.newInstance("use " + keyspaceName));

        MBeanServer platformMBeanServer = ManagementFactory.getPlatformMBeanServer();
        platformMBeanServer.registerMBean(new SemaphoreStats(readQuerySemaphore),
                ObjectName.getInstance("org.glowroot.central:type=ReadQuerySemaphore"));
        platformMBeanServer.registerMBean(new SemaphoreStats(writeQuerySemaphore),
                ObjectName.getInstance("org.glowroot.central:type=WriteQuerySemaphore"));
        platformMBeanServer.registerMBean(new SemaphoreStats(rollupQuerySemaphore),
                ObjectName.getInstance("org.glowroot.central:type=RollupQuerySemaphore"));
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
        return wrappedSession.execute(SimpleStatement.newInstance(query));
    }

    public ResultSet read(Statement<?> statement) {
        if (statement instanceof BoundStatement) {
            BoundStatement boundStatement = (BoundStatement) statement;
            PreparedStatement preparedStatement = boundStatement.getPreparedStatement();
            String queryString = preparedStatement.getQuery();
            if (!queryString.startsWith("select ")) {
                throw new IllegalStateException("Unexpected read query: " + queryString);
            }
        }
        return wrappedSession.execute(statement);
    }

    public void write(Statement<?> statement) throws Exception {
        if (statement instanceof BoundStatement) {
            BoundStatement boundStatement = (BoundStatement) statement;
            PreparedStatement preparedStatement = boundStatement.getPreparedStatement();
            String queryString = preparedStatement.getQuery();
            if (!queryString.startsWith("insert ") && !queryString.startsWith("delete ")) {
                throw new IllegalStateException("Unexpected write query: " + queryString);
            }
        }
        // do not use session.execute() because that calls getUninterruptibly() which can cause
        // central shutdown to timeout while waiting for executor service to shutdown
        writeAsync(statement).toCompletableFuture().get();
    }

    public AsyncResultSet update(Statement<?> statement) throws Exception {
        if (statement instanceof BoundStatement) {
            BoundStatement boundStatement = (BoundStatement) statement;
            PreparedStatement preparedStatement = boundStatement.getPreparedStatement();
            String queryString = preparedStatement.getQuery();
            if (!queryString.contains(" if ")) {
                throw new IllegalStateException("Unexpected update query: " + queryString);
            }
            if (!queryString.startsWith("update ") && !queryString.startsWith("insert ")) {
                throw new IllegalStateException("Unexpected update query: " + queryString);
            }
        }
        // do not use session.execute() because that calls getUninterruptibly() which can cause
        // central shutdown to timeout while waiting for executor service to shutdown
        return updateAsync(statement).toCompletableFuture().get();
    }

    public CompletionStage<AsyncResultSet> readAsyncWarnIfNoRows(Statement<?> statement,
                                                                 String warningMessage, Object... warningArguments) {
        return readAsync(statement).thenApply(results -> {
            if (!results.currentPage().iterator().hasNext()) {
                logger.warn(warningMessage, warningArguments);
            }
            return results;
        });
    }

    public CompletionStage<AsyncResultSet> readAsync(Statement<?> statement) {
        return throttleRead(() -> wrappedSession.executeAsync(statement));
    }

    public CompletionStage<AsyncResultSet> writeAsync(Statement<?> statement) {
        if (statement.getConsistencyLevel() == null && writeConsistencyLevel != null) {
            statement = statement.setConsistencyLevel(writeConsistencyLevel);
        }
        final Statement<?> finalStatement = statement;
        return throttleWrite(() -> {
            // for now, need to record metrics in the same method because CassandraWriteMetrics
            // relies on some thread locals
            cassandraWriteMetrics.recordMetrics(finalStatement);
            return wrappedSession.executeAsync(finalStatement);
        });
    }

    private CompletionStage<AsyncResultSet> updateAsync(Statement<?> statement) {
        return throttleWrite(() -> wrappedSession.executeAsync(statement));
    }

    public String getKeyspaceName() {
        return keyspaceName;
    }

    public @Nullable TableMetadata getTable(String name) {
        Optional<KeyspaceMetadata> keyspace = getKeyspace();
        return keyspace.flatMap(keyspaceMetadata -> keyspaceMetadata.getTable(name)).orElse(null);
    }

    public Collection<TableMetadata> getTables() {
        return getKeyspace().map(keyspaceMetadata -> keyspaceMetadata.getTables().values()).orElseGet(ImmutableList::of);
    }

    public Collection<String> getAllTableNames() {
        return allTableNames;
    }

    Optional<KeyspaceMetadata> getKeyspace() {
        return wrappedSession.getMetadata().getKeyspace(keyspaceName);
    }

    public Metadata getMetadata() {
        return wrappedSession.getMetadata();
    }

    @Override
    public void close() throws Exception {
        MBeanServer platformMBeanServer = ManagementFactory.getPlatformMBeanServer();
        platformMBeanServer.unregisterMBean(
                ObjectName.getInstance("org.glowroot.central:type=ReadQuerySemaphore"));
        platformMBeanServer.unregisterMBean(
                ObjectName.getInstance("org.glowroot.central:type=WriteQuerySemaphore"));
        platformMBeanServer.unregisterMBean(
                ObjectName.getInstance("org.glowroot.central:type=RollupQuerySemaphore"));
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

    public void updateTableTwcsProperties(String tableName, int expirationHours)
            throws InterruptedException {
        updateSchemaWithRetry(
                "alter table " + tableName + " with " + getTwcsCompactionClause(expirationHours));
    }

    public void updateTableTwcsProperties(String tableName, String compactionWindowUnit,
            int compactionWindowSize) throws InterruptedException {
        updateSchemaWithRetry("alter table " + tableName + " with "
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
        writeQuerySemaphore.acquire();
        try {
            updateSchemaWithRetry(wrappedSession, query);
        } finally {
            writeQuerySemaphore.release();
        }
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

    public static boolean isInRollupThread() {
        return inRollupThread.get();
    }

    public static void setInRollupThread(boolean value) {
        inRollupThread.set(value);
    }

    private CompletionStage<AsyncResultSet> throttleRead(DoUnderThrottle doUnderThrottle) {
        if (inRollupThread.get()) {
            return throttle(doUnderThrottle, rollupQuerySemaphore);
        } else {
            return throttle(doUnderThrottle, readQuerySemaphore);
        }
    }

    private CompletionStage<AsyncResultSet> throttleWrite(DoUnderThrottle doUnderThrottle) {
        if (inRollupThread.get()) {
            return throttle(doUnderThrottle, rollupQuerySemaphore);
        } else {
            return throttle(doUnderThrottle, writeQuerySemaphore);
        }
    }

    private static CompletionStage<AsyncResultSet> throttle(DoUnderThrottle doUnderThrottle,
                                                            Semaphore overallSemaphore) {
        try {
            overallSemaphore.acquire();
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
        return doUnderThrottle.execute().whenCompleteAsync((results, throwable) -> {
            overallSemaphore.release();
        });
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

    // creating/dropping/truncating keyspaces/tables can timeout, throwing AllNodesFailedException
    // (see https://github.com/glowroot/glowroot/issues/351)
    private static void updateSchemaWithRetry(CqlSession wrappedSession,
            String query) throws InterruptedException {
        Stopwatch stopwatch = Stopwatch.createStarted();
        while (stopwatch.elapsed(SECONDS) < 60) {
            try {
                SimpleStatement stmt = SimpleStatement.builder(query).setExecutionProfileName(CassandraProfile.SLOW.name()).build();
                wrappedSession.execute(stmt);
                return;
            } catch (AllNodesFailedException e) {
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
        CompletionStage<AsyncResultSet> execute();
    }
}
