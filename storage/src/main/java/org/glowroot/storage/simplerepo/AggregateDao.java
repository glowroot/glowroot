/*
 * Copyright 2013-2015 the original author or authors.
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
package org.glowroot.storage.simplerepo;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.List;
import java.util.concurrent.atomic.AtomicLongArray;

import javax.annotation.Nullable;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;
import com.google.protobuf.AbstractMessageLite;
import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.Parser;
import org.checkerframework.checker.tainting.qual.Untainted;
import org.immutables.value.Value;

import org.glowroot.common.live.ImmutableOverallErrorSummary;
import org.glowroot.common.live.ImmutableOverallSummary;
import org.glowroot.common.live.ImmutableOverviewAggregate;
import org.glowroot.common.live.ImmutablePercentileAggregate;
import org.glowroot.common.live.ImmutableThroughputAggregate;
import org.glowroot.common.live.LiveAggregateRepository.OverallErrorSummary;
import org.glowroot.common.live.LiveAggregateRepository.OverallSummary;
import org.glowroot.common.live.LiveAggregateRepository.OverviewAggregate;
import org.glowroot.common.live.LiveAggregateRepository.PercentileAggregate;
import org.glowroot.common.live.LiveAggregateRepository.ThroughputAggregate;
import org.glowroot.common.model.LazyHistogram.ScratchBuffer;
import org.glowroot.common.model.QueryCollector;
import org.glowroot.common.util.Styles;
import org.glowroot.storage.repo.AggregateRepository;
import org.glowroot.storage.repo.ConfigRepository;
import org.glowroot.storage.repo.ConfigRepository.RollupConfig;
import org.glowroot.storage.repo.ImmutableErrorPoint;
import org.glowroot.storage.repo.MutableAggregate;
import org.glowroot.storage.repo.ProfileCollector;
import org.glowroot.storage.repo.TransactionErrorSummaryCollector;
import org.glowroot.storage.repo.TransactionSummaryCollector;
import org.glowroot.storage.repo.helper.RollupLevelService;
import org.glowroot.storage.simplerepo.util.CappedDatabase;
import org.glowroot.storage.simplerepo.util.DataSource;
import org.glowroot.storage.simplerepo.util.DataSource.JdbcQuery;
import org.glowroot.storage.simplerepo.util.DataSource.JdbcRowQuery;
import org.glowroot.storage.simplerepo.util.DataSource.JdbcUpdate;
import org.glowroot.storage.simplerepo.util.ImmutableColumn;
import org.glowroot.storage.simplerepo.util.ImmutableIndex;
import org.glowroot.storage.simplerepo.util.RowMappers;
import org.glowroot.storage.simplerepo.util.Schemas.Column;
import org.glowroot.storage.simplerepo.util.Schemas.ColumnType;
import org.glowroot.storage.simplerepo.util.Schemas.Index;
import org.glowroot.wire.api.model.AggregateOuterClass.Aggregate;
import org.glowroot.wire.api.model.AggregateOuterClass.Aggregate.QueriesByType;
import org.glowroot.wire.api.model.AggregateOuterClass.AggregatesByType;
import org.glowroot.wire.api.model.AggregateOuterClass.TransactionAggregate;
import org.glowroot.wire.api.model.ProfileOuterClass.Profile;

import static com.google.common.base.Preconditions.checkNotNull;
import static org.glowroot.storage.simplerepo.util.Checkers.castUntainted;

public class AggregateDao implements AggregateRepository {

    private static final String SERVER_ID = "";

    private static final ImmutableList<Column> overallAggregatePointColumns =
            ImmutableList.<Column>of(
                    ImmutableColumn.of("transaction_type", ColumnType.VARCHAR),
                    ImmutableColumn.of("capture_time", ColumnType.BIGINT),
                    ImmutableColumn.of("total_nanos", ColumnType.DOUBLE),
                    ImmutableColumn.of("transaction_count", ColumnType.BIGINT),
                    ImmutableColumn.of("error_count", ColumnType.BIGINT),
                    ImmutableColumn.of("total_cpu_nanos", ColumnType.BIGINT),
                    ImmutableColumn.of("total_blocked_nanos", ColumnType.BIGINT),
                    ImmutableColumn.of("total_waited_nanos", ColumnType.BIGINT),
                    ImmutableColumn.of("total_allocated_bytes", ColumnType.BIGINT),
                    ImmutableColumn.of("profile_capped_id", ColumnType.BIGINT),
                    ImmutableColumn.of("queries_capped_id", ColumnType.BIGINT),
                    ImmutableColumn.of("root_timers", ColumnType.VARBINARY), // protobuf
                    ImmutableColumn.of("histogram", ColumnType.VARBINARY)); // protobuf

    private static final ImmutableList<Column> transactionAggregateColumns =
            ImmutableList.<Column>of(
                    ImmutableColumn.of("transaction_type", ColumnType.VARCHAR),
                    ImmutableColumn.of("transaction_name", ColumnType.VARCHAR),
                    ImmutableColumn.of("capture_time", ColumnType.BIGINT),
                    ImmutableColumn.of("total_nanos", ColumnType.DOUBLE),
                    ImmutableColumn.of("transaction_count", ColumnType.BIGINT),
                    ImmutableColumn.of("error_count", ColumnType.BIGINT),
                    ImmutableColumn.of("total_cpu_nanos", ColumnType.BIGINT),
                    ImmutableColumn.of("total_blocked_nanos", ColumnType.BIGINT),
                    ImmutableColumn.of("total_waited_nanos", ColumnType.BIGINT),
                    ImmutableColumn.of("total_allocated_bytes", ColumnType.BIGINT),
                    ImmutableColumn.of("profile_capped_id", ColumnType.BIGINT),
                    ImmutableColumn.of("queries_capped_id", ColumnType.BIGINT),
                    ImmutableColumn.of("root_timers", ColumnType.VARBINARY), // protobuf
                    ImmutableColumn.of("histogram", ColumnType.VARBINARY)); // protobuf

    // this index includes all columns needed for the overall aggregate query so h2 can return
    // the result set directly from the index without having to reference the table for each row
    private static final ImmutableList<String> overallAggregateIndexColumns =
            ImmutableList.of("capture_time", "transaction_type", "total_nanos", "transaction_count",
                    "error_count");

    // this index includes all columns needed for the transaction aggregate query so h2 can return
    // the result set directly from the index without having to reference the table for each row
    //
    // capture_time is first so this can also be used for readTransactionErrorCounts()
    private static final ImmutableList<String> transactionAggregateIndexColumns =
            ImmutableList.of("capture_time", "transaction_type", "transaction_name", "total_nanos",
                    "transaction_count", "error_count");

    private final DataSource dataSource;
    private final List<CappedDatabase> rollupCappedDatabases;
    private final ConfigRepository configRepository;
    private final TransactionTypeDao transactionTypeDao;

    private final AtomicLongArray lastRollupTimes;

    private final Object rollupLock = new Object();

    AggregateDao(DataSource dataSource, List<CappedDatabase> rollupCappedDatabases,
            ConfigRepository configRepository, TransactionTypeDao transactionTypeDao)
                    throws Exception {
        this.dataSource = dataSource;
        this.rollupCappedDatabases = rollupCappedDatabases;
        this.configRepository = configRepository;
        this.transactionTypeDao = transactionTypeDao;

        List<RollupConfig> rollupConfigs = configRepository.getRollupConfigs();
        for (int i = 0; i < rollupConfigs.size(); i++) {
            String overallTableName = "aggregate_overall_rollup_" + castUntainted(i);
            dataSource.syncTable(overallTableName, overallAggregatePointColumns);
            dataSource.syncIndexes(overallTableName, ImmutableList.<Index>of(
                    ImmutableIndex.of(overallTableName + "_idx", overallAggregateIndexColumns)));
            String transactionTableName = "aggregate_transaction_rollup_" + castUntainted(i);
            dataSource.syncTable(transactionTableName, transactionAggregateColumns);
            dataSource.syncIndexes(transactionTableName, ImmutableList.<Index>of(ImmutableIndex
                    .of(transactionTableName + "_idx", transactionAggregateIndexColumns)));
        }

        // don't need last_rollup_times table like in GaugePointDao since there is already index
        // on capture_time so these queries are relatively fast
        long[] lastRollupTimes = new long[rollupConfigs.size()];
        lastRollupTimes[0] = 0;
        for (int i = 1; i < lastRollupTimes.length; i++) {
            lastRollupTimes[i] = dataSource.queryForLong(
                    "select ifnull(max(capture_time), 0) from aggregate_overall_rollup_"
                            + castUntainted(i));
        }
        this.lastRollupTimes = new AtomicLongArray(lastRollupTimes);

        // TODO initial rollup in case store is not called in a reasonable time
    }

    @Override
    public void store(String serverId, long captureTime, List<AggregatesByType> aggregatesByType)
            throws Exception {
        // intentionally not using batch update as that could cause memory spike while preparing a
        // large batch
        for (AggregatesByType aggregatesByType1 : aggregatesByType) {
            String transactionType = aggregatesByType1.getTransactionType();

            dataSource.update(new AggregateInsert(transactionType, null, captureTime,
                    aggregatesByType1.getOverallAggregate(), 0));
            transactionTypeDao.updateLastCaptureTime(transactionType, captureTime);

            for (TransactionAggregate transactionAggregate : aggregatesByType1
                    .getTransactionAggregateList()) {
                dataSource.update(new AggregateInsert(transactionType,
                        transactionAggregate.getTransactionName(), captureTime,
                        transactionAggregate.getAggregate(), 0));
            }
        }
        synchronized (rollupLock) {
            List<RollupConfig> rollupConfigs = configRepository.getRollupConfigs();
            for (int i = 1; i < rollupConfigs.size(); i++) {
                RollupConfig rollupConfig = rollupConfigs.get(i);
                long safeRollupTime = RollupLevelService.getSafeRollupTime(captureTime,
                        rollupConfig.intervalMillis());
                long lastRollupTime = lastRollupTimes.get(i);
                if (safeRollupTime > lastRollupTime) {
                    rollup(lastRollupTime, safeRollupTime, rollupConfig.intervalMillis(), i, i - 1);
                    lastRollupTimes.set(i, safeRollupTime);
                }
            }
        }
    }

    // query.from() is non-inclusive
    @Override
    public OverallSummary readOverallSummary(OverallQuery query) throws Exception {
        return dataSource.query(new OverallSummaryQuery(query));
    }

    // query.from() is non-inclusive
    @Override
    public void mergeInTransactionSummaries(TransactionSummaryCollector mergedTransactionSummaries,
            OverallQuery query, SummarySortOrder sortOrder, int limit) throws Exception {
        dataSource.query(
                new TransactionSummaryQuery(query, sortOrder, limit, mergedTransactionSummaries));
    }

    // query.from() is non-inclusive
    @Override
    public OverallErrorSummary readOverallErrorSummary(OverallQuery query) throws Exception {
        return dataSource.query(new OverallErrorSummaryQuery(query));
    }

    // query.from() is non-inclusive
    @Override
    public void mergeInTransactionErrorSummaries(
            TransactionErrorSummaryCollector mergedTransactionErrorSummaries, OverallQuery query,
            ErrorSummarySortOrder sortOrder, int limit) throws Exception {
        dataSource.query(new TransactionErrorSummaryQuery(query, sortOrder, limit,
                mergedTransactionErrorSummaries));
    }

    // query.from() is INCLUSIVE
    @Override
    public List<OverviewAggregate> readOverviewAggregates(TransactionQuery query) throws Exception {
        return dataSource.query(new OverviewAggregateQuery(query));
    }

    // query.from() is INCLUSIVE
    @Override
    public List<PercentileAggregate> readPercentileAggregates(TransactionQuery query)
            throws Exception {
        return dataSource.query(new PercentileAggregateQuery(query));
    }

    // query.from() is INCLUSIVE
    @Override
    public List<ThroughputAggregate> readThroughputAggregates(TransactionQuery query)
            throws Exception {
        return dataSource.query(new ThroughputAggregateQuery(query));
    }

    // query.from() is non-inclusive
    @Override
    public void mergeInProfiles(ProfileCollector mergedProfile, TransactionQuery query)
            throws Exception {
        // get list of capped ids first since that is done under the data source lock
        // then do the expensive part of reading and constructing the protobuf messages outside of
        // the data source lock
        List<CappedId> cappedIds = dataSource.query(new CappedIdQuery("profile_capped_id", query));
        long captureTime = Long.MIN_VALUE;
        for (CappedId cappedId : cappedIds) {
            captureTime = Math.max(captureTime, cappedId.captureTime());
            Profile profile = rollupCappedDatabases.get(query.rollupLevel())
                    .readMessage(cappedId.cappedId(), Profile.parser());
            if (profile != null) {
                mergedProfile.mergeProfile(profile);
                mergedProfile.updateLastCaptureTime(captureTime);
            }
        }
    }

    // query.from() is non-inclusive
    @Override
    public void mergeInQueries(QueryCollector mergedQueries, TransactionQuery query)
            throws Exception {
        // get list of capped ids first since that is done under the data source lock
        // then do the expensive part of reading and constructing the protobuf messages outside of
        // the data source lock
        List<CappedId> cappedIds = dataSource.query(new CappedIdQuery("queries_capped_id", query));
        long captureTime = Long.MIN_VALUE;
        for (CappedId cappedId : cappedIds) {
            captureTime = Math.max(captureTime, cappedId.captureTime());
            List<Aggregate.QueriesByType> queries = rollupCappedDatabases.get(query.rollupLevel())
                    .readMessages(cappedId.cappedId(), Aggregate.QueriesByType.parser());
            if (queries != null) {
                mergedQueries.mergeQueries(queries);
                mergedQueries.updateLastCaptureTime(captureTime);
            }
        }
    }

    // query.from() is INCLUSIVE
    @Override
    public List<ErrorPoint> readErrorPoints(TransactionQuery query) throws Exception {
        return dataSource.query(new ErrorPointQuery(query));
    }

    // query.from() is non-inclusive
    @Override
    public boolean shouldHaveProfile(TransactionQuery query) throws Exception {
        return dataSource.query(new ShouldHaveSomethingQuery(query, "profile_capped_id"));
    }

    // query.from() is non-inclusive
    @Override
    public boolean shouldHaveQueries(TransactionQuery query) throws Exception {
        return dataSource.query(new ShouldHaveSomethingQuery(query, "queries_capped_id"));
    }

    @Override
    public void deleteAll(String serverRollup) throws Exception {
        for (int i = 0; i < configRepository.getRollupConfigs().size(); i++) {
            dataSource.execute("truncate table aggregate_overall_rollup_" + castUntainted(i));
            dataSource.execute("truncate table aggregate_transaction_rollup_" + castUntainted(i));
        }
    }

    void deleteBefore(long captureTime, int rollupLevel) throws Exception {
        dataSource.deleteBefore("aggregate_overall_rollup_" + castUntainted(rollupLevel),
                captureTime);
        dataSource.deleteBefore("aggregate_transaction_rollup_" + castUntainted(rollupLevel),
                captureTime);
    }

    private void rollup(long lastRollupTime, long curentRollupTime, long fixedIntervalMillis,
            int toRollupLevel, int fromRollupLevel) throws Exception {
        List<Long> rollupTimes = dataSource.query(new RollupTimeRowMapper(fromRollupLevel,
                fixedIntervalMillis, lastRollupTime, curentRollupTime));
        for (Long rollupTime : rollupTimes) {
            dataSource.query(new RollupOverallAggregates(rollupTime, fixedIntervalMillis,
                    fromRollupLevel, toRollupLevel));
            dataSource.query(new RollupTransactionAggregates(rollupTime, fixedIntervalMillis,
                    fromRollupLevel, toRollupLevel));
        }
    }

    private void merge(MutableAggregate mergedAggregate, ResultSet resultSet, int startColumnIndex,
            int fromRollupLevel) throws Exception {
        int i = startColumnIndex;
        double totalNanos = resultSet.getDouble(i++);
        long transactionCount = resultSet.getLong(i++);
        long errorCount = resultSet.getLong(i++);
        double totalCpuNanos = RowMappers.getNotAvailableAwareDouble(resultSet, i++);
        double totalBlockedNanos = RowMappers.getNotAvailableAwareDouble(resultSet, i++);
        double totalWaitedNanos = RowMappers.getNotAvailableAwareDouble(resultSet, i++);
        double totalAllocatedBytes = RowMappers.getNotAvailableAwareDouble(resultSet, i++);
        Long profileCappedId = RowMappers.getLong(resultSet, i++);
        Long queriesCappedId = RowMappers.getLong(resultSet, i++);
        byte[] rootTimers = checkNotNull(resultSet.getBytes(i++));
        byte[] histogram = checkNotNull(resultSet.getBytes(i++));

        mergedAggregate.addTotalNanos(totalNanos);
        mergedAggregate.addTransactionCount(transactionCount);
        mergedAggregate.addErrorCount(errorCount);
        mergedAggregate.addTotalCpuNanos(totalCpuNanos);
        mergedAggregate.addTotalBlockedNanos(totalBlockedNanos);
        mergedAggregate.addTotalWaitedNanos(totalWaitedNanos);
        mergedAggregate.addTotalAllocatedBytes(totalAllocatedBytes);
        mergedAggregate.mergeRootTimers(readMessages(rootTimers, Aggregate.Timer.parser()));
        mergedAggregate.mergeHistogram(Aggregate.Histogram.parseFrom(histogram));
        if (profileCappedId != null) {
            Profile profile = rollupCappedDatabases.get(fromRollupLevel)
                    .readMessage(profileCappedId, Profile.parser());
            if (profile != null) {
                mergedAggregate.mergeProfile(profile);
            }
        }
        if (queriesCappedId != null) {
            List<Aggregate.QueriesByType> queries = rollupCappedDatabases.get(fromRollupLevel)
                    .readMessages(queriesCappedId, Aggregate.QueriesByType.parser());
            if (queries != null) {
                mergedAggregate.mergeQueries(queries);
            }
        }
    }

    private static @Untainted String getTableName(TransactionQuery query) {
        if (query.transactionName() == null) {
            return "aggregate_overall_rollup_" + castUntainted(query.rollupLevel());
        } else {
            return "aggregate_transaction_rollup_" + castUntainted(query.rollupLevel());
        }
    }

    private static @Untainted String getTransactionNameCriteria(TransactionQuery query) {
        if (query.transactionName() == null) {
            return "";
        } else {
            return " and transaction_name = ?";
        }
    }

    private static int bindQuery(PreparedStatement preparedStatement, TransactionQuery query)
            throws SQLException {
        int i = 1;
        preparedStatement.setString(i++, query.transactionType());
        String transactionName = query.transactionName();
        if (transactionName != null) {
            preparedStatement.setString(i++, transactionName);
        }
        preparedStatement.setLong(i++, query.from());
        preparedStatement.setLong(i++, query.to());
        return i;
    }

    private static <T extends /*@NonNull*/Object> List<T> readMessages(byte[] bytes,
            Parser<T> parser) throws InvalidProtocolBufferException {
        ByteArrayInputStream bais = new ByteArrayInputStream(bytes);
        List<T> messages = Lists.newArrayList();
        T message;
        while ((message = parser.parseDelimitedFrom(bais)) != null) {
            messages.add(message);
        }
        return messages;
    }

    private class AggregateInsert implements JdbcUpdate {

        private final String transactionType;
        private final @Nullable String transactionName;
        private final long captureTime;
        private final Aggregate aggregate;
        private final @Nullable Long profileCappedId;
        private final @Nullable Long queriesCappedId;
        private final byte[] rootTimers;
        private final byte[] histogramBytes;

        private final int rollupLevel;

        private AggregateInsert(String transactionType, @Nullable String transactionName,
                long captureTime, Aggregate aggregate, int rollupLevel) throws IOException {
            this.transactionType = transactionType;
            this.transactionName = transactionName;
            this.captureTime = captureTime;
            this.aggregate = aggregate;
            this.rollupLevel = rollupLevel;

            if (aggregate.hasProfile()) {
                profileCappedId = rollupCappedDatabases.get(rollupLevel).writeMessage(
                        aggregate.getProfile(), RollupCappedDatabaseStats.AGGREGATE_PROFILES);
            } else {
                profileCappedId = null;
            }
            List<QueriesByType> queries = aggregate.getQueriesByTypeList();
            if (queries.isEmpty()) {
                queriesCappedId = null;
            } else {
                queriesCappedId = rollupCappedDatabases.get(rollupLevel).writeMessages(queries,
                        RollupCappedDatabaseStats.AGGREGATE_QUERIES);
            }
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            for (AbstractMessageLite message : aggregate.getRootTimerList()) {
                message.writeDelimitedTo(baos);
            }
            rootTimers = baos.toByteArray();
            histogramBytes = aggregate.getTotalNanosHistogram().toByteArray();
        }

        @Override
        public @Untainted String getSql() {
            StringBuilder sb = new StringBuilder();
            sb.append("insert into aggregate_");
            if (transactionName != null) {
                sb.append("transaction");
            } else {
                sb.append("overall");
            }
            sb.append("_rollup_");
            sb.append(castUntainted(rollupLevel));
            sb.append(" (transaction_type,");
            if (transactionName != null) {
                sb.append(" transaction_name,");
            }
            sb.append(" capture_time, total_nanos, transaction_count, error_count, total_cpu_nanos,"
                    + " total_blocked_nanos, total_waited_nanos, total_allocated_bytes,"
                    + " profile_capped_id, queries_capped_id, root_timers, histogram)"
                    + " values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?");
            if (transactionName != null) {
                sb.append(", ?");
            }
            sb.append(")");
            return castUntainted(sb.toString());
        }

        // minimal work inside this method as it is called with active connection
        @Override
        public void bind(PreparedStatement preparedStatement) throws SQLException {
            int i = 1;
            preparedStatement.setString(i++, transactionType);
            if (transactionName != null) {
                preparedStatement.setString(i++, transactionName);
            }
            preparedStatement.setLong(i++, captureTime);
            preparedStatement.setDouble(i++, aggregate.getTotalNanos());
            preparedStatement.setLong(i++, aggregate.getTransactionCount());
            preparedStatement.setLong(i++, aggregate.getErrorCount());
            if (aggregate.hasTotalCpuNanos()) {
                preparedStatement.setDouble(i++, aggregate.getTotalCpuNanos().getValue());
            } else {
                preparedStatement.setNull(i++, Types.BIGINT);
            }
            if (aggregate.hasTotalBlockedNanos()) {
                preparedStatement.setDouble(i++, aggregate.getTotalBlockedNanos().getValue());
            } else {
                preparedStatement.setNull(i++, Types.BIGINT);
            }
            if (aggregate.hasTotalWaitedNanos()) {
                preparedStatement.setDouble(i++, aggregate.getTotalWaitedNanos().getValue());
            } else {
                preparedStatement.setNull(i++, Types.BIGINT);
            }
            if (aggregate.hasTotalAllocatedBytes()) {
                preparedStatement.setDouble(i++, aggregate.getTotalAllocatedBytes().getValue());
            } else {
                preparedStatement.setNull(i++, Types.BIGINT);
            }
            RowMappers.setLong(preparedStatement, i++, profileCappedId);
            RowMappers.setLong(preparedStatement, i++, queriesCappedId);
            preparedStatement.setBytes(i++, rootTimers);
            preparedStatement.setBytes(i++, histogramBytes);
        }
    }

    private static class OverallSummaryQuery implements JdbcQuery<OverallSummary> {

        private final OverallQuery query;

        private OverallSummaryQuery(OverallQuery query) {
            this.query = query;
        }

        @Override
        public @Untainted String getSql() {
            // it's important that all these columns are in a single index so h2 can return the
            // result set directly from the index without having to reference the table for each row
            return "select sum(total_nanos), sum(transaction_count), max(capture_time)"
                    + " from aggregate_overall_rollup_" + castUntainted(query.rollupLevel())
                    + " where transaction_type = ? and capture_time > ? and capture_time <= ?";
        }

        @Override
        public void bind(PreparedStatement preparedStatement) throws Exception {
            preparedStatement.setString(1, query.transactionType());
            preparedStatement.setLong(2, query.from());
            preparedStatement.setLong(3, query.to());
        }

        @Override
        public OverallSummary processResultSet(ResultSet resultSet) throws Exception {
            if (!resultSet.next()) {
                // this is an aggregate query so this should be impossible
                throw new SQLException("Aggregate query did not return any results");
            }
            return ImmutableOverallSummary.builder()
                    .totalNanos(resultSet.getDouble(1))
                    .transactionCount(resultSet.getLong(2))
                    .lastCaptureTime(resultSet.getLong(3))
                    .build();
        }

        @Override
        public OverallSummary valueIfDataSourceClosing() {
            return ImmutableOverallSummary.builder().build();
        }
    }

    private class TransactionSummaryQuery implements JdbcQuery</*@Nullable*/Void> {

        private final OverallQuery query;
        private final SummarySortOrder sortOrder;
        private final int limit;

        private final TransactionSummaryCollector mergedTransactionSummaries;

        private TransactionSummaryQuery(OverallQuery query, SummarySortOrder sortOrder,
                int limit, TransactionSummaryCollector mergedTransactionSummaries) {
            this.query = query;
            this.sortOrder = sortOrder;
            this.limit = limit;
            this.mergedTransactionSummaries = mergedTransactionSummaries;
        }

        @Override
        public @Untainted String getSql() {
            // it's important that all these columns are in a single index so h2 can return the
            // result set directly from the index without having to reference the table for each row
            StringBuilder sb = new StringBuilder();
            sb.append("select transaction_name, sum(total_nanos), sum(transaction_count),"
                    + " max(capture_time) from aggregate_transaction_rollup_");
            sb.append(query.rollupLevel());
            sb.append(" where transaction_type = ? and capture_time > ? and capture_time <= ?"
                    + " group by transaction_name order by ");
            sb.append(getSortClause(sortOrder));
            sb.append(", transaction_name limit ?");
            return castUntainted(sb.toString());
        }

        @Override
        public void bind(PreparedStatement preparedStatement) throws SQLException {
            preparedStatement.setString(1, query.transactionType());
            preparedStatement.setLong(2, query.from());
            preparedStatement.setLong(3, query.to());
            // limit + 100 since this result still needs to be merged with other results
            preparedStatement.setInt(4, limit + 100);
        }

        @Override
        public @Nullable Void processResultSet(ResultSet resultSet) throws Exception {
            while (resultSet.next()) {
                String transactionName = checkNotNull(resultSet.getString(1));
                double totalNanos = resultSet.getDouble(2);
                long transactionCount = resultSet.getLong(3);
                long maxCaptureTime = resultSet.getLong(4);
                mergedTransactionSummaries.collect(transactionName, totalNanos, transactionCount,
                        maxCaptureTime);
            }
            return null;
        }

        @Override
        public @Nullable Void valueIfDataSourceClosing() {
            return null;
        }

        private @Untainted String getSortClause(SummarySortOrder sortOrder) {
            switch (sortOrder) {
                case TOTAL_TIME:
                    return "sum(total_nanos) desc";
                case AVERAGE_TIME:
                    return "sum(total_nanos) / sum(transaction_count) desc";
                case THROUGHPUT:
                    return "sum(transaction_count) desc";
                default:
                    throw new AssertionError("Unexpected sort order: " + sortOrder);
            }
        }
    }

    private static class OverallErrorSummaryQuery implements JdbcQuery<OverallErrorSummary> {

        private final OverallQuery query;

        private OverallErrorSummaryQuery(OverallQuery query) {
            this.query = query;
        }

        @Override
        public @Untainted String getSql() {
            return "select sum(error_count), sum(transaction_count), max(capture_time)"
                    + " from aggregate_overall_rollup_" + castUntainted(query.rollupLevel())
                    + " where transaction_type = ? and capture_time > ? and capture_time <= ?";
        }

        @Override
        public void bind(PreparedStatement preparedStatement) throws Exception {
            preparedStatement.setString(1, query.transactionType());
            preparedStatement.setLong(2, query.from());
            preparedStatement.setLong(3, query.to());
        }

        @Override
        public OverallErrorSummary processResultSet(ResultSet resultSet) throws Exception {
            if (!resultSet.next()) {
                // this is an aggregate query so this should be impossible
                throw new SQLException("Aggregate query did not return any results");
            }
            return ImmutableOverallErrorSummary.builder()
                    .errorCount(resultSet.getLong(1))
                    .transactionCount(resultSet.getLong(2))
                    .lastCaptureTime(resultSet.getLong(3))
                    .build();
        }

        @Override
        public OverallErrorSummary valueIfDataSourceClosing() {
            return ImmutableOverallErrorSummary.builder().build();
        }
    }

    private class TransactionErrorSummaryQuery implements JdbcQuery</*@Nullable*/Void> {

        private final OverallQuery query;
        private final ErrorSummarySortOrder sortOrder;
        private final int limit;

        private final TransactionErrorSummaryCollector mergedTransactionErrorSummaries;

        private TransactionErrorSummaryQuery(OverallQuery query, ErrorSummarySortOrder sortOrder,
                int limit, TransactionErrorSummaryCollector mergedTransactionErrorSummaries) {
            this.query = query;
            this.sortOrder = sortOrder;
            this.limit = limit;
            this.mergedTransactionErrorSummaries = mergedTransactionErrorSummaries;
        }

        @Override
        public @Untainted String getSql() {
            // it's important that all these columns are in a single index so h2 can return the
            // result set directly from the index without having to reference the table for each row
            StringBuilder sb = new StringBuilder();
            sb.append("select transaction_name, sum(error_count), sum(transaction_count),");
            sb.append(" max(capture_time) from aggregate_transaction_rollup_");
            sb.append(castUntainted(query.rollupLevel()));
            sb.append(" where transaction_type = ? and capture_time > ? and capture_time <= ?"
                    + " group by transaction_name having sum(error_count) > 0 order by ");
            sb.append(getSortClause(sortOrder));
            sb.append(", transaction_name limit ?");
            return castUntainted(sb.toString());
        }

        @Override
        public void bind(PreparedStatement preparedStatement) throws SQLException {
            preparedStatement.setString(1, query.transactionType());
            preparedStatement.setLong(2, query.from());
            preparedStatement.setLong(3, query.to());
            // limit + 100 since this result still needs to be merged with other results
            preparedStatement.setInt(4, limit + 100);
        }

        @Override
        public @Nullable Void processResultSet(ResultSet resultSet) throws Exception {
            while (resultSet.next()) {
                String transactionName = checkNotNull(resultSet.getString(1));
                long errorCount = resultSet.getLong(2);
                long transactionCount = resultSet.getLong(3);
                long maxCaptureTime = resultSet.getLong(4);
                mergedTransactionErrorSummaries.collect(transactionName, errorCount,
                        transactionCount, maxCaptureTime);
            }
            return null;
        }

        @Override
        public @Nullable Void valueIfDataSourceClosing() {
            return null;
        }

        private @Untainted String getSortClause(ErrorSummarySortOrder sortOrder) {
            switch (sortOrder) {
                case ERROR_COUNT:
                    return "sum(error_count) desc";
                case ERROR_RATE:
                    return "sum(error_count) / sum(transaction_count) desc";
                default:
                    throw new AssertionError("Unexpected sort order: " + sortOrder);
            }
        }

    }

    private static class OverviewAggregateQuery implements JdbcRowQuery<OverviewAggregate> {

        private final TransactionQuery query;

        private OverviewAggregateQuery(TransactionQuery query) {
            this.query = query;
        }

        @Override
        public @Untainted String getSql() {
            String tableName = getTableName(query);
            String transactionNameCriteria = getTransactionNameCriteria(query);
            return "select capture_time, total_nanos, transaction_count, total_cpu_nanos,"
                    + " total_blocked_nanos, total_waited_nanos, total_allocated_bytes, root_timers"
                    + " from " + tableName + " where transaction_type = ?" + transactionNameCriteria
                    + " and capture_time >= ? and capture_time <= ? order by capture_time";
        }

        @Override
        public void bind(PreparedStatement preparedStatement) throws SQLException {
            bindQuery(preparedStatement, query);
        }

        @Override
        public OverviewAggregate mapRow(ResultSet resultSet) throws Exception {
            int i = 1;
            ImmutableOverviewAggregate.Builder builder = ImmutableOverviewAggregate.builder()
                    .captureTime(resultSet.getLong(i++))
                    .totalNanos(resultSet.getDouble(i++))
                    .transactionCount(resultSet.getLong(i++))
                    .totalCpuNanos(resultSet.getDouble(i++))
                    .totalBlockedNanos(resultSet.getDouble(i++))
                    .totalWaitedNanos(resultSet.getDouble(i++))
                    .totalAllocatedBytes(resultSet.getDouble(i++));
            byte[] rootTimers = checkNotNull(resultSet.getBytes(i++));
            builder.rootTimers(readMessages(rootTimers, Aggregate.Timer.parser()));
            return builder.build();
        }
    }

    private static class PercentileAggregateQuery implements JdbcRowQuery<PercentileAggregate> {

        private final TransactionQuery query;

        private PercentileAggregateQuery(TransactionQuery query) {
            this.query = query;
        }

        @Override
        public @Untainted String getSql() {
            String tableName = getTableName(query);
            String transactionNameCriteria = getTransactionNameCriteria(query);
            return "select capture_time, total_nanos, transaction_count, histogram from "
                    + tableName + " where transaction_type = ?" + transactionNameCriteria
                    + " and capture_time >= ? and capture_time <= ? order by capture_time";
        }

        @Override
        public void bind(PreparedStatement preparedStatement) throws SQLException {
            bindQuery(preparedStatement, query);
        }

        @Override
        public PercentileAggregate mapRow(ResultSet resultSet) throws Exception {
            int i = 1;
            ImmutablePercentileAggregate.Builder builder = ImmutablePercentileAggregate.builder()
                    .captureTime(resultSet.getLong(i++))
                    .totalNanos(resultSet.getLong(i++))
                    .transactionCount(resultSet.getLong(i++));
            byte[] histogram = checkNotNull(resultSet.getBytes(i++));
            builder.histogram(Aggregate.Histogram.parser().parseFrom(histogram));
            return builder.build();
        }
    }

    private static class ThroughputAggregateQuery implements JdbcRowQuery<ThroughputAggregate> {

        private final TransactionQuery query;

        private ThroughputAggregateQuery(TransactionQuery query) {
            this.query = query;
        }

        @Override
        public @Untainted String getSql() {
            String tableName = getTableName(query);
            String transactionNameCriteria = getTransactionNameCriteria(query);
            return "select capture_time, transaction_count from " + tableName
                    + " where transaction_type = ?" + transactionNameCriteria
                    + " and capture_time >= ? and capture_time <= ? order by capture_time";
        }

        @Override
        public void bind(PreparedStatement preparedStatement) throws SQLException {
            bindQuery(preparedStatement, query);
        }

        @Override
        public ThroughputAggregate mapRow(ResultSet resultSet) throws Exception {
            int i = 1;
            return ImmutableThroughputAggregate.builder()
                    .captureTime(resultSet.getLong(i++))
                    .transactionCount(resultSet.getLong(i++))
                    .build();
        }
    }

    private static class ErrorPointQuery implements JdbcRowQuery<ErrorPoint> {

        private final TransactionQuery query;

        private ErrorPointQuery(TransactionQuery query) {
            this.query = query;
        }

        @Override
        public @Untainted String getSql() {
            String tableName = getTableName(query);
            String transactionNameCriteria = getTransactionNameCriteria(query);
            return "select capture_time, error_count, transaction_count from " + tableName
                    + " where transaction_type = ?" + transactionNameCriteria
                    + " and capture_time >= ? and capture_time <= ? and error_count > 0"
                    + " order by capture_time";
        }

        @Override
        public void bind(PreparedStatement preparedStatement) throws SQLException {
            bindQuery(preparedStatement, query);
        }

        @Override
        public ErrorPoint mapRow(ResultSet resultSet) throws SQLException {
            long captureTime = resultSet.getLong(1);
            long errorCount = resultSet.getLong(2);
            long transactionCount = resultSet.getLong(3);
            return ImmutableErrorPoint.of(captureTime, errorCount, transactionCount);
        }
    }

    private class RollupOverallAggregates implements JdbcQuery</*@Nullable*/Void> {

        private final long rollupCaptureTime;
        private final long fixedIntervalMillis;
        private final int fromRollupLevel;
        private final int toRollupLevel;
        private final ScratchBuffer scratchBuffer = new ScratchBuffer();

        private RollupOverallAggregates(long rollupCaptureTime, long fixedIntervalMillis,
                int fromRollupLevel, int toRollupLevel) {
            this.rollupCaptureTime = rollupCaptureTime;
            this.fixedIntervalMillis = fixedIntervalMillis;
            this.fromRollupLevel = fromRollupLevel;
            this.toRollupLevel = toRollupLevel;
        }

        @Override
        public @Untainted String getSql() {
            return "select transaction_type, total_nanos, transaction_count, error_count,"
                    + " total_cpu_nanos, total_blocked_nanos, total_waited_nanos,"
                    + " total_allocated_bytes, profile_capped_id, queries_capped_id, root_timers,"
                    + " histogram from aggregate_overall_rollup_" + castUntainted(fromRollupLevel)
                    + " where capture_time > ? and capture_time <= ? order by transaction_type";
        }

        @Override
        public void bind(PreparedStatement preparedStatement) throws Exception {
            preparedStatement.setLong(1, rollupCaptureTime - fixedIntervalMillis);
            preparedStatement.setLong(2, rollupCaptureTime);
        }

        @Override
        public @Nullable Void processResultSet(ResultSet resultSet) throws Exception {
            int maxAggregateQueriesPerQueryType =
                    configRepository.getAdvancedConfig(SERVER_ID).maxAggregateQueriesPerQueryType();
            MutableOverallAggregate curr = null;
            while (resultSet.next()) {
                String transactionType = checkNotNull(resultSet.getString(1));
                if (curr == null || !transactionType.equals(curr.transactionType())) {
                    if (curr != null) {
                        dataSource.update(new AggregateInsert(curr.transactionType(), null,
                                rollupCaptureTime, curr.aggregate().toAggregate(scratchBuffer),
                                toRollupLevel));
                    }
                    curr = ImmutableMutableOverallAggregate.of(transactionType,
                            new MutableAggregate(maxAggregateQueriesPerQueryType));
                }
                merge(curr.aggregate(), resultSet, 2, fromRollupLevel);
            }
            if (curr != null) {
                dataSource
                        .update(new AggregateInsert(curr.transactionType(), null, rollupCaptureTime,
                                curr.aggregate().toAggregate(scratchBuffer), toRollupLevel));
            }
            return null;
        }

        @Override
        public @Nullable Void valueIfDataSourceClosing() {
            return null;
        }
    }

    private class RollupTransactionAggregates implements JdbcQuery</*@Nullable*/Void> {

        private final long rollupCaptureTime;
        private final long fixedIntervalMillis;
        private final int fromRollupLevel;
        private final int toRollupLevel;
        private final ScratchBuffer scratchBuffer = new ScratchBuffer();

        private RollupTransactionAggregates(long rollupCaptureTime, long fixedIntervalMillis,
                int fromRollupLevel, int toRollupLevel) {
            this.rollupCaptureTime = rollupCaptureTime;
            this.fixedIntervalMillis = fixedIntervalMillis;
            this.fromRollupLevel = fromRollupLevel;
            this.toRollupLevel = toRollupLevel;
        }

        @Override
        public @Untainted String getSql() {
            return "select transaction_type, transaction_name, total_nanos, transaction_count,"
                    + " error_count, total_cpu_nanos, total_blocked_nanos, total_waited_nanos,"
                    + " total_allocated_bytes, profile_capped_id, queries_capped_id, root_timers,"
                    + " histogram from aggregate_transaction_rollup_"
                    + castUntainted(fromRollupLevel) + " where capture_time > ?"
                    + " and capture_time <= ? order by transaction_type, transaction_name";
        }

        @Override
        public void bind(PreparedStatement preparedStatement) throws Exception {
            preparedStatement.setLong(1, rollupCaptureTime - fixedIntervalMillis);
            preparedStatement.setLong(2, rollupCaptureTime);
        }

        @Override
        public @Nullable Void processResultSet(ResultSet resultSet) throws Exception {
            int maxAggregateQueriesPerQueryType =
                    configRepository.getAdvancedConfig(SERVER_ID).maxAggregateQueriesPerQueryType();
            MutableTransactionAggregate curr = null;
            while (resultSet.next()) {
                String transactionType = checkNotNull(resultSet.getString(1));
                String transactionName = checkNotNull(resultSet.getString(2));
                if (curr == null || !transactionType.equals(curr.transactionType())
                        || !transactionName.equals(curr.transactionName())) {
                    if (curr != null) {
                        dataSource.update(new AggregateInsert(curr.transactionType(),
                                curr.transactionName(), rollupCaptureTime,
                                curr.aggregate().toAggregate(scratchBuffer), toRollupLevel));
                    }
                    curr = ImmutableMutableTransactionAggregate.of(transactionType, transactionName,
                            new MutableAggregate(maxAggregateQueriesPerQueryType));
                }
                merge(curr.aggregate(), resultSet, 3, fromRollupLevel);
            }
            if (curr != null) {
                dataSource.update(new AggregateInsert(curr.transactionType(),
                        curr.transactionName(), rollupCaptureTime,
                        curr.aggregate().toAggregate(scratchBuffer), toRollupLevel));
            }
            return null;
        }

        @Override
        public @Nullable Void valueIfDataSourceClosing() {
            return null;
        }
    }

    private class CappedIdQuery implements JdbcQuery<List<CappedId>> {

        private final @Untainted String cappedIdColumnName;
        private final TransactionQuery query;
        private final long smallestNonExpiredCappedId;

        private CappedIdQuery(@Untainted String cappedIdColumnName, TransactionQuery query) {
            this.cappedIdColumnName = cappedIdColumnName;
            this.query = query;
            smallestNonExpiredCappedId =
                    rollupCappedDatabases.get(query.rollupLevel()).getSmallestNonExpiredId();
        }

        @Override
        public @Untainted String getSql() {
            String tableName = getTableName(query);
            String transactionNameCriteria = getTransactionNameCriteria(query);
            return "select capture_time, " + cappedIdColumnName + " from " + tableName
                    + " where transaction_type = ?" + transactionNameCriteria
                    + " and capture_time > ? and capture_time <= ? and profile_capped_id >= ?";
        }

        @Override
        public void bind(PreparedStatement preparedStatement) throws Exception {
            int i = bindQuery(preparedStatement, query);
            preparedStatement.setLong(i++, smallestNonExpiredCappedId);
        }

        @Override
        public List<CappedId> processResultSet(ResultSet resultSet) throws Exception {
            List<CappedId> cappedIds = Lists.newArrayList();
            while (resultSet.next()) {
                cappedIds.add(ImmutableCappedId.of(resultSet.getLong(1), resultSet.getLong(2)));
            }
            return cappedIds;
        }

        @Override
        public List<CappedId> valueIfDataSourceClosing() {
            return ImmutableList.of();
        }
    }

    private static class ShouldHaveSomethingQuery implements JdbcQuery<Boolean> {

        private final TransactionQuery query;
        private final @Untainted String cappedIdColumnName;

        private ShouldHaveSomethingQuery(TransactionQuery query,
                @Untainted String cappedIdColumnName) {
            this.query = query;
            this.cappedIdColumnName = cappedIdColumnName;
        }

        @Override
        public @Untainted String getSql() {
            String tableName = getTableName(query);
            String transactionNameCriteria = getTransactionNameCriteria(query);
            return "select 1 from " + tableName + " where transaction_type = ?"
                    + transactionNameCriteria + " and capture_time > ? and capture_time <= ?"
                    + " and " + cappedIdColumnName + " is not null limit 1";
        }

        @Override
        public void bind(PreparedStatement preparedStatement) throws Exception {
            int i = 0;
            preparedStatement.setString(i++, query.transactionType());
            String transactionName = query.transactionName();
            if (transactionName != null) {
                preparedStatement.setString(i++, transactionName);
            }
            preparedStatement.setLong(i++, query.from());
            preparedStatement.setLong(i++, query.to());
        }

        @Override
        public Boolean processResultSet(ResultSet resultSet) throws Exception {
            return resultSet.next();
        }

        @Override
        public Boolean valueIfDataSourceClosing() {
            return false;
        }
    }

    private static class RollupTimeRowMapper implements JdbcRowQuery<Long> {

        private final int rollupLevel;
        private final long fixedIntervalMillis;
        private final long lastRollupTime;
        private final long curentRollupTime;

        private RollupTimeRowMapper(int rollupLevel, long fixedIntervalMillis, long lastRollupTime,
                long curentRollupTime) {
            this.rollupLevel = rollupLevel;
            this.fixedIntervalMillis = fixedIntervalMillis;
            this.lastRollupTime = lastRollupTime;
            this.curentRollupTime = curentRollupTime;
        }

        @Override
        public @Untainted String getSql() {
            // need ".0" to force double result
            String captureTimeSql = castUntainted(
                    "ceil(capture_time / " + fixedIntervalMillis + ".0) * " + fixedIntervalMillis);
            return "select distinct " + captureTimeSql + " from aggregate_overall_rollup_"
                    + castUntainted(rollupLevel) + " where capture_time > ? and capture_time <= ?";
        }

        @Override
        public void bind(PreparedStatement preparedStatement) throws SQLException {
            preparedStatement.setLong(1, lastRollupTime);
            preparedStatement.setLong(2, curentRollupTime);
        }

        @Override
        public Long mapRow(ResultSet resultSet) throws SQLException {
            return resultSet.getLong(1);
        }
    }

    @Value.Immutable
    @Styles.AllParameters
    interface CappedId {
        long captureTime();
        long cappedId();
    }

    @Value.Immutable
    @Styles.AllParameters
    interface MutableOverallAggregate {
        String transactionType();
        MutableAggregate aggregate();
    }

    @Value.Immutable
    @Styles.AllParameters
    interface MutableTransactionAggregate {
        String transactionType();
        String transactionName();
        MutableAggregate aggregate();
    }
}
