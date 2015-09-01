/*
 * Copyright 2011-2015 the original author or authors.
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
package org.glowroot.server.simplerepo;

import java.io.IOException;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Map.Entry;

import javax.annotation.Nullable;

import com.google.common.base.Strings;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;
import com.google.common.io.CharSource;
import org.checkerframework.checker.tainting.qual.Untainted;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.glowroot.collector.spi.ProfileNode;
import org.glowroot.collector.spi.Trace;
import org.glowroot.common.model.EntriesChunkSourceCreator;
import org.glowroot.common.model.ProfileJsonMarshaller;
import org.glowroot.common.util.ChunkSource;
import org.glowroot.live.ImmutableTraceHeader;
import org.glowroot.live.ImmutableTracePoint;
import org.glowroot.live.LiveTraceRepository.TraceHeader;
import org.glowroot.live.LiveTraceRepository.TracePoint;
import org.glowroot.live.LiveTraceRepository.TracePointQuery;
import org.glowroot.markers.OnlyUsedByTests;
import org.glowroot.server.repo.ImmutableErrorMessageCount;
import org.glowroot.server.repo.ImmutableTraceErrorPoint;
import org.glowroot.server.repo.Result;
import org.glowroot.server.repo.TraceRepository;
import org.glowroot.server.repo.helper.JsonMarshaller;
import org.glowroot.server.repo.helper.JsonUnmarshaller;
import org.glowroot.server.simplerepo.TracePointQueryBuilder.ParameterizedSql;
import org.glowroot.server.simplerepo.util.CappedDatabase;
import org.glowroot.server.simplerepo.util.DataSource;
import org.glowroot.server.simplerepo.util.DataSource.PreparedStatementBinder;
import org.glowroot.server.simplerepo.util.DataSource.ResultSetExtractor;
import org.glowroot.server.simplerepo.util.DataSource.RowMapper;
import org.glowroot.server.simplerepo.util.ImmutableColumn;
import org.glowroot.server.simplerepo.util.ImmutableIndex;
import org.glowroot.server.simplerepo.util.RowMappers;
import org.glowroot.server.simplerepo.util.Schemas.Column;
import org.glowroot.server.simplerepo.util.Schemas.Index;

import static com.google.common.base.Preconditions.checkNotNull;
import static org.glowroot.server.simplerepo.util.Checkers.castUntainted;

class TraceDao implements TraceRepository {

    private static final Logger logger = LoggerFactory.getLogger(TraceDao.class);

    private static final ImmutableList<Column> traceColumns = ImmutableList.<Column>of(
            ImmutableColumn.of("id", Types.VARCHAR).withPrimaryKey(true),
            ImmutableColumn.of("partial", Types.BIGINT),
            ImmutableColumn.of("slow", Types.BOOLEAN),
            ImmutableColumn.of("error", Types.BOOLEAN),
            ImmutableColumn.of("start_time", Types.BIGINT),
            ImmutableColumn.of("capture_time", Types.BIGINT),
            ImmutableColumn.of("duration_nanos", Types.BIGINT), // nanoseconds
            ImmutableColumn.of("transaction_type", Types.VARCHAR),
            ImmutableColumn.of("transaction_name", Types.VARCHAR),
            ImmutableColumn.of("headline", Types.VARCHAR),
            ImmutableColumn.of("user", Types.VARCHAR),
            ImmutableColumn.of("custom_attributes", Types.VARCHAR), // json data
            ImmutableColumn.of("custom_detail", Types.VARCHAR), // json data
            ImmutableColumn.of("error_message", Types.VARCHAR),
            ImmutableColumn.of("error_throwable", Types.VARCHAR), // json data
            ImmutableColumn.of("timers", Types.VARCHAR), // json data
            ImmutableColumn.of("thread_cpu_time", Types.BIGINT), // nanoseconds
            ImmutableColumn.of("thread_blocked_time", Types.BIGINT), // nanoseconds
            ImmutableColumn.of("thread_waited_time", Types.BIGINT), // nanoseconds
            ImmutableColumn.of("thread_allocated_bytes", Types.BIGINT),
            ImmutableColumn.of("gc_activity", Types.VARCHAR), // json data
            ImmutableColumn.of("entry_count", Types.BIGINT),
            ImmutableColumn.of("entry_limit_exceeded", Types.BOOLEAN),
            ImmutableColumn.of("entries_capped_id", Types.VARCHAR), // capped database id
            ImmutableColumn.of("profile_sample_count", Types.BIGINT),
            ImmutableColumn.of("profile_limit_exceeded", Types.BOOLEAN),
            // profile json is always from "synthetic root"
            ImmutableColumn.of("profile_capped_id", Types.VARCHAR)); // capped database id

    // capture_time column is used for expiring records without using FK with on delete cascade
    private static final ImmutableList<Column> transactionCustomAttributeColumns =
            ImmutableList.<Column>of(ImmutableColumn.of("trace_id", Types.VARCHAR),
                    ImmutableColumn.of("name", Types.VARCHAR),
                    ImmutableColumn.of("value", Types.VARCHAR),
                    ImmutableColumn.of("capture_time", Types.BIGINT));

    private static final ImmutableList<Index> traceIndexes = ImmutableList.<Index>of(
            // duration_nanos, id and error columns are included so h2 can return the result set
            // directly from the index without having to reference the table for each row
            //
            // trace_slow_idx is for slow trace point query and for readOverallSlowCount()
            ImmutableIndex.of("trace_slow_idx",
                    ImmutableList.of("transaction_type", "slow", "capture_time", "duration_nanos",
                            "error", "id")),
            // trace_slow_idx is for slow trace point query and for readTransactionSlowCount()
            ImmutableIndex.of("trace_transaction_slow_idx",
                    ImmutableList.of("transaction_type", "transaction_name", "slow", "capture_time",
                            "duration_nanos", "error", "id")),
            // trace_error_idx is for error trace point query and for readOverallErrorCount()
            ImmutableIndex.of("trace_error_idx",
                    ImmutableList.of("transaction_type", "error", "capture_time", "duration_nanos",
                            "error", "id")),
            // trace_error_idx is for error trace point query and for readTransactionErrorCount()
            ImmutableIndex.of("trace_transaction_error_idx", ImmutableList.of("transaction_type",
                    "transaction_name", "error", "capture_time", "duration_nanos", "id")));

    private final DataSource dataSource;
    private final CappedDatabase traceCappedDatabase;

    TraceDao(DataSource dataSource, CappedDatabase traceCappedDatabase) throws SQLException {
        this.dataSource = dataSource;
        this.traceCappedDatabase = traceCappedDatabase;
        upgradeTraceTable(dataSource);
        dataSource.syncTable("trace", traceColumns);
        dataSource.syncIndexes("trace", traceIndexes);
        dataSource.syncTable("trace_custom_attribute", transactionCustomAttributeColumns);
    }

    public void collect(final Trace trace) throws Exception {
        dataSource.update(
                "merge into trace (id, partial, slow, error, start_time, capture_time,"
                        + " duration_nanos, transaction_type, transaction_name, headline, user,"
                        + " custom_attributes, custom_detail, error_message, error_throwable,"
                        + " timers, thread_cpu_time, thread_blocked_time, thread_waited_time,"
                        + " thread_allocated_bytes, gc_activity, entry_count, entry_limit_exceeded,"
                        + " entries_capped_id, profile_sample_count, profile_limit_exceeded,"
                        + " profile_capped_id) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?,"
                        + " ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                new TraceBinder(trace));
        if (!trace.customAttributes().isEmpty()) {
            dataSource.batchUpdate(
                    "insert into trace_custom_attribute (trace_id, name, value, capture_time)"
                            + " values (?, ?, ?, ?)",
                    new PreparedStatementBinder() {
                        @Override
                        public void bind(PreparedStatement preparedStatement) throws SQLException {
                            for (Entry<String, ? extends Collection<String>> entry : trace
                                    .customAttributes().entrySet()) {
                                for (String value : entry.getValue()) {
                                    preparedStatement.setString(1, trace.id());
                                    preparedStatement.setString(2, entry.getKey());
                                    preparedStatement.setString(3, value);
                                    preparedStatement.setLong(4, trace.captureTime());
                                    preparedStatement.addBatch();
                                }
                            }
                        }
                    });
        }
    }

    @Override
    public Result<TracePoint> readPoints(TracePointQuery query) throws Exception {
        ParameterizedSql parameterizedSql = new TracePointQueryBuilder(query).getParameterizedSql();
        ImmutableList<TracePoint> points = dataSource.query(parameterizedSql.sql(),
                new TracePointRowMapper(), parameterizedSql.argsAsArray());
        // one extra record over the limit is fetched above to identify if the limit was hit
        return Result.from(points, query.limit());
    }

    @Override
    public long readOverallSlowCount(String transactionType, long captureTimeFrom,
            long captureTimeTo) throws Exception {
        return dataSource.queryForLong(
                "select count(*) from trace where transaction_type = ? and capture_time > ?"
                        + " and capture_time <= ? and slow = ?",
                transactionType, captureTimeFrom, captureTimeTo, true);
    }

    @Override
    public long readTransactionSlowCount(String transactionType, String transactionName,
            long captureTimeFrom, long captureTimeTo) throws Exception {
        return dataSource.queryForLong(
                "select count(*) from trace where transaction_type = ? and transaction_name = ?"
                        + " and capture_time > ? and capture_time <= ? and slow = ?",
                transactionType, transactionName, captureTimeFrom, captureTimeTo, true);
    }

    @Override
    public long readOverallErrorCount(String transactionType, long captureTimeFrom,
            long captureTimeTo) throws Exception {
        return dataSource.queryForLong(
                "select count(*) from trace where transaction_type = ? and capture_time > ?"
                        + " and capture_time <= ? and error = ?",
                transactionType, captureTimeFrom, captureTimeTo, true);
    }

    @Override
    public long readTransactionErrorCount(String transactionType, String transactionName,
            long captureTimeFrom, long captureTimeTo) throws Exception {
        return dataSource.queryForLong(
                "select count(*) from trace where transaction_type = ? and transaction_name = ?"
                        + " and capture_time > ? and capture_time <= ? and error = ?",
                transactionType, transactionName, captureTimeFrom, captureTimeTo, true);
    }

    @Override
    public ImmutableList<TraceErrorPoint> readErrorPoints(ErrorMessageQuery query,
            long resolutionMillis, long liveCaptureTime) throws Exception {
        // need ".0" to force double result
        String captureTimeSql = castUntainted(
                "ceil(capture_time / " + resolutionMillis + ".0) * " + resolutionMillis);
        ParameterizedSql parameterizedSql =
                buildErrorMessageQuery(query, "select " + captureTimeSql + ", count(*) from trace",
                        "group by " + captureTimeSql + " order by " + captureTimeSql);
        return dataSource.query(parameterizedSql.sql(), new ErrorPointRowMapper(liveCaptureTime),
                parameterizedSql.argsAsArray());
    }

    @Override
    public Result<ErrorMessageCount> readErrorMessageCounts(ErrorMessageQuery query)
            throws Exception {
        ParameterizedSql parameterizedSql =
                buildErrorMessageQuery(query, "select error_message, count(*) from trace",
                        "group by error_message order by count(*) desc");
        ImmutableList<ErrorMessageCount> points = dataSource.query(parameterizedSql.sql(),
                new ErrorMessageCountRowMapper(), parameterizedSql.argsAsArray());
        // one extra record over the limit is fetched above to identify if the limit was hit
        return Result.from(points, query.limit());
    }

    @Override
    public @Nullable TraceHeader readTraceHeader(String traceId) throws Exception {
        List<TraceHeader> traces = dataSource.query(
                "select id, partial, error, start_time, capture_time, duration_nanos,"
                        + " transaction_type, transaction_name, headline, user, custom_attributes,"
                        + " custom_detail, error_message, error_throwable, timers, thread_cpu_time,"
                        + " thread_blocked_time, thread_waited_time, thread_allocated_bytes,"
                        + " gc_activity, entry_count, entry_limit_exceeded, entries_capped_id,"
                        + " profile_sample_count, profile_limit_exceeded, profile_capped_id"
                        + " from trace where id = ?",
                new TraceHeaderRowMapper(), traceId);
        if (traces.isEmpty()) {
            return null;
        }
        if (traces.size() > 1) {
            logger.error("multiple records returned for trace id: {}", traceId);
        }
        return traces.get(0);
    }

    @Override
    public @Nullable CharSource readEntries(String traceId) throws Exception {
        return readFromCappedDatabase("entries_capped_id", traceId);
    }

    @Override
    public @Nullable CharSource readProfile(String traceId) throws Exception {
        return readFromCappedDatabase("profile_capped_id", traceId);
    }

    @Override
    public void deleteAll() throws SQLException {
        dataSource.execute("truncate table trace_custom_attribute");
        dataSource.execute("truncate table trace");
    }

    void deleteBefore(long captureTime) throws SQLException {
        dataSource.deleteBefore("trace_custom_attribute", captureTime);
        dataSource.deleteBefore("trace", captureTime);
    }

    private @Nullable CharSource readFromCappedDatabase(@Untainted String columnName,
            String traceId) throws Exception {
        return dataSource.query("select " + columnName + " from trace where id = ?",
                new CappedIdResultExtractor(), traceId);
    }

    @Override
    @OnlyUsedByTests
    public long count() throws Exception {
        return dataSource.queryForLong("select count(*) from trace");
    }

    private ParameterizedSql buildErrorMessageQuery(ErrorMessageQuery query,
            @Untainted String selectClause, @Untainted String groupByClause) {
        String sql = selectClause;
        List<Object> args = Lists.newArrayList();
        sql += " where error = ?";
        args.add(true);
        sql += " and transaction_type = ?";
        args.add(query.transactionType());
        String transactionName = query.transactionName();
        if (transactionName != null) {
            sql += " and transaction_name = ?";
            args.add(transactionName);
        }
        sql += " and capture_time > ? and capture_time <= ?";
        args.add(query.from());
        args.add(query.to());
        for (String include : query.includes()) {
            sql += " and upper(error_message) like ?";
            args.add('%' + include.toUpperCase(Locale.ENGLISH) + '%');
        }
        for (String exclude : query.excludes()) {
            sql += " and upper(error_message) not like ?";
            args.add('%' + exclude.toUpperCase(Locale.ENGLISH) + '%');
        }
        sql += " " + groupByClause;
        return ImmutableParameterizedSql.of(sql, args);
    }

    private static void upgradeTraceTable(DataSource dataSource) throws SQLException {
        if (!dataSource.tableExists("trace")) {
            return;
        }
    }

    private class CappedIdResultExtractor implements ResultSetExtractor</*@Nullable*/CharSource> {
        @Override
        public @Nullable CharSource extractData(ResultSet resultSet) throws SQLException {
            if (!resultSet.next()) {
                // trace must have just expired while user was viewing it
                return CharSource.wrap("{\"expired\":true}");
            }
            long cappedId = resultSet.getLong(1);
            if (resultSet.wasNull()) {
                return null;
            }
            return traceCappedDatabase.read(cappedId, "{\"overwritten\":true}");

        }
    }

    private class TraceBinder implements PreparedStatementBinder {

        private final Trace trace;
        private final @Nullable Long entriesId;
        private final @Nullable Long profileId;
        private final @Nullable String customAttributes;
        private final @Nullable String customDetail;
        private final @Nullable String errorThrowable;
        private final String timers;
        private final @Nullable String gcActivity;
        private final long profileSampleCount;

        private TraceBinder(Trace trace) throws IOException {
            this.trace = trace;

            ChunkSource entries =
                    EntriesChunkSourceCreator.createEntriesChunkSource(trace.entries());
            if (entries == null) {
                entriesId = null;
            } else {
                entriesId =
                        traceCappedDatabase.write(entries, TraceCappedDatabaseStats.TRACE_ENTRIES);
            }

            ProfileNode syntheticRootProfileNode = trace.syntheticRootProfileNode();
            if (syntheticRootProfileNode == null) {
                profileId = null;
                profileSampleCount = 0;
            } else {
                profileId = traceCappedDatabase.write(
                        CharSource.wrap(ProfileJsonMarshaller.marshal(syntheticRootProfileNode)),
                        TraceCappedDatabaseStats.TRACE_PROFILES);
                profileSampleCount = syntheticRootProfileNode.sampleCount();
            }

            customAttributes = JsonMarshaller.marshalCustomAttributes(trace.customAttributes());
            customDetail = JsonMarshaller.marshalDetailMap(trace.customDetail());
            errorThrowable = JsonMarshaller.marshal(trace.errorThrowable());
            timers = JsonMarshaller.marshal(trace.rootTimer());
            gcActivity = JsonMarshaller.marshalGcActivity(trace.gcActivity());
        }

        // minimal work inside this method as it is called with active connection
        @Override
        public void bind(PreparedStatement preparedStatement) throws Exception {
            int i = 1;
            preparedStatement.setString(i++, trace.id());
            preparedStatement.setBoolean(i++, trace.partial());
            preparedStatement.setBoolean(i++, trace.slow());
            preparedStatement.setBoolean(i++, trace.error());
            preparedStatement.setLong(i++, trace.startTime());
            preparedStatement.setLong(i++, trace.captureTime());
            preparedStatement.setLong(i++, trace.durationNanos());
            preparedStatement.setString(i++, trace.transactionType());
            preparedStatement.setString(i++, trace.transactionName());
            preparedStatement.setString(i++, trace.headline());
            preparedStatement.setString(i++, trace.user());
            preparedStatement.setString(i++, customAttributes);
            preparedStatement.setString(i++, customDetail);
            preparedStatement.setString(i++, trace.errorMessage());
            preparedStatement.setString(i++, errorThrowable);
            preparedStatement.setString(i++, timers);
            RowMappers.setNotAvailableAwareLong(preparedStatement, i++, trace.threadCpuNanos());
            RowMappers.setNotAvailableAwareLong(preparedStatement, i++, trace.threadBlockedNanos());
            RowMappers.setNotAvailableAwareLong(preparedStatement, i++, trace.threadWaitedNanos());
            RowMappers.setNotAvailableAwareLong(preparedStatement, i++,
                    trace.threadAllocatedBytes());
            preparedStatement.setString(i++, gcActivity);
            preparedStatement.setInt(i++, trace.entries().size());
            preparedStatement.setBoolean(i++, trace.entryLimitExceeded());
            RowMappers.setLong(preparedStatement, i++, entriesId);
            preparedStatement.setLong(i++, profileSampleCount);
            preparedStatement.setBoolean(i++, trace.profileLimitExceeded());
            RowMappers.setLong(preparedStatement, i++, profileId);
        }
    }

    private static class TracePointRowMapper implements RowMapper<TracePoint> {
        @Override
        public TracePoint mapRow(ResultSet resultSet) throws SQLException {
            String id = resultSet.getString(1);
            // this checkNotNull is safe since id is the primary key and cannot be null
            checkNotNull(id);
            return ImmutableTracePoint.builder()
                    .id(id)
                    .captureTime(resultSet.getLong(2))
                    .durationNanos(resultSet.getLong(3))
                    .error(resultSet.getBoolean(4))
                    .build();
        }
    }

    private class TraceHeaderRowMapper implements RowMapper<TraceHeader> {

        @Override
        public TraceHeader mapRow(ResultSet resultSet) throws Exception {
            int columnIndex = 1;
            String id = resultSet.getString(columnIndex++);
            // this checkNotNull is safe since id is the primary key and cannot be null
            checkNotNull(id);
            ImmutableTraceHeader.Builder builder = ImmutableTraceHeader.builder()
                    .id(id)
                    .active(false)
                    .partial(resultSet.getBoolean(columnIndex++))
                    .error(resultSet.getBoolean(columnIndex++))
                    .startTime(resultSet.getLong(columnIndex++))
                    .captureTime(resultSet.getLong(columnIndex++))
                    .durationNanos(resultSet.getLong(columnIndex++))
                    .transactionType(Strings.nullToEmpty(resultSet.getString(columnIndex++)))
                    .transactionName(Strings.nullToEmpty(resultSet.getString(columnIndex++)))
                    .headline(Strings.nullToEmpty(resultSet.getString(columnIndex++)))
                    .user(resultSet.getString(columnIndex++));

            builder.customAttributes(
                    JsonUnmarshaller.unmarshalCustomAttributes(resultSet.getString(columnIndex++)));
            builder.customDetail(
                    cast(JsonUnmarshaller.unmarshalDetailMap(resultSet.getString(columnIndex++))));
            builder.errorMessage(resultSet.getString(columnIndex++));
            builder.errorThrowable(
                    JsonUnmarshaller.unmarshalThrowable(resultSet.getString(columnIndex++)));

            String timers = checkNotNull(resultSet.getString(columnIndex++));
            builder.rootTimer(JsonUnmarshaller.unmarshalTraceTimers(timers));

            builder.threadCpuNanos(RowMappers.getNotAvailableAwareLong(resultSet, columnIndex++));
            builder.threadBlockedNanos(
                    RowMappers.getNotAvailableAwareLong(resultSet, columnIndex++));
            builder.threadWaitedNanos(
                    RowMappers.getNotAvailableAwareLong(resultSet, columnIndex++));
            builder.threadAllocatedBytes(
                    RowMappers.getNotAvailableAwareLong(resultSet, columnIndex++));

            builder.gcActivity(
                    JsonUnmarshaller.unmarshalGcActivity(resultSet.getString(columnIndex++)));

            builder.entryCount(resultSet.getInt(columnIndex++));
            builder.entryLimitExceeded(resultSet.getBoolean(columnIndex++));
            builder.entriesExistence(
                    RowMappers.getExistence(resultSet, columnIndex++, traceCappedDatabase));
            builder.profileSampleCount(resultSet.getLong(columnIndex++));
            builder.profileLimitExceeded(resultSet.getBoolean(columnIndex++));
            builder.profileExistence(
                    RowMappers.getExistence(resultSet, columnIndex++, traceCappedDatabase));
            return builder.build();
        }

        @SuppressWarnings("return.type.incompatible")
        private Map<String, ? extends Object> cast(
                Map<String, ? extends /*@Nullable*/Object> detail) {
            return detail;
        }
    }

    private static class ErrorPointRowMapper implements RowMapper<TraceErrorPoint> {
        private final long liveCaptureTime;
        private ErrorPointRowMapper(long liveCaptureTime) {
            this.liveCaptureTime = liveCaptureTime;
        }
        @Override
        public TraceErrorPoint mapRow(ResultSet resultSet) throws SQLException {
            long captureTime = Math.min(resultSet.getLong(1), liveCaptureTime);
            long errorCount = resultSet.getLong(2);
            return ImmutableTraceErrorPoint.of(captureTime, errorCount);
        }
    }

    private static class ErrorMessageCountRowMapper implements RowMapper<ErrorMessageCount> {
        @Override
        public ErrorMessageCount mapRow(ResultSet resultSet) throws SQLException {
            return ImmutableErrorMessageCount.builder()
                    .message(Strings.nullToEmpty(resultSet.getString(1)))
                    .count(resultSet.getLong(2))
                    .build();
        }
    }
}
