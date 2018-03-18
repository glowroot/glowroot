/*
 * Copyright 2016-2018 the original author or authors.
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
package org.glowroot.agent.embedded.repo;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;

import javax.annotation.Nullable;

import com.google.common.base.Strings;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.protobuf.AbstractMessage;
import org.checkerframework.checker.tainting.qual.Untainted;

import org.glowroot.agent.embedded.repo.AggregateDao.TruncatedQueryText;
import org.glowroot.agent.embedded.repo.model.Stored;
import org.glowroot.agent.embedded.util.CappedDatabase;
import org.glowroot.agent.embedded.util.DataSource.JdbcUpdate;
import org.glowroot.agent.embedded.util.RowMappers;
import org.glowroot.common.model.LazyHistogram.ScratchBuffer;
import org.glowroot.common.model.MutableProfile;
import org.glowroot.common.model.MutableQuery;
import org.glowroot.common.model.MutableServiceCall;
import org.glowroot.common.model.QueryCollector;
import org.glowroot.common.model.ServiceCallCollector;
import org.glowroot.common.util.NotAvailableAware;
import org.glowroot.common2.repo.MutableAggregate;
import org.glowroot.common2.repo.MutableThreadStats;
import org.glowroot.wire.api.model.AggregateOuterClass.Aggregate;
import org.glowroot.wire.api.model.ProfileOuterClass.Profile;

import static org.glowroot.agent.util.Checkers.castUntainted;

class AggregateInsert implements JdbcUpdate {

    private final String transactionType;
    private final @Nullable String transactionName;
    private final long captureTime;
    private final double totalDurationNanos;
    private final long transactionCount;
    private final long errorCount;
    private final boolean asyncTransactions;
    private final @Nullable Long queriesCappedId;
    private final @Nullable Long serviceCallsCappedId;
    private final @Nullable Long mainThreadProfileCappedId;
    private final @Nullable Long auxThreadProfileCappedId;
    private final byte /*@Nullable*/ [] mainThreadRootTimers;
    private final byte /*@Nullable*/ [] auxThreadRootTimers;
    private final byte /*@Nullable*/ [] asyncTimers;
    private final @Nullable Double mainThreadTotalCpuNanos;
    private final @Nullable Double mainThreadTotalBlockedNanos;
    private final @Nullable Double mainThreadTotalWaitedNanos;
    private final @Nullable Double mainThreadTotalAllocatedBytes;
    private final @Nullable Double auxThreadTotalCpuNanos;
    private final @Nullable Double auxThreadTotalBlockedNanos;
    private final @Nullable Double auxThreadTotalWaitedNanos;
    private final @Nullable Double auxThreadTotalAllocatedBytes;
    private final byte[] durationNanosHistogramBytes;

    private final int rollupLevel;

    AggregateInsert(String transactionType, @Nullable String transactionName,
            long captureTime, Aggregate aggregate, List<TruncatedQueryText> truncatedQueryTexts,
            int rollupLevel, CappedDatabase cappedDatabase) throws IOException {
        this.transactionType = transactionType;
        this.transactionName = transactionName;
        this.captureTime = captureTime;
        this.rollupLevel = rollupLevel;
        totalDurationNanos = aggregate.getTotalDurationNanos();
        transactionCount = aggregate.getTransactionCount();
        errorCount = aggregate.getErrorCount();
        asyncTransactions = aggregate.getAsyncTransactions();

        queriesCappedId = writeQueries(cappedDatabase,
                toStored(aggregate.getQueryList(), truncatedQueryTexts));
        serviceCallsCappedId =
                writeServiceCalls(cappedDatabase, toStored(aggregate.getServiceCallList()));
        if (aggregate.hasMainThreadProfile()) {
            mainThreadProfileCappedId =
                    writeProfile(cappedDatabase, aggregate.getMainThreadProfile());
        } else {
            mainThreadProfileCappedId = null;
        }
        if (aggregate.hasAuxThreadProfile()) {
            auxThreadProfileCappedId =
                    writeProfile(cappedDatabase, aggregate.getAuxThreadProfile());
        } else {
            auxThreadProfileCappedId = null;
        }
        mainThreadRootTimers = toByteArray(aggregate.getMainThreadRootTimerList());
        auxThreadRootTimers = toByteArray(aggregate.getAuxThreadRootTimerList());
        asyncTimers = toByteArray(aggregate.getAsyncTimerList());
        Aggregate.ThreadStats mainThreadStats = aggregate.getMainThreadStats();
        mainThreadTotalCpuNanos = mainThreadStats.hasTotalCpuNanos()
                ? mainThreadStats.getTotalCpuNanos().getValue()
                : null;
        mainThreadTotalBlockedNanos = mainThreadStats.hasTotalBlockedNanos()
                ? mainThreadStats.getTotalBlockedNanos().getValue()
                : null;
        mainThreadTotalWaitedNanos = mainThreadStats.hasTotalWaitedNanos()
                ? mainThreadStats.getTotalWaitedNanos().getValue()
                : null;
        mainThreadTotalAllocatedBytes = mainThreadStats.hasTotalAllocatedBytes()
                ? mainThreadStats.getTotalAllocatedBytes().getValue()
                : null;
        Aggregate.ThreadStats auxThreadStats = aggregate.getAuxThreadStats();
        auxThreadTotalCpuNanos = auxThreadStats.hasTotalCpuNanos()
                ? auxThreadStats.getTotalCpuNanos().getValue()
                : null;
        auxThreadTotalBlockedNanos = auxThreadStats.hasTotalBlockedNanos()
                ? auxThreadStats.getTotalBlockedNanos().getValue()
                : null;
        auxThreadTotalWaitedNanos = auxThreadStats.hasTotalWaitedNanos()
                ? auxThreadStats.getTotalWaitedNanos().getValue()
                : null;
        auxThreadTotalAllocatedBytes = auxThreadStats.hasTotalAllocatedBytes()
                ? auxThreadStats.getTotalAllocatedBytes().getValue()
                : null;
        durationNanosHistogramBytes = aggregate.getDurationNanosHistogram().toByteArray();
    }

    AggregateInsert(String transactionType, @Nullable String transactionName,
            long captureTime, MutableAggregate aggregate, int rollupLevel,
            CappedDatabase cappedDatabase, ScratchBuffer scratchBuffer) throws IOException {
        this.transactionType = transactionType;
        this.transactionName = transactionName;
        this.captureTime = captureTime;
        this.rollupLevel = rollupLevel;
        totalDurationNanos = aggregate.getTotalDurationNanos();
        transactionCount = aggregate.getTransactionCount();
        errorCount = aggregate.getErrorCount();
        asyncTransactions = aggregate.isAsyncTransactions();

        queriesCappedId = writeQueries(cappedDatabase, toStored(aggregate.getQueries()));
        serviceCallsCappedId =
                writeServiceCalls(cappedDatabase, toStored(aggregate.getServiceCalls()));
        mainThreadProfileCappedId = writeProfile(cappedDatabase, aggregate.getMainThreadProfile());
        auxThreadProfileCappedId = writeProfile(cappedDatabase, aggregate.getAuxThreadProfile());
        mainThreadRootTimers = toByteArray(aggregate.getMainThreadRootTimersProto());
        auxThreadRootTimers = toByteArray(aggregate.getAuxThreadRootTimersProto());
        asyncTimers = toByteArray(aggregate.getAsyncTimersProto());
        MutableThreadStats mainThreadStats = aggregate.getMainThreadStats();
        mainThreadTotalCpuNanos = NotAvailableAware.orNull(mainThreadStats.getTotalCpuNanos());
        mainThreadTotalBlockedNanos =
                NotAvailableAware.orNull(mainThreadStats.getTotalBlockedNanos());
        mainThreadTotalWaitedNanos =
                NotAvailableAware.orNull(mainThreadStats.getTotalWaitedNanos());
        mainThreadTotalAllocatedBytes =
                NotAvailableAware.orNull(mainThreadStats.getTotalAllocatedBytes());
        MutableThreadStats auxThreadStats = aggregate.getAuxThreadStats();
        auxThreadTotalCpuNanos = NotAvailableAware.orNull(auxThreadStats.getTotalCpuNanos());
        auxThreadTotalBlockedNanos =
                NotAvailableAware.orNull(auxThreadStats.getTotalBlockedNanos());
        auxThreadTotalWaitedNanos = NotAvailableAware.orNull(auxThreadStats.getTotalWaitedNanos());
        auxThreadTotalAllocatedBytes =
                NotAvailableAware.orNull(auxThreadStats.getTotalAllocatedBytes());
        durationNanosHistogramBytes =
                aggregate.getDurationNanosHistogram().toProto(scratchBuffer).toByteArray();
    }

    @Override
    public @Untainted String getSql() {
        StringBuilder sb = new StringBuilder();
        sb.append("merge into aggregate_");
        if (transactionName != null) {
            sb.append("tn_");
        } else {
            sb.append("tt_");
        }
        sb.append("rollup_");
        sb.append(castUntainted(rollupLevel));
        sb.append(" (transaction_type,");
        if (transactionName != null) {
            sb.append(" transaction_name,");
        }
        sb.append(" capture_time, total_duration_nanos, transaction_count, error_count,"
                + " async_transactions, queries_capped_id, service_calls_capped_id,"
                + " main_thread_profile_capped_id, aux_thread_profile_capped_id,"
                + " main_thread_root_timers, aux_thread_root_timers, async_root_timers,"
                + " main_thread_total_cpu_nanos, main_thread_total_blocked_nanos,"
                + " main_thread_total_waited_nanos, main_thread_total_allocated_bytes,"
                + " aux_thread_total_cpu_nanos, aux_thread_total_blocked_nanos,"
                + " aux_thread_total_waited_nanos, aux_thread_total_allocated_bytes,"
                + " duration_nanos_histogram) key (transaction_type");
        if (transactionName != null) {
            sb.append(", transaction_name");
        }
        sb.append(", capture_time) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?,"
                + " ?, ?, ?");
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
        preparedStatement.setDouble(i++, totalDurationNanos);
        preparedStatement.setLong(i++, transactionCount);
        preparedStatement.setLong(i++, errorCount);
        preparedStatement.setBoolean(i++, asyncTransactions);
        RowMappers.setLong(preparedStatement, i++, queriesCappedId);
        RowMappers.setLong(preparedStatement, i++, serviceCallsCappedId);
        RowMappers.setLong(preparedStatement, i++, mainThreadProfileCappedId);
        RowMappers.setLong(preparedStatement, i++, auxThreadProfileCappedId);
        RowMappers.setBytes(preparedStatement, i++, mainThreadRootTimers);
        RowMappers.setBytes(preparedStatement, i++, auxThreadRootTimers);
        RowMappers.setBytes(preparedStatement, i++, asyncTimers);
        RowMappers.setDouble(preparedStatement, i++, mainThreadTotalCpuNanos);
        RowMappers.setDouble(preparedStatement, i++, mainThreadTotalBlockedNanos);
        RowMappers.setDouble(preparedStatement, i++, mainThreadTotalWaitedNanos);
        RowMappers.setDouble(preparedStatement, i++, mainThreadTotalAllocatedBytes);
        RowMappers.setDouble(preparedStatement, i++, auxThreadTotalCpuNanos);
        RowMappers.setDouble(preparedStatement, i++, auxThreadTotalBlockedNanos);
        RowMappers.setDouble(preparedStatement, i++, auxThreadTotalWaitedNanos);
        RowMappers.setDouble(preparedStatement, i++, auxThreadTotalAllocatedBytes);
        preparedStatement.setBytes(i++, durationNanosHistogramBytes);
    }

    private static List<Stored.QueriesByType> toStored(List<Aggregate.Query> aggregateQueries,
            List<TruncatedQueryText> truncatedQueryTexts) {
        Map<String, Stored.QueriesByType.Builder> builders = Maps.newHashMap();
        for (Aggregate.Query aggregateQuery : aggregateQueries) {
            TruncatedQueryText truncatedQueryText =
                    truncatedQueryTexts.get(aggregateQuery.getSharedQueryTextIndex());
            Stored.Query.Builder query = Stored.Query.newBuilder()
                    .setTruncatedText(truncatedQueryText.truncatedText())
                    .setFullTextSha1(Strings.nullToEmpty(truncatedQueryText.fullTextSha1()))
                    .setTotalDurationNanos(aggregateQuery.getTotalDurationNanos())
                    .setExecutionCount(aggregateQuery.getExecutionCount());
            if (aggregateQuery.hasTotalRows()) {
                query.setTotalRows(Stored.OptionalInt64.newBuilder()
                        .setValue(aggregateQuery.getTotalRows().getValue())
                        .build());
            }
            String queryType = aggregateQuery.getType();
            Stored.QueriesByType.Builder queriesByType = builders.get(queryType);
            if (queriesByType == null) {
                queriesByType = Stored.QueriesByType.newBuilder().setType(queryType);
                builders.put(queryType, queriesByType);
            }
            queriesByType.addQuery(query.build());
        }
        List<Stored.QueriesByType> queries = Lists.newArrayList();
        for (Stored.QueriesByType.Builder builder : builders.values()) {
            queries.add(builder.build());
        }
        return queries;
    }

    private static List<Stored.QueriesByType> toStored(@Nullable QueryCollector collector) {
        if (collector == null) {
            return ImmutableList.of();
        }
        Map<String, Stored.QueriesByType.Builder> builders = Maps.newHashMap();
        for (MutableQuery mutableQuery : collector.getSortedAndTruncatedQueries()) {
            Stored.Query.Builder query = Stored.Query.newBuilder()
                    .setTruncatedText(mutableQuery.getTruncatedText())
                    .setFullTextSha1(Strings.nullToEmpty(mutableQuery.getFullTextSha1()))
                    .setTotalDurationNanos(mutableQuery.getTotalDurationNanos())
                    .setExecutionCount(mutableQuery.getExecutionCount());
            if (mutableQuery.hasTotalRows()) {
                query.setTotalRows(Stored.OptionalInt64.newBuilder()
                        .setValue(mutableQuery.getTotalRows())
                        .build());
            }
            String queryType = mutableQuery.getType();
            Stored.QueriesByType.Builder queriesByType = builders.get(queryType);
            if (queriesByType == null) {
                queriesByType = Stored.QueriesByType.newBuilder().setType(queryType);
                builders.put(queryType, queriesByType);
            }
            queriesByType.addQuery(query.build());
        }
        List<Stored.QueriesByType> queries = Lists.newArrayList();
        for (Stored.QueriesByType.Builder builder : builders.values()) {
            queries.add(builder.build());
        }
        return queries;
    }

    private static List<Stored.ServiceCallsByType> toStored(
            List<Aggregate.ServiceCall> aggregateServiceCalls) {
        Map<String, Stored.ServiceCallsByType.Builder> builders = Maps.newHashMap();
        for (Aggregate.ServiceCall aggregateServiceCall : aggregateServiceCalls) {
            Stored.ServiceCall.Builder serviceCall = Stored.ServiceCall.newBuilder()
                    .setText(aggregateServiceCall.getText())
                    .setTotalDurationNanos(aggregateServiceCall.getTotalDurationNanos())
                    .setExecutionCount(aggregateServiceCall.getExecutionCount());
            String serviceCallType = aggregateServiceCall.getType();
            Stored.ServiceCallsByType.Builder serviceCallsByType = builders.get(serviceCallType);
            if (serviceCallsByType == null) {
                serviceCallsByType =
                        Stored.ServiceCallsByType.newBuilder().setType(serviceCallType);
                builders.put(serviceCallType, serviceCallsByType);
            }
            serviceCallsByType.addServiceCall(serviceCall.build());
        }
        List<Stored.ServiceCallsByType> serviceCalls = Lists.newArrayList();
        for (Stored.ServiceCallsByType.Builder builder : builders.values()) {
            serviceCalls.add(builder.build());
        }
        return serviceCalls;
    }

    private static List<Stored.ServiceCallsByType> toStored(
            @Nullable ServiceCallCollector collector) {
        if (collector == null) {
            return ImmutableList.of();
        }
        Map<String, Stored.ServiceCallsByType.Builder> builders = Maps.newHashMap();
        for (MutableServiceCall mutableServiceCall : collector
                .getSortedAndTruncatedServiceCalls()) {
            Stored.ServiceCall.Builder serviceCall = Stored.ServiceCall.newBuilder()
                    .setText(mutableServiceCall.getText())
                    .setTotalDurationNanos(mutableServiceCall.getTotalDurationNanos())
                    .setExecutionCount(mutableServiceCall.getExecutionCount());
            String serviceCallType = mutableServiceCall.getType();
            Stored.ServiceCallsByType.Builder serviceCallsByType = builders.get(serviceCallType);
            if (serviceCallsByType == null) {
                serviceCallsByType =
                        Stored.ServiceCallsByType.newBuilder().setType(serviceCallType);
                builders.put(serviceCallType, serviceCallsByType);
            }
            serviceCallsByType.addServiceCall(serviceCall.build());
        }
        List<Stored.ServiceCallsByType> serviceCalls = Lists.newArrayList();
        for (Stored.ServiceCallsByType.Builder builder : builders.values()) {
            serviceCalls.add(builder.build());
        }
        return serviceCalls;
    }

    private static @Nullable Long writeQueries(CappedDatabase cappedDatabase,
            List<Stored.QueriesByType> queries) throws IOException {
        if (queries.isEmpty()) {
            return null;
        }
        return cappedDatabase.writeMessages(queries, RollupCappedDatabaseStats.AGGREGATE_QUERIES);
    }

    private static @Nullable Long writeServiceCalls(CappedDatabase cappedDatabase,
            List<Stored.ServiceCallsByType> serviceCalls) throws IOException {
        if (serviceCalls.isEmpty()) {
            return null;
        }
        return cappedDatabase.writeMessages(serviceCalls,
                RollupCappedDatabaseStats.AGGREGATE_SERVICE_CALLS);
    }

    private static @Nullable Long writeProfile(CappedDatabase cappedDatabase,
            @Nullable MutableProfile profile) throws IOException {
        if (profile == null) {
            return null;
        }
        return cappedDatabase.writeMessage(profile.toProto(),
                RollupCappedDatabaseStats.AGGREGATE_PROFILES);
    }

    private static @Nullable Long writeProfile(CappedDatabase cappedDatabase, Profile profile)
            throws IOException {
        return cappedDatabase.writeMessage(profile, RollupCappedDatabaseStats.AGGREGATE_PROFILES);
    }

    private static byte /*@Nullable*/ [] toByteArray(List<? extends AbstractMessage> messages)
            throws IOException {
        if (messages.isEmpty()) {
            return null;
        }
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        for (AbstractMessage message : messages) {
            message.writeDelimitedTo(baos);
        }
        return baos.toByteArray();
    }
}
