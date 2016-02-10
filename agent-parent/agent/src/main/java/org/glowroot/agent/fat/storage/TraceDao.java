/*
 * Copyright 2011-2016 the original author or authors.
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
package org.glowroot.agent.fat.storage;

import java.io.IOException;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.List;
import java.util.Locale;

import javax.annotation.Nullable;

import com.google.common.base.Strings;
import com.google.common.collect.ImmutableList;
import org.checkerframework.checker.tainting.qual.Untainted;

import org.glowroot.agent.fat.storage.TracePointQueryBuilder.ParameterizedSql;
import org.glowroot.agent.fat.storage.util.CappedDatabase;
import org.glowroot.agent.fat.storage.util.DataSource;
import org.glowroot.agent.fat.storage.util.DataSource.JdbcRowQuery;
import org.glowroot.agent.fat.storage.util.DataSource.JdbcUpdate;
import org.glowroot.agent.fat.storage.util.ImmutableColumn;
import org.glowroot.agent.fat.storage.util.ImmutableIndex;
import org.glowroot.agent.fat.storage.util.RowMappers;
import org.glowroot.agent.fat.storage.util.Schemas.Column;
import org.glowroot.agent.fat.storage.util.Schemas.ColumnType;
import org.glowroot.agent.fat.storage.util.Schemas.Index;
import org.glowroot.common.live.ImmutableTracePoint;
import org.glowroot.common.live.LiveTraceRepository.Existence;
import org.glowroot.common.live.LiveTraceRepository.TraceKind;
import org.glowroot.common.live.LiveTraceRepository.TracePoint;
import org.glowroot.common.live.LiveTraceRepository.TracePointFilter;
import org.glowroot.storage.repo.ImmutableErrorMessageCount;
import org.glowroot.storage.repo.ImmutableErrorMessagePoint;
import org.glowroot.storage.repo.ImmutableErrorMessageResult;
import org.glowroot.storage.repo.ImmutableHeaderPlus;
import org.glowroot.storage.repo.Result;
import org.glowroot.storage.repo.TraceRepository;
import org.glowroot.wire.api.model.ProfileOuterClass.Profile;
import org.glowroot.wire.api.model.TraceOuterClass.Trace;

import static com.google.common.base.Preconditions.checkNotNull;
import static org.glowroot.agent.fat.storage.util.Checkers.castUntainted;

public class TraceDao implements TraceRepository {

    private static final String SERVER_ID = "";

    private static final ImmutableList<Column> traceColumns = ImmutableList.<Column>of(
            ImmutableColumn.of("id", ColumnType.VARCHAR),
            ImmutableColumn.of("partial", ColumnType.BOOLEAN),
            ImmutableColumn.of("slow", ColumnType.BOOLEAN),
            ImmutableColumn.of("error", ColumnType.BOOLEAN),
            ImmutableColumn.of("start_time", ColumnType.BIGINT),
            ImmutableColumn.of("capture_time", ColumnType.BIGINT),
            ImmutableColumn.of("duration_nanos", ColumnType.BIGINT), // nanoseconds
            ImmutableColumn.of("transaction_type", ColumnType.VARCHAR),
            ImmutableColumn.of("transaction_name", ColumnType.VARCHAR),
            ImmutableColumn.of("headline", ColumnType.VARCHAR),
            ImmutableColumn.of("user", ColumnType.VARCHAR),
            ImmutableColumn.of("error_message", ColumnType.VARCHAR),
            ImmutableColumn.of("header", ColumnType.VARBINARY), // protobuf
            ImmutableColumn.of("entries_capped_id", ColumnType.BIGINT),
            ImmutableColumn.of("main_thread_profile_capped_id", ColumnType.BIGINT),
            ImmutableColumn.of("aux_thread_profile_capped_id", ColumnType.BIGINT));

    // capture_time column is used for expiring records without using FK with on delete cascade
    private static final ImmutableList<Column> traceAttributeColumns =
            ImmutableList.<Column>of(ImmutableColumn.of("trace_id", ColumnType.VARCHAR),
                    ImmutableColumn.of("name", ColumnType.VARCHAR),
                    ImmutableColumn.of("value", ColumnType.VARCHAR),
                    ImmutableColumn.of("capture_time", ColumnType.BIGINT));

    private static final ImmutableList<Index> traceIndexes = ImmutableList.<Index>of(
            // duration_nanos, id and error columns are included so database can return the
            // result set directly from the index without having to reference the table for each row
            //
            // trace_overall_slow_idx is for readSlowCount() and readSlowPoints()
            ImmutableIndex.of("trace_overall_slow_idx",
                    ImmutableList.of("transaction_type", "slow", "capture_time", "duration_nanos",
                            "error", "id")),
            // trace_transaction_slow_idx is for readSlowCount() and readSlowPoints()
            ImmutableIndex.of("trace_transaction_slow_idx",
                    ImmutableList.of("transaction_type", "transaction_name", "slow", "capture_time",
                            "duration_nanos", "error", "id")),
            // trace_overall_error_idx is for readErrorCount() and readErrorPoints()
            ImmutableIndex.of("trace_error_idx",
                    ImmutableList.of("transaction_type", "error", "capture_time", "duration_nanos",
                            "error", "id")),
            // trace_transaction_error_idx is for readErrorCount() and readErrorPoints()
            ImmutableIndex.of("trace_transaction_error_idx",
                    ImmutableList.of("transaction_type", "transaction_name", "error",
                            "capture_time", "duration_nanos", "id")),
            // trace_idx is for trace header lookup
            ImmutableIndex.of("trace_idx", ImmutableList.of("id")));

    private static final ImmutableList<Index> traceAttributeIndexes = ImmutableList.<Index>of(
            ImmutableIndex.of("trace_attribute_idx", ImmutableList.of("trace_id")));

    private final DataSource dataSource;
    private final CappedDatabase traceCappedDatabase;
    private final TraceAttributeNameDao traceAttributeNameDao;
    private final TransactionTypeDao transactionTypeDao;

    TraceDao(DataSource dataSource, CappedDatabase traceCappedDatabase,
            TransactionTypeDao transactionTypeDao) throws Exception {
        this.dataSource = dataSource;
        this.traceCappedDatabase = traceCappedDatabase;
        traceAttributeNameDao = new TraceAttributeNameDao(dataSource);
        this.transactionTypeDao = transactionTypeDao;
        dataSource.syncTable("trace", traceColumns);
        dataSource.syncIndexes("trace", traceIndexes);
        dataSource.syncTable("trace_attribute", traceAttributeColumns);
        dataSource.syncIndexes("trace_attribute", traceAttributeIndexes);
    }

    @Override
    public void collect(final String serverId, final Trace trace) throws Exception {
        final Trace.Header header = trace.getHeader();
        boolean exists =
                dataSource.queryForExists("select 1 from trace where id = ?", trace.getId());
        dataSource.update(new TraceUpsert(trace, exists));
        if (header.getAttributeCount() > 0) {
            if (exists) {
                dataSource.update("delete from trace_attribute where trace_id = ?", trace.getId());
            }
            dataSource.batchUpdate(new TraceAttributeInsert(trace));
            for (Trace.Attribute attribute : header.getAttributeList()) {
                traceAttributeNameDao.updateLastCaptureTime(header.getTransactionType(),
                        attribute.getName(), header.getCaptureTime());
            }
        }
        transactionTypeDao.updateLastCaptureTime(trace.getHeader().getTransactionType(),
                trace.getHeader().getCaptureTime());
    }

    @Override
    public List<String> readTraceAttributeNames(String serverRollup, String transactionType)
            throws Exception {
        return traceAttributeNameDao.readTraceAttributeNames(transactionType);
    }

    @Override
    public Result<TracePoint> readSlowPoints(TraceQuery query, TracePointFilter filter, int limit)
            throws Exception {
        return readPoints(TraceKind.SLOW, query, filter, limit);
    }

    @Override
    public Result<TracePoint> readErrorPoints(TraceQuery query, TracePointFilter filter, int limit)
            throws Exception {
        return readPoints(TraceKind.ERROR, query, filter, limit);
    }

    @Override
    public long readSlowCount(TraceQuery query) throws Exception {
        String transactionName = query.transactionName();
        if (transactionName == null) {
            return dataSource.queryForLong(
                    "select count(*) from trace where transaction_type = ? and capture_time > ?"
                            + " and capture_time <= ? and slow = ?",
                    query.transactionType(), query.from(), query.to(), true);
        } else {
            return dataSource.queryForLong(
                    "select count(*) from trace where transaction_type = ? and transaction_name = ?"
                            + " and capture_time > ? and capture_time <= ? and slow = ?",
                    query.transactionType(), transactionName, query.from(), query.to(), true);
        }
    }

    @Override
    public long readErrorCount(TraceQuery query) throws Exception {
        String transactionName = query.transactionName();
        if (transactionName == null) {
            return dataSource.queryForLong(
                    "select count(*) from trace where transaction_type = ? and capture_time > ?"
                            + " and capture_time <= ? and error = ?",
                    query.transactionType(), query.from(), query.to(), true);
        } else {
            return dataSource.queryForLong(
                    "select count(*) from trace where transaction_type = ? and transaction_name = ?"
                            + " and capture_time > ? and capture_time <= ? and error = ?",
                    query.transactionType(), transactionName, query.from(), query.to(), true);
        }
    }

    @Override
    public ErrorMessageResult readErrorMessages(TraceQuery query, ErrorMessageFilter filter,
            long resolutionMillis, long liveCaptureTime, int limit) throws Exception {
        List<ErrorMessagePoint> points = dataSource
                .query(new ErrorPointQuery(query, filter, resolutionMillis, liveCaptureTime));
        List<ErrorMessageCount> counts =
                dataSource.query(new ErrorMessageCountQuery(query, filter, limit + 1));
        // one extra record over the limit is fetched above to identify if the limit was hit
        return ImmutableErrorMessageResult.builder()
                .addAllPoints(points)
                .counts(Result.from(counts, limit))
                .build();
    }

    @Override
    public @Nullable HeaderPlus readHeaderPlus(String serverId, String traceId) throws Exception {
        return dataSource.queryAtMostOne(new TraceHeaderQuery(traceId));
    }

    @Override
    public List<Trace.Entry> readEntries(String serverId, String traceId) throws Exception {
        Long cappedId = dataSource
                .queryForOptionalLong("select entries_capped_id from trace where id = ?", traceId);
        if (cappedId == null) {
            // trace must have just expired while user was viewing it, or data source is closing
            return ImmutableList.of();
        }
        return traceCappedDatabase.readMessages(cappedId, Trace.Entry.parser());
    }

    @Override
    public @Nullable Profile readMainThreadProfile(String serverId, String traceId)
            throws Exception {
        Long cappedId = dataSource.queryForOptionalLong(
                "select main_thread_profile_capped_id from trace where id = ?", traceId);
        if (cappedId == null) {
            // trace must have just expired while user was viewing it, or data source is closing
            return null;
        }
        return traceCappedDatabase.readMessage(cappedId, Profile.parser());
    }

    @Override
    public @Nullable Profile readAuxThreadProfile(String serverId, String traceId)
            throws Exception {
        Long cappedId = dataSource.queryForOptionalLong(
                "select aux_thread_profile_capped_id from trace where id = ?", traceId);
        if (cappedId == null) {
            // trace must have just expired while user was viewing it, or data source is closing
            return null;
        }
        return traceCappedDatabase.readMessage(cappedId, Profile.parser());
    }

    @Override
    public void deleteAll(String serverRollup) throws Exception {
        traceAttributeNameDao.deleteAll();
        dataSource.execute("truncate table trace");
        dataSource.execute("truncate table trace_attribute");
    }

    void deleteBefore(long captureTime) throws Exception {
        traceAttributeNameDao.deleteBefore(captureTime);
        dataSource.deleteBefore("trace", captureTime);
        dataSource.deleteBefore("trace_attribute", captureTime);
    }

    private Result<TracePoint> readPoints(TraceKind traceKind, TraceQuery query,
            TracePointFilter filter, int limit) throws Exception {
        ParameterizedSql parameterizedSql =
                new TracePointQueryBuilder(traceKind, query, filter, limit).getParameterizedSql();
        List<TracePoint> points = dataSource.query(new TracePointQuery(parameterizedSql));
        // one extra record over the limit is fetched above to identify if the limit was hit
        return Result.from(points, limit);
    }

    private static void appendQueryAndFilter(StringBuilder sql, TraceQuery query,
            ErrorMessageFilter filter) {
        sql.append(" and transaction_type = ?");
        String transactionName = query.transactionName();
        if (transactionName != null) {
            sql.append(" and transaction_name = ?");
        }
        sql.append(" and capture_time > ? and capture_time <= ?");
        for (int i = 0; i < filter.includes().size(); i++) {
            sql.append(" and upper(error_message) like ?");
        }
        for (int i = 0; i < filter.excludes().size(); i++) {
            sql.append(" and upper(error_message) not like ?");
        }
    }

    private static int bindQueryAndFilter(PreparedStatement preparedStatement, int startIndex,
            TraceQuery query, ErrorMessageFilter filter) throws SQLException {
        int i = startIndex;
        preparedStatement.setString(i++, query.transactionType());
        String transactionName = query.transactionName();
        if (transactionName != null) {
            preparedStatement.setString(i++, transactionName);
        }
        preparedStatement.setLong(i++, query.from());
        preparedStatement.setLong(i++, query.to());
        for (String include : filter.includes()) {
            preparedStatement.setString(i++, '%' + include.toUpperCase(Locale.ENGLISH) + '%');
        }
        for (String exclude : filter.excludes()) {
            preparedStatement.setString(i++, '%' + exclude.toUpperCase(Locale.ENGLISH) + '%');
        }
        return i;
    }

    private class TraceUpsert implements JdbcUpdate {

        private final boolean update;
        private final String traceId;
        private final Trace.Header header;
        private final @Nullable Long entriesId;
        private final @Nullable Long mainThreadProfileId;
        private final @Nullable Long auxThreadProfileId;

        private TraceUpsert(Trace trace, boolean update) throws IOException {
            this.update = update;
            this.traceId = trace.getId();
            this.header = trace.getHeader();

            List<Trace.Entry> entries = trace.getEntryList();
            if (entries.isEmpty()) {
                entriesId = null;
            } else {
                entriesId = traceCappedDatabase.writeMessages(entries,
                        TraceCappedDatabaseStats.TRACE_ENTRIES);
            }
            if (trace.hasMainThreadProfile()) {
                mainThreadProfileId = traceCappedDatabase.writeMessage(trace.getMainThreadProfile(),
                        TraceCappedDatabaseStats.TRACE_PROFILES);
            } else {
                mainThreadProfileId = null;
            }
            if (trace.hasMainThreadProfile()) {
                auxThreadProfileId = traceCappedDatabase.writeMessage(trace.getAuxThreadProfile(),
                        TraceCappedDatabaseStats.TRACE_PROFILES);
            } else {
                auxThreadProfileId = null;
            }
        }

        @Override
        public @Untainted String getSql() {
            if (update) {
                return "update trace set partial = ?, slow = ?, error = ?, start_time = ?,"
                        + " capture_time = ?, duration_nanos = ?, transaction_type = ?,"
                        + " transaction_name = ?, headline = ?, user = ?, error_message = ?,"
                        + " header = ?, entries_capped_id = ?, main_thread_profile_capped_id = ?,"
                        + " aux_thread_profile_capped_id = ? where id = ?";
            } else {
                return "insert into trace (partial, slow, error, start_time, capture_time,"
                        + " duration_nanos, transaction_type, transaction_name, headline,"
                        + " user, error_message, header, entries_capped_id,"
                        + " main_thread_profile_capped_id, aux_thread_profile_capped_id, id)"
                        + " values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
            }
        }

        // minimal work inside this method as it is called with active connection
        @Override
        public void bind(PreparedStatement preparedStatement) throws SQLException {
            int i = 1;
            preparedStatement.setBoolean(i++, header.getPartial());
            preparedStatement.setBoolean(i++, header.getSlow());
            preparedStatement.setBoolean(i++, header.hasError());
            preparedStatement.setLong(i++, header.getStartTime());
            preparedStatement.setLong(i++, header.getCaptureTime());
            preparedStatement.setLong(i++, header.getDurationNanos());
            preparedStatement.setString(i++, header.getTransactionType());
            preparedStatement.setString(i++, header.getTransactionName());
            preparedStatement.setString(i++, header.getHeadline());
            preparedStatement.setString(i++, Strings.emptyToNull(header.getUser()));
            if (header.hasError()) {
                preparedStatement.setString(i++, header.getError().getMessage());
            } else {
                preparedStatement.setNull(i++, Types.VARCHAR);
            }
            preparedStatement.setBytes(i++, header.toByteArray());
            RowMappers.setLong(preparedStatement, i++, entriesId);
            RowMappers.setLong(preparedStatement, i++, mainThreadProfileId);
            RowMappers.setLong(preparedStatement, i++, auxThreadProfileId);
            preparedStatement.setString(i++, traceId);
        }
    }

    private static class TraceAttributeInsert implements JdbcUpdate {

        private final Trace trace;

        private TraceAttributeInsert(Trace trace) {
            this.trace = trace;
        }

        @Override
        public @Untainted String getSql() {
            return "insert into trace_attribute (trace_id, name, value, capture_time)"
                    + " values (?, ?, ?, ?)";
        }

        @Override
        public void bind(PreparedStatement preparedStatement) throws SQLException {
            Trace.Header header = trace.getHeader();
            for (Trace.Attribute attribute : header.getAttributeList()) {
                for (String value : attribute.getValueList()) {
                    preparedStatement.setString(1, trace.getId());
                    preparedStatement.setString(2, attribute.getName());
                    preparedStatement.setString(3, value);
                    preparedStatement.setLong(4, header.getCaptureTime());
                    preparedStatement.addBatch();
                }
            }

        }
    }

    private static class TracePointQuery implements JdbcRowQuery<TracePoint> {

        private final ParameterizedSql parameterizedSql;

        private TracePointQuery(ParameterizedSql parameterizedSql) {
            this.parameterizedSql = parameterizedSql;
        }

        @Override
        public @Untainted String getSql() {
            return castUntainted(parameterizedSql.sql());
        }

        @Override
        public void bind(PreparedStatement preparedStatement) throws SQLException {
            int i = 1;
            for (Object obj : parameterizedSql.args()) {
                preparedStatement.setObject(i++, obj);
            }
        }

        @Override
        public TracePoint mapRow(ResultSet resultSet) throws SQLException {
            String traceId = checkNotNull(resultSet.getString(1));
            return ImmutableTracePoint.builder()
                    .serverId(SERVER_ID)
                    .traceId(traceId)
                    .captureTime(resultSet.getLong(2))
                    .durationNanos(resultSet.getLong(3))
                    .error(resultSet.getBoolean(4))
                    .build();
        }
    }

    private class TraceHeaderQuery implements JdbcRowQuery<HeaderPlus> {

        private final String traceId;

        private TraceHeaderQuery(String traceId) {
            this.traceId = traceId;
        }

        @Override
        public @Untainted String getSql() {
            return "select header, entries_capped_id, main_thread_profile_capped_id,"
                    + " aux_thread_profile_capped_id from trace where id = ?";
        }

        @Override
        public void bind(PreparedStatement preparedStatement) throws SQLException {
            preparedStatement.setString(1, traceId);
        }

        @Override
        public HeaderPlus mapRow(ResultSet resultSet) throws Exception {
            byte[] header = checkNotNull(resultSet.getBytes(1));
            Existence entriesExistence = RowMappers.getExistence(resultSet, 2, traceCappedDatabase);
            Existence mainThreadProfileExistence =
                    RowMappers.getExistence(resultSet, 3, traceCappedDatabase);
            Existence auxThreadProfileExistence =
                    RowMappers.getExistence(resultSet, 3, traceCappedDatabase);
            Existence profileExistence;
            if (mainThreadProfileExistence == Existence.EXPIRED
                    || auxThreadProfileExistence == Existence.EXPIRED) {
                profileExistence = Existence.EXPIRED;
            } else if (mainThreadProfileExistence == Existence.YES
                    || auxThreadProfileExistence == Existence.YES) {
                profileExistence = Existence.YES;
            } else {
                profileExistence = Existence.NO;
            }
            return ImmutableHeaderPlus.builder()
                    .header(Trace.Header.parseFrom(header))
                    .entriesExistence(entriesExistence)
                    .profileExistence(profileExistence)
                    .build();
        }
    }

    private static class ErrorPointQuery implements JdbcRowQuery<ErrorMessagePoint> {

        private final TraceQuery query;
        private final ErrorMessageFilter filter;
        private final long resolutionMillis;
        private final long liveCaptureTime;

        private ErrorPointQuery(TraceQuery query, ErrorMessageFilter filter, long resolutionMillis,
                long liveCaptureTime) {
            this.query = query;
            this.filter = filter;
            this.resolutionMillis = resolutionMillis;
            this.liveCaptureTime = liveCaptureTime;
        }

        @Override
        public @Untainted String getSql() {
            // need ".0" to force double result
            String captureTimeSql = castUntainted(
                    "ceil(capture_time / " + resolutionMillis + ".0) * " + resolutionMillis);
            StringBuilder sql = new StringBuilder();
            sql.append("select " + captureTimeSql + ", count(*) from trace where error = ?");
            appendQueryAndFilter(sql, query, filter);
            sql.append(" group by " + captureTimeSql + " order by " + captureTimeSql);
            return castUntainted(sql.toString());
        }

        @Override
        public void bind(PreparedStatement preparedStatement) throws SQLException {
            int i = 1;
            preparedStatement.setBoolean(i++, true);
            bindQueryAndFilter(preparedStatement, i, query, filter);
        }

        @Override
        public ErrorMessagePoint mapRow(ResultSet resultSet) throws SQLException {
            long captureTime = Math.min(resultSet.getLong(1), liveCaptureTime);
            long errorCount = resultSet.getLong(2);
            return ImmutableErrorMessagePoint.of(captureTime, errorCount);
        }
    }

    private static class ErrorMessageCountQuery implements JdbcRowQuery<ErrorMessageCount> {

        private final TraceQuery query;
        private final ErrorMessageFilter filter;
        private final int limit;

        private ErrorMessageCountQuery(TraceQuery query, ErrorMessageFilter filter, int limit) {
            this.query = query;
            this.filter = filter;
            this.limit = limit;
        }

        @Override
        public @Untainted String getSql() {
            StringBuilder sql = new StringBuilder();
            sql.append("select error_message, count(*) from trace where error = ?");
            appendQueryAndFilter(sql, query, filter);
            sql.append(" group by error_message order by count(*) desc limit ?");
            return castUntainted(sql.toString());
        }

        @Override
        public void bind(PreparedStatement preparedStatement) throws SQLException {
            int i = 1;
            preparedStatement.setBoolean(i++, true);
            i = bindQueryAndFilter(preparedStatement, i, query, filter);
            preparedStatement.setInt(i++, limit);
        }

        @Override
        public ErrorMessageCount mapRow(ResultSet resultSet) throws SQLException {
            return ImmutableErrorMessageCount.builder()
                    .message(Strings.nullToEmpty(resultSet.getString(1)))
                    .count(resultSet.getLong(2))
                    .build();
        }
    }
}
