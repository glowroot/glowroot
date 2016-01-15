/*
 * Copyright 2015-2016 the original author or authors.
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
package org.glowroot.central.storage;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.util.Date;
import java.util.List;
import java.util.Map;

import com.datastax.driver.core.BoundStatement;
import com.datastax.driver.core.PreparedStatement;
import com.datastax.driver.core.ResultSet;
import com.datastax.driver.core.Row;
import com.datastax.driver.core.Session;
import com.datastax.driver.core.utils.UUIDs;
import com.google.common.base.Strings;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.protobuf.AbstractMessage;
import com.google.protobuf.ByteString;
import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.Parser;
import org.immutables.value.Value;

import org.glowroot.central.util.ByteBufferInputStream;
import org.glowroot.central.util.Messages;
import org.glowroot.common.model.QueryCollector;
import org.glowroot.common.util.Styles;
import org.glowroot.storage.repo.AggregateRepository;
import org.glowroot.storage.repo.ConfigRepository;
import org.glowroot.storage.repo.ConfigRepository.RollupConfig;
import org.glowroot.storage.repo.ImmutableOverallErrorSummary;
import org.glowroot.storage.repo.ImmutableOverallSummary;
import org.glowroot.storage.repo.ImmutableOverviewAggregate;
import org.glowroot.storage.repo.ImmutablePercentileAggregate;
import org.glowroot.storage.repo.ImmutableThroughputAggregate;
import org.glowroot.storage.repo.ProfileCollector;
import org.glowroot.storage.repo.TransactionErrorSummaryCollector;
import org.glowroot.storage.repo.TransactionSummaryCollector;
import org.glowroot.wire.api.model.AggregateOuterClass.Aggregate;
import org.glowroot.wire.api.model.AggregateOuterClass.Aggregate.QueriesByType;
import org.glowroot.wire.api.model.AggregateOuterClass.Aggregate.Timer;
import org.glowroot.wire.api.model.AggregateOuterClass.AggregatesByType;
import org.glowroot.wire.api.model.AggregateOuterClass.TransactionAggregate;
import org.glowroot.wire.api.model.ProfileOuterClass.Profile;

import static com.google.common.base.Preconditions.checkNotNull;

public class AggregateDao implements AggregateRepository {

    private static final Table summaryTable = ImmutableTable.builder()
            .partialName("summary")
            .addColumns(ImmutableColumn.of("total_duration_nanos", "double"))
            .addColumns(ImmutableColumn.of("transaction_count", "bigint"))
            .summary(true)
            .fromInclusive(false)
            .build();

    private static final Table errorSummaryTable = ImmutableTable.builder()
            .partialName("error_summary")
            .addColumns(ImmutableColumn.of("error_count", "bigint"))
            .addColumns(ImmutableColumn.of("transaction_count", "bigint"))
            .summary(true)
            .fromInclusive(false)
            .build();

    private static final Table overviewTable = ImmutableTable.builder()
            .partialName("overview")
            .addColumns(ImmutableColumn.of("total_duration_nanos", "double"))
            .addColumns(ImmutableColumn.of("transaction_count", "bigint"))
            .addColumns(ImmutableColumn.of("main_thread_root_timers", "blob"))
            .addColumns(ImmutableColumn.of("aux_thread_root_timers", "blob"))
            .addColumns(ImmutableColumn.of("async_root_timers", "blob"))
            .addColumns(ImmutableColumn.of("main_thread_stats", "blob"))
            .addColumns(ImmutableColumn.of("aux_thread_stats", "blob"))
            .summary(false)
            .fromInclusive(true)
            .build();

    private static final Table histogramTable = ImmutableTable.builder()
            .partialName("histogram")
            .addColumns(ImmutableColumn.of("total_duration_nanos", "double"))
            .addColumns(ImmutableColumn.of("transaction_count", "bigint"))
            .addColumns(ImmutableColumn.of("histogram", "blob"))
            .summary(false)
            .fromInclusive(true)
            .build();

    private static final Table throughputTable = ImmutableTable.builder()
            .partialName("throughput")
            .addColumns(ImmutableColumn.of("transaction_count", "bigint"))
            .summary(false)
            .fromInclusive(true)
            .build();

    private static final Table mainThreadProfileTable = ImmutableTable.builder()
            .partialName("main_thread_profile")
            .addColumns(ImmutableColumn.of("main_thread_profile", "blob"))
            .summary(false)
            .fromInclusive(false)
            .build();

    private static final Table auxThreadProfileTable = ImmutableTable.builder()
            .partialName("aux_thread_profile")
            .addColumns(ImmutableColumn.of("aux_thread_profile", "blob"))
            .summary(false)
            .fromInclusive(false)
            .build();

    private static final Table queriesTable = ImmutableTable.builder()
            .partialName("queries")
            .addColumns(ImmutableColumn.of("queries", "blob"))
            .summary(false)
            .fromInclusive(false)
            .build();

    private final Session session;
    private final ServerDao serverDao;
    private final TransactionTypeDao transactionTypeDao;
    private final ConfigRepository configRepository;

    // list index is rollupLevel
    private final Map<Table, List<PreparedStatement>> insertOverallPS;
    private final Map<Table, List<PreparedStatement>> insertTransactionPS;
    private final Map<Table, List<PreparedStatement>> readOverallPS;
    private final Map<Table, List<PreparedStatement>> readTransactionPS;

    private final List<PreparedStatement> existsAuxThreadProfileOverallPS;
    private final List<PreparedStatement> existsAuxThreadProfileTransactionPS;

    private final List<PreparedStatement> insertNeedsRollup;
    private final List<PreparedStatement> readNeedsRollup;

    public AggregateDao(Session session, ServerDao serverDao,
            TransactionTypeDao transactionTypeDao, ConfigRepository configRepository) {
        this.session = session;
        this.serverDao = serverDao;
        this.transactionTypeDao = transactionTypeDao;
        this.configRepository = configRepository;

        int count = configRepository.getRollupConfigs().size();

        List<Table> tables = ImmutableList.of(summaryTable, errorSummaryTable, overviewTable,
                histogramTable, throughputTable, mainThreadProfileTable, auxThreadProfileTable,
                queriesTable);
        Map<Table, List<PreparedStatement>> insertOverallMap = Maps.newHashMap();
        Map<Table, List<PreparedStatement>> insertTransactionMap = Maps.newHashMap();
        Map<Table, List<PreparedStatement>> readOverallMap = Maps.newHashMap();
        Map<Table, List<PreparedStatement>> readTransactionMap = Maps.newHashMap();
        for (Table table : tables) {
            List<PreparedStatement> insertOverallList = Lists.newArrayList();
            List<PreparedStatement> insertTransactionList = Lists.newArrayList();
            List<PreparedStatement> readOverallList = Lists.newArrayList();
            List<PreparedStatement> readTransactionList = Lists.newArrayList();
            for (int i = 0; i < count; i++) {
                if (table.summary()) {
                    session.execute(createSummaryTablePS(table, false, i));
                    session.execute(createSummaryTablePS(table, true, i));
                    insertOverallList.add(session.prepare(insertSummaryPS(table, false, i)));
                    insertTransactionList.add(session.prepare(insertSummaryPS(table, true, i)));
                    readOverallList.add(session.prepare(readSummaryPS(table, false, i)));
                    readTransactionList.add(session.prepare(readSummaryPS(table, true, i)));
                } else {
                    session.execute(createTablePS(table, false, i));
                    session.execute(createTablePS(table, true, i));
                    insertOverallList.add(session.prepare(insertPS(table, false, i)));
                    insertTransactionList.add(session.prepare(insertPS(table, true, i)));
                    readOverallList.add(session.prepare(readPS(table, false, i)));
                    readTransactionList.add(session.prepare(readPS(table, true, i)));
                }
            }
            insertOverallMap.put(table, ImmutableList.copyOf(insertOverallList));
            insertTransactionMap.put(table, ImmutableList.copyOf(insertTransactionList));
            readOverallMap.put(table, ImmutableList.copyOf(readOverallList));
            readTransactionMap.put(table, ImmutableList.copyOf(readTransactionList));
        }
        this.insertOverallPS = ImmutableMap.copyOf(insertOverallMap);
        this.insertTransactionPS = ImmutableMap.copyOf(insertTransactionMap);
        this.readOverallPS = ImmutableMap.copyOf(readOverallMap);
        this.readTransactionPS = ImmutableMap.copyOf(readTransactionMap);

        List<PreparedStatement> existsAuxThreadProfileOverallPS = Lists.newArrayList();
        List<PreparedStatement> existsAuxThreadProfileTransactionPS = Lists.newArrayList();
        for (int i = 0; i < count; i++) {
            existsAuxThreadProfileOverallPS
                    .add(session.prepare(existsPS(auxThreadProfileTable, false, i)));
            existsAuxThreadProfileTransactionPS
                    .add(session.prepare(existsPS(auxThreadProfileTable, true, i)));
        }
        this.existsAuxThreadProfileOverallPS = existsAuxThreadProfileOverallPS;
        this.existsAuxThreadProfileTransactionPS = existsAuxThreadProfileTransactionPS;

        List<PreparedStatement> insertNeedsRollup = Lists.newArrayList();
        List<PreparedStatement> readNeedsRollup = Lists.newArrayList();
        for (int i = 0; i < count; i++) {
            session.execute("create table if not exists aggregate_needs_rollup_" + i
                    + " (server_rollup varchar, transaction_type varchar, capture_time timestamp,"
                    + " last_update timeuuid, primary key ((server_rollup, transaction_type),"
                    + " capture_time))");
            insertNeedsRollup.add(session.prepare("insert into aggregate_needs_rollup_" + i
                    + " (server_rollup, transaction_type, capture_time, last_update) values"
                    + " (?, ?, ?, ?)"));
            readNeedsRollup.add(session.prepare("select capture_time, last_update from"
                    + " aggregate_needs_rollup_" + i + " where server_rollup = ?"
                    + " and transaction_type = ? and capture_time > ?"));
        }
        this.insertNeedsRollup = insertNeedsRollup;
        this.readNeedsRollup = readNeedsRollup;
    }

    @Override
    public void store(String serverId, long captureTime,
            List<AggregatesByType> aggregatesByTypeList) throws IOException {
        for (AggregatesByType aggregatesByType : aggregatesByTypeList) {
            String transactionType = aggregatesByType.getTransactionType();
            Aggregate overallAggregate = aggregatesByType.getOverallAggregate();
            storeOverallAggregate(0, serverId, transactionType, captureTime, overallAggregate);
            for (TransactionAggregate transactionAggregate : aggregatesByType
                    .getTransactionAggregateList()) {
                storeTransactionAggregate(0, serverId, transactionType,
                        transactionAggregate.getTransactionName(), captureTime,
                        transactionAggregate.getAggregate());
            }
            transactionTypeDao.updateLastCaptureTime(serverId, transactionType);

            List<RollupConfig> rollupConfigs = configRepository.getRollupConfigs();
            for (int i = 1; i < rollupConfigs.size(); i++) {
                long intervalMillis = rollupConfigs.get(i).intervalMillis();
                long rollupCaptureTime =
                        (long) Math.ceil(captureTime / (double) intervalMillis) * intervalMillis;
                BoundStatement boundStatement = insertNeedsRollup.get(i).bind();
                boundStatement.setString(0, serverId);
                boundStatement.setString(1, transactionType);
                boundStatement.setTimestamp(2, new Date(rollupCaptureTime));
                boundStatement.setUUID(3, UUIDs.timeBased());
                session.execute(boundStatement);
            }
        }
        serverDao.updateLastCaptureTime(serverId, true);
    }

    @Override
    public OverallSummary readOverallSummary(OverallQuery query) {
        // currently have to do aggregation client-site (don't want to require Cassandra 2.2 yet)
        BoundStatement boundStatement =
                checkNotNull(readOverallPS.get(summaryTable)).get(query.rollupLevel()).bind();
        bindQuery(boundStatement, query);
        ResultSet results = session.execute(boundStatement);
        long lastCaptureTime = 0;
        double totalNanos = 0;
        long transactionCount = 0;
        for (Row row : results) {
            // results are ordered by capture time so Math.max() is not needed here
            lastCaptureTime = checkNotNull(row.getTimestamp(0)).getTime();
            totalNanos += row.getDouble(1);
            transactionCount += row.getLong(2);
        }
        return ImmutableOverallSummary.builder()
                .totalNanos(totalNanos)
                .transactionCount(transactionCount)
                .lastCaptureTime(lastCaptureTime)
                .build();
    }

    // sortOrder and limit are only used by fat agent H2 collector, while the central collector
    // which currently has to pull in all records anyways, just delegates ordering and limit to
    // TransactionSummaryCollector
    @Override
    public void mergeInTransactionSummaries(TransactionSummaryCollector mergedTransactionSummaries,
            OverallQuery query, SummarySortOrder sortOrder, int limit) {
        // currently have to do group by / sort / limit client-side
        BoundStatement boundStatement =
                checkNotNull(readTransactionPS.get(summaryTable)).get(query.rollupLevel()).bind();
        bindQuery(boundStatement, query);
        ResultSet results = session.execute(boundStatement);
        for (Row row : results) {
            long captureTime = checkNotNull(row.getTimestamp(0)).getTime();
            String transactionName = checkNotNull(row.getString(1));
            double totalNanos = row.getDouble(2);
            long transactionCount = row.getLong(3);
            mergedTransactionSummaries.collect(transactionName, totalNanos, transactionCount,
                    captureTime);
        }
    }

    @Override
    public OverallErrorSummary readOverallErrorSummary(OverallQuery query) {
        // currently have to do aggregation client-site (don't want to require Cassandra 2.2 yet)
        BoundStatement boundStatement =
                checkNotNull(readOverallPS.get(errorSummaryTable)).get(query.rollupLevel()).bind();
        bindQuery(boundStatement, query);
        ResultSet results = session.execute(boundStatement);
        long lastCaptureTime = 0;
        long errorCount = 0;
        long transactionCount = 0;
        for (Row row : results) {
            // results are ordered by capture time so Math.max() is not needed here
            lastCaptureTime = checkNotNull(row.getTimestamp(0)).getTime();
            errorCount += row.getLong(1);
            transactionCount += row.getLong(2);
        }
        return ImmutableOverallErrorSummary.builder()
                .errorCount(errorCount)
                .transactionCount(transactionCount)
                .lastCaptureTime(lastCaptureTime)
                .build();
    }

    // sortOrder and limit are only used by fat agent H2 collector, while the central collector
    // which currently has to pull in all records anyways, just delegates ordering and limit to
    // TransactionErrorSummaryCollector
    @Override
    public void mergeInTransactionErrorSummaries(
            TransactionErrorSummaryCollector mergedTransactionErrorSummaries, OverallQuery query,
            ErrorSummarySortOrder sortOrder, int limit) {
        // currently have to do group by / sort / limit client-side
        BoundStatement boundStatement = checkNotNull(readTransactionPS.get(errorSummaryTable))
                .get(query.rollupLevel()).bind();
        bindQuery(boundStatement, query);
        ResultSet results = session.execute(boundStatement);
        for (Row row : results) {
            long captureTime = checkNotNull(row.getTimestamp(0)).getTime();
            String transactionName = checkNotNull(row.getString(1));
            long errorCount = row.getLong(2);
            long transactionCount = row.getLong(3);
            mergedTransactionErrorSummaries.collect(transactionName, errorCount, transactionCount,
                    captureTime);
        }
    }

    @Override
    public List<OverviewAggregate> readOverviewAggregates(TransactionQuery query)
            throws IOException {
        BoundStatement boundStatement = createBoundStatement(overviewTable, query);
        bindQuery(boundStatement, query);
        ResultSet results = session.execute(boundStatement);
        List<OverviewAggregate> overviewAggregates = Lists.newArrayList();
        for (Row row : results) {
            int i = 0;
            long captureTime = checkNotNull(row.getTimestamp(i++)).getTime();
            double totalDurationNanos = row.getDouble(i++);
            long transactionCount = row.getLong(i++);
            List<Aggregate.Timer> mainThreadRootTimers =
                    Messages.parseDelimitedFrom(row.getBytes(i++), Aggregate.Timer.parser());
            List<Aggregate.Timer> auxThreadRootTimers =
                    Messages.parseDelimitedFrom(row.getBytes(i++), Aggregate.Timer.parser());
            List<Aggregate.Timer> asyncRootTimers =
                    Messages.parseDelimitedFrom(row.getBytes(i++), Aggregate.Timer.parser());
            ImmutableOverviewAggregate.Builder builder = ImmutableOverviewAggregate.builder()
                    .captureTime(captureTime)
                    .totalDurationNanos(totalDurationNanos)
                    .transactionCount(transactionCount)
                    .addAllMainThreadRootTimers(mainThreadRootTimers)
                    .addAllAuxThreadRootTimers(auxThreadRootTimers)
                    .addAllAsyncRootTimers(asyncRootTimers);
            ByteBuffer mainThreadStats = row.getBytes(i++);
            if (mainThreadStats != null) {
                builder.mainThreadStats(
                        Aggregate.ThreadStats.parseFrom(ByteString.copyFrom(mainThreadStats)));
            }
            ByteBuffer auxThreadStats = row.getBytes(i++);
            if (auxThreadStats != null) {
                builder.auxThreadStats(
                        Aggregate.ThreadStats.parseFrom(ByteString.copyFrom(auxThreadStats)));
            }
            overviewAggregates.add(builder.build());
        }
        return overviewAggregates;
    }

    @Override
    public List<PercentileAggregate> readPercentileAggregates(TransactionQuery query)
            throws InvalidProtocolBufferException {
        BoundStatement boundStatement = createBoundStatement(histogramTable, query);
        bindQuery(boundStatement, query);
        ResultSet results = session.execute(boundStatement);
        List<PercentileAggregate> percentileAggregates = Lists.newArrayList();
        for (Row row : results) {
            long captureTime = checkNotNull(row.getTimestamp(0)).getTime();
            double totalNanos = row.getDouble(1);
            long transactionCount = row.getLong(2);
            ByteBuffer bytes = checkNotNull(row.getBytes(3));
            Aggregate.Histogram histogram =
                    Aggregate.Histogram.parseFrom(ByteString.copyFrom(bytes));
            percentileAggregates.add(ImmutablePercentileAggregate.builder()
                    .captureTime(captureTime)
                    .totalNanos(totalNanos)
                    .transactionCount(transactionCount)
                    .histogram(histogram)
                    .build());
        }
        return percentileAggregates;
    }

    @Override
    public List<ThroughputAggregate> readThroughputAggregates(TransactionQuery query)
            throws IOException {
        BoundStatement boundStatement = createBoundStatement(throughputTable, query);
        bindQuery(boundStatement, query);
        ResultSet results = session.execute(boundStatement);
        List<ThroughputAggregate> throughputAggregates = Lists.newArrayList();
        for (Row row : results) {
            long captureTime = checkNotNull(row.getTimestamp(0)).getTime();
            long transactionCount = row.getLong(1);
            throughputAggregates.add(ImmutableThroughputAggregate.builder()
                    .captureTime(captureTime)
                    .transactionCount(transactionCount)
                    .build());
        }
        return throughputAggregates;
    }

    @Override
    public void mergeInMainThreadProfiles(ProfileCollector mergedProfile, TransactionQuery query)
            throws InvalidProtocolBufferException {
        mergeInProfiles(mergedProfile, query, mainThreadProfileTable);
    }

    @Override
    public void mergeInAuxThreadProfiles(ProfileCollector mergedProfile, TransactionQuery query)
            throws InvalidProtocolBufferException {
        mergeInProfiles(mergedProfile, query, auxThreadProfileTable);
    }

    @Override
    public void mergeInQueries(QueryCollector mergedQueries, TransactionQuery query)
            throws IOException {
        BoundStatement boundStatement = createBoundStatement(queriesTable, query);
        bindQuery(boundStatement, query);
        ResultSet results = session.execute(boundStatement);
        long captureTime = Long.MIN_VALUE;
        for (Row row : results) {
            captureTime = Math.max(captureTime, checkNotNull(row.getTimestamp(0)).getTime());
            ByteBuffer byteBuf = checkNotNull(row.getBytes(1));
            try (InputStream input = new ByteBufferInputStream(byteBuf)) {
                Parser<QueriesByType> parser = Aggregate.QueriesByType.parser();
                QueriesByType message;
                while ((message = parser.parseDelimitedFrom(input)) != null) {
                    mergedQueries.mergeQueries(message);
                    mergedQueries.updateLastCaptureTime(captureTime);
                }
            }
        }
    }

    @Override
    public List<ErrorPoint> readErrorPoints(TransactionQuery query) {
        return ImmutableList.of();
    }

    @Override
    public boolean hasAuxThreadProfile(TransactionQuery query) throws Exception {
        BoundStatement boundStatement = query.transactionName() == null
                ? existsAuxThreadProfileOverallPS.get(query.rollupLevel()).bind()
                : existsAuxThreadProfileTransactionPS.get(query.rollupLevel()).bind();
        bindQuery(boundStatement, query);
        ResultSet results = session.execute(boundStatement);
        return results.one() != null;
    }

    @Override
    public boolean shouldHaveMainThreadProfile(TransactionQuery query) {
        return false;
    }

    @Override
    public boolean shouldHaveAuxThreadProfile(TransactionQuery query) {
        return false;
    }

    @Override
    public boolean shouldHaveQueries(TransactionQuery query) {
        return false;
    }

    @Override
    public void deleteAll(String serverRollup) {
        // this is not currently supported (to avoid row key range query)
        throw new UnsupportedOperationException();
    }

    private void storeOverallAggregate(int rollupLevel, String serverRollup,
            String transactionType, long captureTime, Aggregate aggregate) throws IOException {

        BoundStatement boundStatement = getInsertOverallPS(summaryTable, rollupLevel).bind();
        boundStatement.setString(0, serverRollup);
        boundStatement.setString(1, transactionType);
        boundStatement.setTimestamp(2, new Date(captureTime));
        boundStatement.setDouble(3, aggregate.getTotalDurationNanos());
        boundStatement.setLong(4, aggregate.getTransactionCount());
        session.execute(boundStatement);

        if (aggregate.getErrorCount() > 0) {
            boundStatement = getInsertOverallPS(errorSummaryTable, rollupLevel).bind();
            boundStatement.setString(0, serverRollup);
            boundStatement.setString(1, transactionType);
            boundStatement.setTimestamp(2, new Date(captureTime));
            boundStatement.setLong(3, aggregate.getErrorCount());
            boundStatement.setLong(4, aggregate.getTransactionCount());
            session.execute(boundStatement);
        }

        boundStatement = getInsertOverallPS(overviewTable, rollupLevel).bind();
        boundStatement.setString(0, serverRollup);
        boundStatement.setString(1, transactionType);
        boundStatement.setTimestamp(2, new Date(captureTime));
        bindAggregate(boundStatement, aggregate, 3);
        session.execute(boundStatement);

        boundStatement = getInsertOverallPS(histogramTable, rollupLevel).bind();
        boundStatement.setString(0, serverRollup);
        boundStatement.setString(1, transactionType);
        boundStatement.setTimestamp(2, new Date(captureTime));
        boundStatement.setDouble(3, aggregate.getTotalDurationNanos());
        boundStatement.setLong(4, aggregate.getTransactionCount());
        boundStatement.setBytes(5, toByteBuffer(aggregate.getTotalNanosHistogram()));
        session.execute(boundStatement);

        boundStatement = getInsertOverallPS(throughputTable, rollupLevel).bind();
        boundStatement.setString(0, serverRollup);
        boundStatement.setString(1, transactionType);
        boundStatement.setTimestamp(2, new Date(captureTime));
        boundStatement.setLong(3, aggregate.getTransactionCount());
        session.execute(boundStatement);

        if (aggregate.hasMainThreadProfile()) {
            Profile profile = aggregate.getMainThreadProfile();
            boundStatement = getInsertOverallPS(mainThreadProfileTable, rollupLevel).bind();
            boundStatement.setString(0, serverRollup);
            boundStatement.setString(1, transactionType);
            boundStatement.setTimestamp(2, new Date(captureTime));
            boundStatement.setBytes(3, toByteBuffer(profile));
            session.execute(boundStatement);
        }
        if (aggregate.hasAuxThreadProfile()) {
            Profile profile = aggregate.getAuxThreadProfile();
            boundStatement = getInsertOverallPS(auxThreadProfileTable, rollupLevel).bind();
            boundStatement.setString(0, serverRollup);
            boundStatement.setString(1, transactionType);
            boundStatement.setTimestamp(2, new Date(captureTime));
            boundStatement.setBytes(3, toByteBuffer(profile));
            session.execute(boundStatement);
        }
        List<QueriesByType> queriesByTypeList = aggregate.getQueriesByTypeList();
        if (!queriesByTypeList.isEmpty()) {
            // TODO optimize byte copying
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            for (Aggregate.QueriesByType queriesByType : queriesByTypeList) {
                queriesByType.writeDelimitedTo(output);
            }
            boundStatement = getInsertOverallPS(queriesTable, rollupLevel).bind();
            boundStatement.setString(0, serverRollup);
            boundStatement.setString(1, transactionType);
            boundStatement.setTimestamp(2, new Date(captureTime));
            boundStatement.setBytes(3, ByteBuffer.wrap(output.toByteArray()));
            session.execute(boundStatement);
        }
    }

    private void storeTransactionAggregate(int rollupLevel, String serverRollup,
            String transactionType, String transactionName, long captureTime, Aggregate aggregate)
                    throws IOException {

        BoundStatement boundStatement = getInsertTransactionPS(summaryTable, rollupLevel).bind();
        boundStatement.setString(0, serverRollup);
        boundStatement.setString(1, transactionType);
        boundStatement.setTimestamp(2, new Date(captureTime));
        boundStatement.setString(3, transactionName);
        boundStatement.setDouble(4, aggregate.getTotalDurationNanos());
        boundStatement.setLong(5, aggregate.getTransactionCount());
        session.execute(boundStatement);

        if (aggregate.getErrorCount() > 0) {
            boundStatement = getInsertTransactionPS(errorSummaryTable, rollupLevel).bind();
            boundStatement.setString(0, serverRollup);
            boundStatement.setString(1, transactionType);
            boundStatement.setTimestamp(2, new Date(captureTime));
            boundStatement.setString(3, transactionName);
            boundStatement.setLong(4, aggregate.getErrorCount());
            boundStatement.setLong(5, aggregate.getTransactionCount());
            session.execute(boundStatement);
        }

        boundStatement = getInsertTransactionPS(overviewTable, rollupLevel).bind();
        boundStatement.setString(0, serverRollup);
        boundStatement.setString(1, transactionType);
        boundStatement.setString(2, transactionName);
        boundStatement.setTimestamp(3, new Date(captureTime));
        bindAggregate(boundStatement, aggregate, 4);
        session.execute(boundStatement);

        boundStatement = getInsertTransactionPS(histogramTable, rollupLevel).bind();
        boundStatement.setString(0, serverRollup);
        boundStatement.setString(1, transactionType);
        boundStatement.setString(2, transactionName);
        boundStatement.setTimestamp(3, new Date(captureTime));
        boundStatement.setDouble(4, aggregate.getTotalDurationNanos());
        boundStatement.setLong(5, aggregate.getTransactionCount());
        boundStatement.setBytes(6, toByteBuffer(aggregate.getTotalNanosHistogram()));
        session.execute(boundStatement);

        boundStatement = getInsertTransactionPS(throughputTable, rollupLevel).bind();
        boundStatement.setString(0, serverRollup);
        boundStatement.setString(1, transactionType);
        boundStatement.setString(2, transactionName);
        boundStatement.setTimestamp(3, new Date(captureTime));
        boundStatement.setLong(4, aggregate.getTransactionCount());
        session.execute(boundStatement);

        if (aggregate.hasMainThreadProfile()) {
            Profile profile = aggregate.getMainThreadProfile();
            boundStatement = getInsertTransactionPS(mainThreadProfileTable, rollupLevel).bind();
            boundStatement.setString(0, serverRollup);
            boundStatement.setString(1, transactionType);
            boundStatement.setString(2, transactionName);
            boundStatement.setTimestamp(3, new Date(captureTime));
            boundStatement.setBytes(4, toByteBuffer(profile));
            session.execute(boundStatement);
        }
        if (aggregate.hasAuxThreadProfile()) {
            Profile profile = aggregate.getAuxThreadProfile();
            boundStatement = getInsertTransactionPS(auxThreadProfileTable, rollupLevel).bind();
            boundStatement.setString(0, serverRollup);
            boundStatement.setString(1, transactionType);
            boundStatement.setString(2, transactionName);
            boundStatement.setTimestamp(3, new Date(captureTime));
            boundStatement.setBytes(4, toByteBuffer(profile));
            session.execute(boundStatement);
        }
        List<QueriesByType> queriesByTypeList = aggregate.getQueriesByTypeList();
        if (!queriesByTypeList.isEmpty()) {
            // TODO optimize byte copying
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            for (Aggregate.QueriesByType queriesByType : queriesByTypeList) {
                queriesByType.writeDelimitedTo(output);
            }
            boundStatement = getInsertTransactionPS(queriesTable, rollupLevel).bind();
            boundStatement.setString(0, serverRollup);
            boundStatement.setString(1, transactionType);
            boundStatement.setString(2, transactionName);
            boundStatement.setTimestamp(3, new Date(captureTime));
            boundStatement.setBytes(4, ByteBuffer.wrap(output.toByteArray()));
            session.execute(boundStatement);
        }
    }

    private PreparedStatement getInsertOverallPS(Table table, int rollupLevel) {
        return checkNotNull(insertOverallPS.get(table)).get(rollupLevel);
    }

    private PreparedStatement getInsertTransactionPS(Table table, int rollupLevel) {
        return checkNotNull(insertTransactionPS.get(table)).get(rollupLevel);
    }

    private void bindAggregate(BoundStatement boundStatement, Aggregate aggregate, int startIndex)
            throws IOException {
        int i = startIndex;
        boundStatement.setDouble(i++, aggregate.getTotalDurationNanos());
        boundStatement.setLong(i++, aggregate.getTransactionCount());
        List<Timer> mainThreadRootTimers = aggregate.getMainThreadRootTimerList();
        if (!mainThreadRootTimers.isEmpty()) {
            boundStatement.setBytes(i++, Messages.toByteBuffer(mainThreadRootTimers));
        } else {
            boundStatement.setToNull(i++);
        }
        List<Timer> auxThreadRootTimers = aggregate.getAuxThreadRootTimerList();
        if (!auxThreadRootTimers.isEmpty()) {
            boundStatement.setBytes(i++, Messages.toByteBuffer(auxThreadRootTimers));
        } else {
            boundStatement.setToNull(i++);
        }
        List<Timer> asyncRootTimers = aggregate.getAsyncRootTimerList();
        if (!asyncRootTimers.isEmpty()) {
            boundStatement.setBytes(i++, Messages.toByteBuffer(asyncRootTimers));
        } else {
            boundStatement.setToNull(i++);
        }
        if (aggregate.hasMainThreadStats()) {
            boundStatement.setBytes(i++,
                    ByteBuffer.wrap(aggregate.getMainThreadStats().toByteArray()));
        } else {
            boundStatement.setToNull(i++);
        }
        if (aggregate.hasAuxThreadStats()) {
            boundStatement.setBytes(i++,
                    ByteBuffer.wrap(aggregate.getAuxThreadStats().toByteArray()));
        } else {
            boundStatement.setToNull(i++);
        }
    }

    private BoundStatement createBoundStatement(Table table, TransactionQuery query) {
        if (query.transactionName() == null) {
            return checkNotNull(readOverallPS.get(table)).get(query.rollupLevel()).bind();
        }
        return checkNotNull(readTransactionPS.get(table)).get(query.rollupLevel()).bind();
    }

    private void mergeInProfiles(ProfileCollector mergedProfile, TransactionQuery query,
            Table profileTable) throws InvalidProtocolBufferException {
        BoundStatement boundStatement = createBoundStatement(profileTable, query);
        bindQuery(boundStatement, query);
        ResultSet results = session.execute(boundStatement);
        long captureTime = Long.MIN_VALUE;
        for (Row row : results) {
            captureTime = Math.max(captureTime, checkNotNull(row.getTimestamp(0)).getTime());
            ByteBuffer bytes = checkNotNull(row.getBytes(1));
            // TODO optimize this byte copying
            Profile profile = Profile.parseFrom(ByteString.copyFrom(bytes));
            mergedProfile.mergeProfile(profile);
            mergedProfile.updateLastCaptureTime(captureTime);
        }
    }

    private static void bindQuery(BoundStatement boundStatement, OverallQuery query) {
        int i = 0;
        boundStatement.setString(i++, query.serverRollup());
        boundStatement.setString(i++, query.transactionType());
        boundStatement.setTimestamp(i++, new Date(query.from()));
        boundStatement.setTimestamp(i++, new Date(query.to()));
    }

    private static void bindQuery(BoundStatement boundStatement, TransactionQuery query) {
        int i = 0;
        boundStatement.setString(i++, query.serverRollup());
        boundStatement.setString(i++, query.transactionType());
        String transactionName = query.transactionName();
        if (transactionName != null) {
            boundStatement.setString(i++, transactionName);
        }
        boundStatement.setTimestamp(i++, new Date(query.from()));
        boundStatement.setTimestamp(i++, new Date(query.to()));
    }

    private static String createTablePS(Table table, boolean transaction, int i) {
        StringBuilder sb = new StringBuilder();
        sb.append("create table if not exists ");
        sb.append(getTableName(table.partialName(), transaction, i));
        sb.append(" (server_rollup varchar, transaction_type varchar");
        if (transaction) {
            sb.append(", transaction_name varchar");
        }
        sb.append(", capture_time timestamp");
        for (Column column : table.columns()) {
            sb.append(", ");
            sb.append(column.name());
            sb.append(" ");
            sb.append(column.type());
        }
        if (transaction) {
            sb.append(", primary key ((server_rollup, transaction_type, transaction_name),"
                    + " capture_time))");
        } else {
            sb.append(", primary key ((server_rollup, transaction_type), capture_time))");
        }
        return sb.toString();
    }

    private static String insertPS(Table table, boolean transaction, int i) {
        StringBuilder sb = new StringBuilder();
        sb.append("insert into ");
        sb.append(getTableName(table.partialName(), transaction, i));
        sb.append(" (server_rollup, transaction_type");
        if (transaction) {
            sb.append(", transaction_name");
        }
        sb.append(", capture_time");
        for (Column column : table.columns()) {
            sb.append(", ");
            sb.append(column.name());
        }
        sb.append(") values (?, ?, ?");
        if (transaction) {
            sb.append(", ?");
        }
        sb.append(Strings.repeat(", ?", table.columns().size()));
        sb.append(")");
        return sb.toString();
    }

    private static String readPS(Table table, boolean transaction, int i) {
        StringBuilder sb = new StringBuilder();
        sb.append("select capture_time");
        for (Column column : table.columns()) {
            sb.append(", ");
            sb.append(column.name());
        }
        sb.append(" from ");
        sb.append(getTableName(table.partialName(), transaction, i));
        sb.append(" where server_rollup = ? and transaction_type = ?");
        if (transaction) {
            sb.append(" and transaction_name = ?");
        }
        sb.append(" and capture_time >");
        if (table.fromInclusive()) {
            sb.append("=");
        }
        sb.append(" ? and capture_time <= ?");
        return sb.toString();
    }

    private static String existsPS(Table table, boolean transaction, int i) {
        StringBuilder sb = new StringBuilder();
        sb.append("select server_rollup");
        sb.append(" from ");
        sb.append(getTableName(table.partialName(), transaction, i));
        sb.append(" where server_rollup = ? and transaction_type = ?");
        if (transaction) {
            sb.append(" and transaction_name = ?");
        }
        sb.append(" and capture_time >");
        if (table.fromInclusive()) {
            sb.append("=");
        }
        sb.append(" ? and capture_time <= ? limit 1");
        return sb.toString();
    }

    private static String createSummaryTablePS(Table table, boolean transaction, int i) {
        StringBuilder sb = new StringBuilder();
        sb.append("create table if not exists ");
        sb.append(getTableName(table.partialName(), transaction, i));
        sb.append(" (server_rollup varchar, transaction_type varchar, capture_time timestamp");
        if (transaction) {
            sb.append(", transaction_name varchar");
        }
        for (Column column : table.columns()) {
            sb.append(", ");
            sb.append(column.name());
            sb.append(" ");
            sb.append(column.type());
        }
        if (transaction) {
            sb.append(", primary key ((server_rollup, transaction_type),"
                    + " capture_time, transaction_name))");
        } else {
            sb.append(", primary key ((server_rollup, transaction_type), capture_time))");
        }
        return sb.toString();
    }

    private static String insertSummaryPS(Table table, boolean transaction, int i) {
        StringBuilder sb = new StringBuilder();
        sb.append("insert into ");
        sb.append(getTableName(table.partialName(), transaction, i));
        sb.append(" (server_rollup, transaction_type, capture_time");
        if (transaction) {
            sb.append(", transaction_name");
        }
        for (Column column : table.columns()) {
            sb.append(", ");
            sb.append(column.name());
        }
        sb.append(") values (?, ?, ?");
        if (transaction) {
            sb.append(", ?");
        }
        sb.append(Strings.repeat(", ?", table.columns().size()));
        sb.append(")");
        return sb.toString();
    }

    // currently have to do group by / sort / limit client-side, even on overall_summary
    // because sum(double) requires Cassandra 2.2+
    private static String readSummaryPS(Table table, boolean transaction, int i) {
        StringBuilder sb = new StringBuilder();
        // capture_time is needed to keep track of lastCaptureTime for rollup level when merging
        // recent non-rolled up data
        sb.append("select capture_time");
        if (transaction) {
            sb.append(", transaction_name");
        }
        for (Column column : table.columns()) {
            sb.append(", ");
            sb.append(column.name());
        }
        sb.append(" from ");
        sb.append(getTableName(table.partialName(), transaction, i));
        sb.append(" where server_rollup = ? and transaction_type = ? and capture_time >");
        if (table.fromInclusive()) {
            sb.append("=");
        }
        sb.append(" ? and capture_time <= ?");
        return sb.toString();
    }

    private static StringBuilder getTableName(String partialName, boolean transaction, int i) {
        StringBuilder sb = new StringBuilder();
        sb.append("aggregate_");
        if (transaction) {
            sb.append("tn_");
        } else {
            sb.append("tt_");
        }
        sb.append(partialName);
        sb.append("_rollup_");
        sb.append(i);
        return sb;
    }

    private static ByteBuffer toByteBuffer(AbstractMessage message) {
        return ByteBuffer.wrap(message.toByteString().toByteArray());
    }

    @Value.Immutable
    interface Table {
        String partialName();
        List<String> partitionKey();
        List<String> clusterKey();
        List<Column> columns();
        boolean summary();
        boolean fromInclusive();
    }

    @Value.Immutable
    @Styles.AllParameters
    interface Column {
        String name();
        String type();
    }
}
