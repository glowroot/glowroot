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
package org.glowroot.storage.simplerepo;

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
import com.google.common.collect.Lists;
import org.checkerframework.checker.tainting.qual.Untainted;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.glowroot.common.live.ImmutableTracePoint;
import org.glowroot.common.live.LiveTraceRepository.Existence;
import org.glowroot.common.live.LiveTraceRepository.TraceKind;
import org.glowroot.common.live.LiveTraceRepository.TracePoint;
import org.glowroot.common.live.LiveTraceRepository.TracePointCriteria;
import org.glowroot.common.util.OnlyUsedByTests;
import org.glowroot.storage.repo.ImmutableErrorMessageCount;
import org.glowroot.storage.repo.ImmutableHeaderPlus;
import org.glowroot.storage.repo.ImmutableTraceErrorPoint;
import org.glowroot.storage.repo.Result;
import org.glowroot.storage.repo.TraceRepository;
import org.glowroot.storage.simplerepo.TracePointQueryBuilder.ParameterizedSql;
import org.glowroot.storage.simplerepo.util.CappedDatabase;
import org.glowroot.storage.simplerepo.util.DataSource;
import org.glowroot.storage.simplerepo.util.DataSource.PreparedStatementBinder;
import org.glowroot.storage.simplerepo.util.DataSource.ResultSetExtractor;
import org.glowroot.storage.simplerepo.util.DataSource.RowMapper;
import org.glowroot.storage.simplerepo.util.ImmutableColumn;
import org.glowroot.storage.simplerepo.util.ImmutableIndex;
import org.glowroot.storage.simplerepo.util.RowMappers;
import org.glowroot.storage.simplerepo.util.Schema;
import org.glowroot.storage.simplerepo.util.Schema.Column;
import org.glowroot.storage.simplerepo.util.Schema.ColumnType;
import org.glowroot.storage.simplerepo.util.Schema.Index;
import org.glowroot.wire.api.model.ProfileOuterClass.Profile;
import org.glowroot.wire.api.model.TraceOuterClass.Trace;

import static com.google.common.base.Preconditions.checkNotNull;
import static org.glowroot.storage.simplerepo.util.Checkers.castUntainted;

public class TraceDao implements TraceRepository {

    private static final Logger logger = LoggerFactory.getLogger(TraceDao.class);

    private static final ImmutableList<Column> traceColumns = ImmutableList.<Column>of(
            ImmutableColumn.of("server_id", ColumnType.VARCHAR),
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
            ImmutableColumn.of("user_id", ColumnType.VARCHAR), // user is a postgres reserved word
            ImmutableColumn.of("error_message", ColumnType.VARCHAR),
            ImmutableColumn.of("header", ColumnType.VARBINARY), // protobuf
            ImmutableColumn.of("entries_capped_id", ColumnType.BIGINT),
            ImmutableColumn.of("profile_capped_id", ColumnType.BIGINT));

    // capture_time column is used for expiring records without using FK with on delete cascade
    private static final ImmutableList<Column> traceAttributeColumns =
            ImmutableList.<Column>of(ImmutableColumn.of("server_id", ColumnType.VARCHAR),
                    ImmutableColumn.of("id", ColumnType.VARCHAR),
                    ImmutableColumn.of("name", ColumnType.VARCHAR),
                    ImmutableColumn.of("value", ColumnType.VARCHAR),
                    ImmutableColumn.of("capture_time", ColumnType.BIGINT));

    private static final ImmutableList<Index> traceIndexes = ImmutableList.<Index>of(
            // trace_transaction_type_idx is used by readTransactionTypes
            ImmutableIndex.of("trace_transaction_type_idx",
                    ImmutableList.of("server_id", "transaction_type")),
            // duration_nanos, id and error columns are included so database can return the
            // result set directly from the index without having to reference the table for each row
            //
            // trace_slow_idx is for slow trace point query and for readOverallSlowCount()
            ImmutableIndex.of("trace_slow_idx",
                    ImmutableList.of("server_id", "transaction_type", "slow", "capture_time",
                            "duration_nanos", "error", "id")),
            // trace_transaction_slow_idx is for slow trace point query and for
            // readTransactionSlowCount()
            ImmutableIndex.of("trace_transaction_slow_idx",
                    ImmutableList.of("server_id", "transaction_type", "transaction_name", "slow",
                            "capture_time", "duration_nanos", "error", "id")),
            // trace_error_idx is for error trace point query and for readOverallErrorCount()
            ImmutableIndex.of("trace_error_idx",
                    ImmutableList.of("server_id", "transaction_type", "error", "capture_time",
                            "duration_nanos", "error", "id")),
            // trace_transaction_error_idx is for error trace point query and for
            // readTransactionErrorCount()
            ImmutableIndex.of("trace_transaction_error_idx",
                    ImmutableList.of("server_id", "transaction_type", "transaction_name", "error",
                            "capture_time", "duration_nanos", "id")),
            // trace_idx is for trace header lookup
            ImmutableIndex.of("trace_idx", ImmutableList.of("server_id", "id")));

    private static final ImmutableList<Index> traceAttributeIndexes = ImmutableList.<Index>of(
            ImmutableIndex.of("trace_attribute_idx", ImmutableList.of("server_id", "id")));

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
        Schema schema = dataSource.getSchema();
        schema.syncTable("trace", traceColumns);
        schema.syncIndexes("trace", traceIndexes);
        schema.syncTable("trace_attribute", traceAttributeColumns);
        schema.syncIndexes("trace_attribute", traceAttributeIndexes);
    }

    @Override
    public void collect(final String serverId, final Trace trace) throws Exception {
        final Trace.Header header = trace.getHeader();
        boolean exists = dataSource.queryForExists(
                "select 1 from trace where server_id = ? and id = ?", serverId, trace.getId());
        if (exists) {
            dataSource.update("update trace set partial = ?, slow = ?, error = ?, start_time = ?,"
                    + " capture_time = ?, duration_nanos = ?, transaction_type = ?,"
                    + " transaction_name = ?, headline = ?, user_id = ?, error_message = ?,"
                    + " header = ?, entries_capped_id = ?, profile_capped_id = ?"
                    + " where server_id = ? and id = ?", new TraceBinder(serverId, trace));
        } else {
            dataSource.update(
                    "insert into trace (partial, slow, error, start_time, capture_time,"
                            + " duration_nanos, transaction_type, transaction_name, headline,"
                            + " user_id, error_message, header, entries_capped_id,"
                            + " profile_capped_id, server_id, id)"
                            + " values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    new TraceBinder(serverId, trace));
        }
        if (header.getAttributeCount() > 0) {
            if (exists) {
                dataSource.update("delete from trace_attribute where server_id = ? and id = ?",
                        serverId, trace.getId());
            }
            dataSource.batchUpdate(
                    "insert into trace_attribute (server_id, id, name, value, capture_time)"
                            + " values (?, ?, ?, ?, ?)",
                    new PreparedStatementBinder() {
                        @Override
                        public void bind(PreparedStatement preparedStatement) throws SQLException {
                            for (Trace.Attribute attribute : header.getAttributeList()) {
                                for (String value : attribute.getValueList()) {
                                    preparedStatement.setString(1, serverId);
                                    preparedStatement.setString(2, trace.getId());
                                    preparedStatement.setString(3, attribute.getName());
                                    preparedStatement.setString(4, value);
                                    preparedStatement.setLong(5, header.getCaptureTime());
                                    preparedStatement.addBatch();
                                }
                            }
                        }
                    });
            for (Trace.Attribute attribute : header.getAttributeList()) {
                traceAttributeNameDao.updateLastCaptureTime(serverId, attribute.getName(),
                        header.getCaptureTime());
            }
        }
        // FIXME maintain table of server_id/server_rollup associations and update transaction type
        // for all server rollups
        transactionTypeDao.updateLastCaptureTime(serverId, trace.getHeader().getTransactionType(),
                trace.getHeader().getCaptureTime());
    }

    @Override
    public List<String> readTraceAttributeNames(String serverRollup) throws Exception {
        // FIXME maintain table of server_id/server_rollup associations and join that here
        // (maintain this table on agent "Hello", wipe out prior associations and add new ones)
        return traceAttributeNameDao.readTraceAttributeNames(serverRollup);
    }

    @Override
    public Result<TracePoint> readOverallSlowPoints(String serverRollup, String transactionType,
            long captureTimeFrom, long captureTimeTo, TracePointCriteria criteria, int limit)
                    throws Exception {
        return readPoints(TraceKind.SLOW, serverRollup, transactionType, null, captureTimeFrom,
                captureTimeTo, criteria, limit);
    }

    @Override
    public Result<TracePoint> readTransactionSlowPoints(String serverRollup, String transactionType,
            String transactionName, long captureTimeFrom, long captureTimeTo,
            TracePointCriteria criteria, int limit) throws Exception {
        return readPoints(TraceKind.SLOW, serverRollup, transactionType, transactionName,
                captureTimeFrom, captureTimeTo, criteria, limit);
    }

    @Override
    public Result<TracePoint> readOverallErrorPoints(String serverRollup, String transactionType,
            long captureTimeFrom, long captureTimeTo, TracePointCriteria criteria, int limit)
                    throws Exception {
        return readPoints(TraceKind.ERROR, serverRollup, transactionType, null, captureTimeFrom,
                captureTimeTo, criteria, limit);
    }

    @Override
    public Result<TracePoint> readTransactionErrorPoints(String serverRollup,
            String transactionType, String transactionName, long captureTimeFrom,
            long captureTimeTo, TracePointCriteria criteria, int limit) throws Exception {
        return readPoints(TraceKind.ERROR, serverRollup, transactionType, transactionName,
                captureTimeFrom, captureTimeTo, criteria, limit);
    }

    @Override
    public long readOverallSlowCount(String serverId, String transactionType, long captureTimeFrom,
            long captureTimeTo) throws Exception {
        return dataSource.queryForLong(
                "select count(*) from trace where server_id = ? and transaction_type = ?"
                        + " and capture_time > ? and capture_time <= ? and slow = ?",
                serverId, transactionType, captureTimeFrom, captureTimeTo, true);
    }

    @Override
    public long readTransactionSlowCount(String serverId, String transactionType,
            String transactionName, long captureTimeFrom, long captureTimeTo) throws Exception {
        return dataSource.queryForLong(
                "select count(*) from trace where server_id = ? and transaction_type = ?"
                        + " and transaction_name = ? and capture_time > ? and capture_time <= ?"
                        + " and slow = ?",
                serverId, transactionType, transactionName, captureTimeFrom, captureTimeTo, true);
    }

    @Override
    public long readOverallErrorCount(String serverId, String transactionType, long captureTimeFrom,
            long captureTimeTo) throws Exception {
        return dataSource.queryForLong(
                "select count(*) from trace where server_id = ? and transaction_type = ?"
                        + " and capture_time > ? and capture_time <= ? and error = ?",
                serverId, transactionType, captureTimeFrom, captureTimeTo, true);
    }

    @Override
    public long readTransactionErrorCount(String serverId, String transactionType,
            String transactionName, long captureTimeFrom, long captureTimeTo) throws Exception {
        return dataSource.queryForLong(
                "select count(*) from trace where server_id = ? and transaction_type = ?"
                        + " and transaction_name = ? and capture_time > ? and capture_time <= ?"
                        + " and error = ?",
                serverId, transactionType, transactionName, captureTimeFrom, captureTimeTo, true);
    }

    @Override
    public List<TraceErrorPoint> readErrorPoints(ErrorMessageQuery query, long resolutionMillis,
            long liveCaptureTime) throws Exception {
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
    public @Nullable HeaderPlus readHeader(String serverId, String traceId) throws Exception {
        List<HeaderPlus> traces = dataSource.query(
                "select header, entries_capped_id, profile_capped_id from trace"
                        + " where server_id = ? and id = ?",
                new TraceHeaderRowMapper(), serverId, traceId);
        if (traces.isEmpty()) {
            return null;
        }
        if (traces.size() > 1) {
            logger.error("multiple records returned for server_id '{}' and trace id '{}'", serverId,
                    traceId);
        }
        return traces.get(0);
    }

    @Override
    public List<Trace.Entry> readEntries(String serverId, String traceId) throws Exception {
        List<Trace.Entry> entries = dataSource.query(
                "select entries_capped_id from trace where server_id = ? and id = ?",
                new EntriesResultExtractor(), serverId, traceId);
        if (entries == null) {
            // data source is closing
            return ImmutableList.of();
        }
        return entries;
    }

    @Override
    public @Nullable Profile readProfile(String serverId, String traceId) throws Exception {
        return dataSource.query(
                "select profile_capped_id from trace where server_id = ? and id = ?",
                new ProfileResultExtractor(), serverId, traceId);
    }

    @Override
    public void deleteAll(String serverId) throws Exception {
        traceAttributeNameDao.deleteAll(serverId);
        dataSource.deleteAll("trace", "server_id", serverId);
        dataSource.deleteAll("trace_attribute", "server_id", serverId);
    }

    void deleteBefore(String serverId, long captureTime) throws Exception {
        traceAttributeNameDao.deleteBefore(serverId, captureTime);
        dataSource.deleteBefore("trace", "server_id", serverId, captureTime);
        dataSource.deleteBefore("trace_attribute", "server_id", serverId, captureTime);
    }

    @Override
    @OnlyUsedByTests
    public long count(String serverId) throws Exception {
        return dataSource.queryForLong("select count(*) from trace where server_id = ?", serverId);
    }

    private Result<TracePoint> readPoints(TraceKind traceKind, String serverRollup,
            String transactionType, @Nullable String transactionName, long captureTimeFrom,
            long captureTimeTo, TracePointCriteria criteria, int limit) throws Exception {
        ParameterizedSql parameterizedSql = new TracePointQueryBuilder(traceKind, serverRollup,
                transactionType, transactionName, captureTimeFrom, captureTimeTo, criteria, limit)
                        .getParameterizedSql();
        List<TracePoint> points = dataSource.query(parameterizedSql.sql(),
                new TracePointRowMapper(), parameterizedSql.argsAsArray());
        // one extra record over the limit is fetched above to identify if the limit was hit
        return Result.from(points, limit);
    }

    private ParameterizedSql buildErrorMessageQuery(ErrorMessageQuery query,
            @Untainted String selectClause, @Untainted String groupByClause) {
        String sql = selectClause;
        List<Object> args = Lists.newArrayList();
        // FIXME maintain table of server_id/server_rollup associations and join that here
        // (maintain this table on agent "Hello", wipe out prior associations and add new ones)
        sql += " where server_id = ?";
        args.add(query.serverRollup());
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

    private class ProfileResultExtractor
            implements ResultSetExtractor</*@Nullable*/Profile> {
        @Override
        public @Nullable Profile extractData(ResultSet resultSet) throws Exception {
            if (!resultSet.next()) {
                // trace must have just expired while user was viewing it
                return null;
            }
            long cappedId = resultSet.getLong(1);
            if (resultSet.wasNull()) {
                return null;
            }
            return traceCappedDatabase.readMessage(cappedId, Profile.parser());
        }
    }

    private class TraceBinder implements PreparedStatementBinder {

        private final String serverId;
        private final String traceId;
        private final Trace.Header header;
        private final @Nullable Long entriesId;
        private final @Nullable Long profileId;

        private TraceBinder(String serverId, Trace trace) throws IOException {
            this.serverId = serverId;
            this.traceId = trace.getId();
            this.header = trace.getHeader();

            List<Trace.Entry> entries = trace.getEntryList();
            if (entries.isEmpty()) {
                entriesId = null;
            } else {
                entriesId = traceCappedDatabase.writeMessages(entries,
                        TraceCappedDatabaseStats.TRACE_ENTRIES);
            }

            if (trace.hasProfile()) {
                profileId = traceCappedDatabase.writeMessage(trace.getProfile(),
                        TraceCappedDatabaseStats.TRACE_PROFILES);
            } else {
                profileId = null;
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
            RowMappers.setLong(preparedStatement, i++, profileId);
            preparedStatement.setString(i++, serverId);
            preparedStatement.setString(i++, traceId);
        }
    }

    private static class TracePointRowMapper implements RowMapper<TracePoint> {
        @Override
        public TracePoint mapRow(ResultSet resultSet) throws SQLException {
            String serverId = checkNotNull(resultSet.getString(1));
            String traceId = checkNotNull(resultSet.getString(2));
            return ImmutableTracePoint.builder()
                    .serverId(serverId)
                    .traceId(traceId)
                    .captureTime(resultSet.getLong(3))
                    .durationNanos(resultSet.getLong(4))
                    .error(resultSet.getBoolean(5))
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
