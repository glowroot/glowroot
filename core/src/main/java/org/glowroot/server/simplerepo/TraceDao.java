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
import java.util.List;
import java.util.Locale;

import javax.annotation.Nullable;

import com.google.common.base.Strings;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;
import org.checkerframework.checker.tainting.qual.Untainted;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.glowroot.collector.spi.model.ProfileTreeOuterClass.ProfileTree;
import org.glowroot.collector.spi.model.TraceOuterClass.Trace;
import org.glowroot.common.util.OnlyUsedByTests;
import org.glowroot.live.ImmutableTracePoint;
import org.glowroot.live.LiveTraceRepository.Existence;
import org.glowroot.live.LiveTraceRepository.TracePoint;
import org.glowroot.live.LiveTraceRepository.TracePointQuery;
import org.glowroot.server.repo.ImmutableErrorMessageCount;
import org.glowroot.server.repo.ImmutableHeaderPlus;
import org.glowroot.server.repo.ImmutableTraceErrorPoint;
import org.glowroot.server.repo.Result;
import org.glowroot.server.repo.TraceRepository;
import org.glowroot.server.simplerepo.TracePointQueryBuilder.ParameterizedSql;
import org.glowroot.server.simplerepo.util.CappedDatabase;
import org.glowroot.server.simplerepo.util.DataSource;
import org.glowroot.server.simplerepo.util.DataSource.PreparedStatementBinder;
import org.glowroot.server.simplerepo.util.DataSource.ResultSetExtractor;
import org.glowroot.server.simplerepo.util.DataSource.RowMapper;
import org.glowroot.server.simplerepo.util.ImmutableColumn;
import org.glowroot.server.simplerepo.util.ImmutableIndex;
import org.glowroot.server.simplerepo.util.RowMappers;
import org.glowroot.server.simplerepo.util.Schema;
import org.glowroot.server.simplerepo.util.Schema.Column;
import org.glowroot.server.simplerepo.util.Schema.ColumnType;
import org.glowroot.server.simplerepo.util.Schema.Index;

import static com.google.common.base.Preconditions.checkNotNull;
import static org.glowroot.server.simplerepo.util.Checkers.castUntainted;

class TraceDao implements TraceRepository {

    private static final Logger logger = LoggerFactory.getLogger(TraceDao.class);

    private static final ImmutableList<Column> traceColumns = ImmutableList.<Column>of(
            ImmutableColumn.of("server_id", ColumnType.BIGINT),
            ImmutableColumn.of("trace_id", ColumnType.VARCHAR),
            ImmutableColumn.of("partial", ColumnType.BOOLEAN),
            ImmutableColumn.of("slow", ColumnType.BOOLEAN),
            ImmutableColumn.of("error", ColumnType.BOOLEAN),
            ImmutableColumn.of("start_time", ColumnType.BIGINT),
            ImmutableColumn.of("capture_time", ColumnType.BIGINT),
            ImmutableColumn.of("duration_nanos", ColumnType.BIGINT), // nanoseconds
            ImmutableColumn.of("transaction_type", ColumnType.VARCHAR),
            ImmutableColumn.of("transaction_name", ColumnType.VARCHAR),
            ImmutableColumn.of("headline", ColumnType.VARCHAR),
            ImmutableColumn.of("user_id", ColumnType.VARCHAR), // user is a postgres reserved word
            ImmutableColumn.of("error_message", ColumnType.VARCHAR),
            ImmutableColumn.of("header", ColumnType.VARBINARY), // protobuf
            ImmutableColumn.of("entries_capped_id", ColumnType.BIGINT),
            ImmutableColumn.of("profile_capped_id", ColumnType.BIGINT));

    // capture_time column is used for expiring records without using FK with on delete cascade
    private static final ImmutableList<Column> transactionAttributeColumns =
            ImmutableList.<Column>of(ImmutableColumn.of("server_id", ColumnType.BIGINT),
                    ImmutableColumn.of("trace_id", ColumnType.VARCHAR),
                    ImmutableColumn.of("name", ColumnType.VARCHAR),
                    ImmutableColumn.of("value", ColumnType.VARCHAR),
                    ImmutableColumn.of("capture_time", ColumnType.BIGINT));

    private static final ImmutableList<Index> traceIndexes = ImmutableList.<Index>of(
            // duration_nanos, trace_id and error columns are included so h2 can return the result
            // set directly from the index without having to reference the table for each row
            //
            // trace_slow_idx is for slow trace point query and for readOverallSlowCount()
            ImmutableIndex.of("trace_slow_idx", ImmutableList.of("server_id", "transaction_type",
                    "slow", "capture_time", "duration_nanos", "error", "trace_id")),
            // trace_slow_idx is for slow trace point query and for readTransactionSlowCount()
            ImmutableIndex.of("trace_transaction_slow_idx",
                    ImmutableList.of("server_id", "transaction_type", "transaction_name", "slow",
                            "capture_time", "duration_nanos", "error", "trace_id")),
            // trace_error_idx is for error trace point query and for readOverallErrorCount()
            ImmutableIndex.of("trace_error_idx", ImmutableList.of("server_id", "transaction_type",
                    "error", "capture_time", "duration_nanos", "error", "trace_id")),
            // trace_error_idx is for error trace point query and for readTransactionErrorCount()
            ImmutableIndex.of("trace_transaction_error_idx",
                    ImmutableList.of("server_id", "transaction_type", "transaction_name", "error",
                            "capture_time", "duration_nanos", "trace_id")));

    private final DataSource dataSource;
    private final CappedDatabase traceCappedDatabase;

    TraceDao(DataSource dataSource, CappedDatabase traceCappedDatabase) throws SQLException {
        this.dataSource = dataSource;
        this.traceCappedDatabase = traceCappedDatabase;
        Schema schema = dataSource.getSchema();
        schema.syncTable("trace", traceColumns);
        schema.syncIndexes("trace", traceIndexes);
        schema.syncTable("trace_attribute", transactionAttributeColumns);
    }

    public void collect(final long serverId, Trace trace) throws Exception {
        final Trace.Header header = trace.getHeader();
        boolean exists = dataSource.queryForExists("select 1 from trace where server_id = ?"
                + " and trace_id = ?", serverId, header.getId());
        if (exists) {
            dataSource.update(
                    "update trace set partial = ?, slow = ?, error = ?, start_time = ?,"
                            + " capture_time = ?, duration_nanos = ?, transaction_type = ?,"
                            + " transaction_name = ?, headline = ?, user_id = ?, error_message = ?,"
                            + " header = ?, entries_capped_id = ?, profile_capped_id = ?"
                            + " where server_id = ? and trace_id = ?",
                    new TraceBinder(serverId, trace));
        } else {
            dataSource.update(
                    "insert into trace (partial, slow, error, start_time, capture_time,"
                            + " duration_nanos, transaction_type, transaction_name, headline,"
                            + " user_id, error_message, header, entries_capped_id,"
                            + " profile_capped_id, server_id, trace_id)"
                            + " values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    new TraceBinder(serverId, trace));
        }
        if (header.getAttributeCount() > 0) {
            if (exists) {
                dataSource.update("delete from trace_attribute where server_id = ?"
                        + " and trace_id = ?", serverId, header.getId());
            }
            dataSource.batchUpdate(
                    "insert into trace_attribute (server_id, trace_id, name, value, capture_time)"
                            + " values (?, ?, ?, ?, ?)",
                    new PreparedStatementBinder() {
                        @Override
                        public void bind(PreparedStatement preparedStatement) throws SQLException {
                            for (Trace.Attribute attribute : header.getAttributeList()) {
                                for (String value : attribute.getValueList()) {
                                    preparedStatement.setLong(1, serverId);
                                    preparedStatement.setString(2, header.getId());
                                    preparedStatement.setString(3, attribute.getName());
                                    preparedStatement.setString(4, value);
                                    preparedStatement.setLong(5, header.getCaptureTime());
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
        List<TracePoint> points = dataSource.query(parameterizedSql.sql(),
                new TracePointRowMapper(), parameterizedSql.argsAsArray());
        // one extra record over the limit is fetched above to identify if the limit was hit
        return Result.from(points, query.limit());
    }

    @Override
    public long readOverallSlowCount(long serverId, String transactionType, long captureTimeFrom,
            long captureTimeTo) throws Exception {
        return dataSource.queryForLong(
                "select count(*) from trace where server_id = ? and transaction_type = ?"
                        + " and capture_time > ? and capture_time <= ? and slow = ?",
                serverId, transactionType, captureTimeFrom, captureTimeTo, true);
    }

    @Override
    public long readTransactionSlowCount(long serverId, String transactionType,
            String transactionName, long captureTimeFrom, long captureTimeTo) throws Exception {
        return dataSource.queryForLong(
                "select count(*) from trace where server_id = ? and transaction_type = ?"
                        + " and transaction_name = ? and capture_time > ? and capture_time <= ?"
                        + " and slow = ?",
                serverId, transactionType, transactionName, captureTimeFrom, captureTimeTo, true);
    }

    @Override
    public long readOverallErrorCount(long serverId, String transactionType, long captureTimeFrom,
            long captureTimeTo) throws Exception {
        return dataSource.queryForLong(
                "select count(*) from trace where server_id = ? and transaction_type = ?"
                        + " and capture_time > ? and capture_time <= ? and error = ?",
                serverId, transactionType, captureTimeFrom, captureTimeTo, true);
    }

    @Override
    public long readTransactionErrorCount(long serverId, String transactionType,
            String transactionName, long captureTimeFrom, long captureTimeTo) throws Exception {
        return dataSource.queryForLong(
                "select count(*) from trace where server_id = ? and transaction_type = ?"
                        + " and transaction_name = ? and capture_time > ? and capture_time <= ?"
                        + " and error = ?",
                serverId, transactionType, transactionName, captureTimeFrom, captureTimeTo, true);
    }

    @Override
    public List<TraceErrorPoint> readErrorPoints(ErrorMessageQuery query,
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
        List<ErrorMessageCount> points = dataSource.query(parameterizedSql.sql(),
                new ErrorMessageCountRowMapper(), parameterizedSql.argsAsArray());
        // one extra record over the limit is fetched above to identify if the limit was hit
        return Result.from(points, query.limit());
    }

    @Override
    public @Nullable HeaderPlus readHeader(long serverId, String traceId) throws Exception {
        List<HeaderPlus> traces = dataSource.query(
                "select header, entries_capped_id, profile_capped_id from trace where server_id = ?"
                        + " and trace_id = ?",
                new TraceHeaderRowMapper(), serverId, traceId);
        if (traces.isEmpty()) {
            return null;
        }
        if (traces.size() > 1) {
            logger.error("multiple records returned for server id '{}' and trace id '{}'",
                    serverId, traceId);
        }
        return traces.get(0);
    }

    @Override
    public List<Trace.Entry> readEntries(long serverId, String traceId) throws Exception {
        List<Trace.Entry> entries =
                dataSource.query("select entries_capped_id from trace where server_id = ?"
                        + " and trace_id = ?",
                        new EntriesResultExtractor(), serverId, traceId);
        if (entries == null) {
            // data source is closing
            return ImmutableList.of();
        }
        return entries;
    }

    @Override
    public @Nullable ProfileTree readProfileTree(long serverId, String traceId) throws Exception {
        return dataSource.query("select profile_capped_id from trace where server_id = ?"
                + " and trace_id = ?",
                new ProfileTreeResultExtractor(), serverId, traceId);
    }

    @Override
    public void deleteAll(long serverId) throws SQLException {
        dataSource.deleteAll("trace_attribute", serverId);
        dataSource.deleteAll("trace", serverId);
    }

    void deleteBefore(long serverId, long captureTime) throws SQLException {
        dataSource.deleteBefore("trace_attribute", serverId, captureTime);
        dataSource.deleteBefore("trace", serverId, captureTime);
    }

    @Override
    @OnlyUsedByTests
    public long count(long serverId) throws Exception {
        return dataSource.queryForLong("select count(*) from trace where server_id = ?", serverId);
    }

    private ParameterizedSql buildErrorMessageQuery(ErrorMessageQuery query,
            @Untainted String selectClause, @Untainted String groupByClause) {
        String sql = selectClause;
        List<Object> args = Lists.newArrayList();
        sql += " where server_id = ?";
        args.add(query.serverId());
        sql += " and error = ?";
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

    private class EntriesResultExtractor implements ResultSetExtractor<List<Trace.Entry>> {
        @Override
        public List<Trace.Entry> extractData(ResultSet resultSet) throws Exception {
            if (!resultSet.next()) {
                // trace must have just expired while user was viewing it
                return ImmutableList.of();
            }
            long cappedId = resultSet.getLong(1);
            if (resultSet.wasNull()) {
                return ImmutableList.of();
            }
            return traceCappedDatabase.readMessages(cappedId, Trace.Entry.parser());
        }
    }

    private class ProfileTreeResultExtractor
            implements ResultSetExtractor</*@Nullable*/ProfileTree> {
        @Override
        public @Nullable ProfileTree extractData(ResultSet resultSet) throws Exception {
            if (!resultSet.next()) {
                // trace must have just expired while user was viewing it
                return null;
            }
            long cappedId = resultSet.getLong(1);
            if (resultSet.wasNull()) {
                return null;
            }
            return traceCappedDatabase.readMessage(cappedId, ProfileTree.parser());
        }
    }

    private class TraceBinder implements PreparedStatementBinder {

        private final long serverId;
        private final Trace.Header header;
        private final @Nullable Long entriesId;
        private final @Nullable Long profileId;

        private TraceBinder(long serverId, Trace trace) throws IOException {
            this.serverId = serverId;
            this.header = trace.getHeader();

            List<Trace.Entry> entries = trace.getEntryList();
            if (entries.isEmpty()) {
                entriesId = null;
            } else {
                entriesId = traceCappedDatabase.writeMessages(entries,
                        TraceCappedDatabaseStats.TRACE_ENTRIES);
            }

            ProfileTree profileTree = trace.getProfileTree();
            if (profileTree.getNodeCount() == 0) {
                profileId = null;
            } else {
                profileId = traceCappedDatabase.writeMessage(profileTree,
                        TraceCappedDatabaseStats.TRACE_PROFILES);
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
            preparedStatement.setString(i++, header.getUser());
            preparedStatement.setString(i++, header.getError().getMessage());
            preparedStatement.setBytes(i++, header.toByteArray());
            RowMappers.setLong(preparedStatement, i++, entriesId);
            RowMappers.setLong(preparedStatement, i++, profileId);
            preparedStatement.setLong(i++, serverId);
            preparedStatement.setString(i++, header.getId());
        }
    }

    private static class TracePointRowMapper implements RowMapper<TracePoint> {
        @Override
        public TracePoint mapRow(ResultSet resultSet) throws SQLException {
            String traceId = resultSet.getString(1);
            // this checkNotNull is safe since trace_id is the primary key and cannot be null
            checkNotNull(traceId);
            return ImmutableTracePoint.builder()
                    .traceId(traceId)
                    .captureTime(resultSet.getLong(2))
                    .durationNanos(resultSet.getLong(3))
                    .error(resultSet.getBoolean(4))
                    .build();
        }
    }

    private class TraceHeaderRowMapper implements RowMapper<HeaderPlus> {
        @Override
        public HeaderPlus mapRow(ResultSet resultSet) throws Exception {
            byte[] header = checkNotNull(resultSet.getBytes(1));
            Existence entriesExistence = RowMappers.getExistence(resultSet, 2, traceCappedDatabase);
            Existence profileExistence = RowMappers.getExistence(resultSet, 3, traceCappedDatabase);
            return ImmutableHeaderPlus.builder()
                    .header(Trace.Header.parseFrom(header))
                    .entriesExistence(entriesExistence)
                    .profileExistence(profileExistence)
                    .build();
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
