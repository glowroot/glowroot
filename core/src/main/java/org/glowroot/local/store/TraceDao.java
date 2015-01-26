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
package org.glowroot.local.store;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.List;
import java.util.Locale;
import java.util.Map.Entry;

import javax.annotation.Nullable;

import com.google.common.base.Strings;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSetMultimap;
import com.google.common.collect.Lists;
import com.google.common.io.CharSource;
import org.checkerframework.checker.tainting.qual.Untainted;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.glowroot.collector.ImmutableTrace;
import org.glowroot.collector.Trace;
import org.glowroot.collector.TraceRepository;
import org.glowroot.local.store.DataSource.BatchAdder;
import org.glowroot.local.store.DataSource.ResultSetExtractor;
import org.glowroot.local.store.DataSource.RowMapper;
import org.glowroot.local.store.Schemas.Column;
import org.glowroot.local.store.Schemas.Index;
import org.glowroot.markers.OnlyUsedByTests;

import static com.google.common.base.Preconditions.checkNotNull;
import static org.glowroot.common.Checkers.castUntainted;

public class TraceDao implements TraceRepository {

    private static final Logger logger = LoggerFactory.getLogger(TraceDao.class);

    private static final ImmutableList<Column> traceColumns = ImmutableList.<Column>of(
            ImmutablePrimaryKeyColumn.of("id", Types.VARCHAR),
            ImmutableColumn.of("partial", Types.BIGINT),
            ImmutableColumn.of("start_time", Types.BIGINT),
            ImmutableColumn.of("capture_time", Types.BIGINT),
            ImmutableColumn.of("duration", Types.BIGINT), // nanoseconds
            ImmutableColumn.of("transaction_type", Types.VARCHAR),
            ImmutableColumn.of("transaction_name", Types.VARCHAR),
            ImmutableColumn.of("headline", Types.VARCHAR),
            ImmutableColumn.of("error", Types.BOOLEAN), // for searching only
            ImmutableColumn.of("profiled", Types.BOOLEAN), // for searching only
            ImmutableColumn.of("error_message", Types.VARCHAR),
            ImmutableColumn.of("user", Types.VARCHAR),
            ImmutableColumn.of("custom_attributes", Types.VARCHAR), // json data
            ImmutableColumn.of("metrics", Types.VARCHAR), // json data
            ImmutableColumn.of("thread_info", Types.VARCHAR), // json data
            ImmutableColumn.of("gc_infos", Types.VARCHAR), // json data
            ImmutableColumn.of("entries_capped_id", Types.VARCHAR), // capped database id
            ImmutableColumn.of("profile_capped_id", Types.VARCHAR)); // capped database id

    // capture_time column is used for expiring records without using FK with on delete cascade
    private static final ImmutableList<Column> transactionCustomAttributeColumns =
            ImmutableList.<Column>of(
                    ImmutableColumn.of("trace_id", Types.VARCHAR),
                    ImmutableColumn.of("name", Types.VARCHAR),
                    ImmutableColumn.of("value", Types.VARCHAR),
                    ImmutableColumn.of("capture_time", Types.BIGINT));

    private static final ImmutableList<Index> traceIndexes = ImmutableList.<Index>of(
            // trace_idx is for the default trace point query
            //
            // capture_time is listed first (instead of transaction_type) in order to handle both
            // the case where transaction_type is a filter and where it is not
            //
            // duration, id and error columns are included so h2 can return the result set directly
            // from the index without having to reference the table for each row
            ImmutableIndex.of("trace_idx", ImmutableList.of("capture_time", "transaction_type",
                    "duration", "id", "error")),
            // trace_error_message_idx is for readErrorMessageCounts()
            ImmutableIndex.of("trace_error_message_idx", ImmutableList.of("error", "capture_time",
                    "transaction_type", "transaction_name", "error_message")),
            // trace_transaction_count_idx is for readTransactionCount()
            ImmutableIndex.of("trace_transaction_count_idx",
                    ImmutableList.of("transaction_type", "transaction_name", "capture_time")),
            // trace_overall_count_idx is for readOverallCount()
            ImmutableIndex.of("trace_overall_count_idx",
                    ImmutableList.of("transaction_type", "capture_time")));

    private final DataSource dataSource;
    private final CappedDatabase cappedDatabase;

    TraceDao(DataSource dataSource, CappedDatabase cappedDatabase) throws SQLException {
        this.dataSource = dataSource;
        this.cappedDatabase = cappedDatabase;
        upgradeTraceTable(dataSource);
        dataSource.syncTable("trace", traceColumns);
        dataSource.syncIndexes("trace", traceIndexes);
        dataSource.syncTable("trace_custom_attribute", transactionCustomAttributeColumns);
    }

    @Override
    public void store(final Trace trace, CharSource entries, @Nullable CharSource profile)
            throws Exception {
        long entriesId = cappedDatabase.write(entries);
        Long profileId = null;
        if (profile != null) {
            profileId = cappedDatabase.write(profile);
        }
        dataSource.update("merge into trace (id, partial, start_time, capture_time, duration,"
                + " transaction_type, transaction_name, headline, error, profiled,"
                + " error_message, user, custom_attributes, metrics, thread_info, gc_infos,"
                + " entries_capped_id, profile_capped_id) values (?, ?, ?, ?, ?, ?, ?, ?, ?,"
                + " ?, ?, ?, ?, ?, ?, ?, ?, ?)", trace.id(), trace.partial(),
                trace.startTime(), trace.captureTime(), trace.duration(),
                trace.transactionType(), trace.transactionName(), trace.headline(),
                trace.error() != null, profileId != null, trace.error(), trace.user(),
                trace.customAttributes(), trace.metrics(), trace.threadInfo(), trace.gcInfos(),
                entriesId, profileId);
        final ImmutableSetMultimap<String, String> customAttributesForIndexing =
                trace.customAttributesForIndexing();
        if (!customAttributesForIndexing.isEmpty()) {
            dataSource.batchUpdate("insert into trace_custom_attribute (trace_id, name,"
                    + " value, capture_time) values (?, ?, ?, ?)", new BatchAdder() {
                @Override
                public void addBatches(PreparedStatement preparedStatement)
                        throws SQLException {
                    for (Entry<String, String> entry : customAttributesForIndexing.entries()) {
                        preparedStatement.setString(1, trace.id());
                        preparedStatement.setString(2, entry.getKey());
                        preparedStatement.setString(3, entry.getValue());
                        preparedStatement.setLong(4, trace.captureTime());
                        preparedStatement.addBatch();
                    }
                }
            });
        }
    }

    public QueryResult<TracePoint> readPoints(TracePointQuery query) throws SQLException {
        ParameterizedSql parameterizedSql = query.getParameterizedSql();
        ImmutableList<TracePoint> points = dataSource.query(parameterizedSql.sql(),
                new TracePointRowMapper(), parameterizedSql.argsAsArray());
        // one extra record over the limit is fetched above to identify if the limit was hit
        return QueryResult.from(points, query.limit());
    }

    public long readTransactionCount(String transactionType, String transactionName,
            long captureTimeFrom, long captureTimeTo) throws SQLException {
        return dataSource.queryForLong("select count(*) from trace where transaction_type = ?"
                + " and transaction_name = ? and capture_time >= ? and capture_time <= ?",
                transactionType, transactionName, captureTimeFrom, captureTimeTo);
    }

    public long readOverallCount(String transactionType, long captureTimeFrom, long captureTimeTo)
            throws SQLException {
        return dataSource.queryForLong("select count(*) from trace where transaction_type = ?"
                + " and capture_time >= ? and capture_time <= ?", transactionType,
                captureTimeFrom, captureTimeTo);
    }

    public long readTransactionErrorCount(String transactionType, String transactionName,
            long captureTimeFrom, long captureTimeTo) throws SQLException {
        return dataSource.queryForLong("select count(*) from trace where transaction_type = ?"
                + " and transaction_name = ? and capture_time >= ? and capture_time <= ? and"
                + " error = ?", transactionType, transactionName, captureTimeFrom, captureTimeTo,
                true);
    }

    public long readOverallErrorCount(String transactionType, long captureTimeFrom,
            long captureTimeTo) throws SQLException {
        return dataSource.queryForLong("select count(*) from trace where transaction_type = ?"
                + " and capture_time >= ? and capture_time <= ? and error = ?", transactionType,
                captureTimeFrom, captureTimeTo, true);
    }

    public ImmutableList<TraceErrorPoint> readErrorPoints(ErrorMessageQuery query,
            long resolutionMillis) throws SQLException {
        // need ".0" to force double result
        String captureTimeSql = castUntainted(
                "ceil(capture_time / " + resolutionMillis + ".0) * " + resolutionMillis);
        ParameterizedSql parameterizedSql = buildErrorMessageQuery(query,
                "select " + captureTimeSql + ", count(*) from trace",
                "group by " + captureTimeSql + " order by " + captureTimeSql);
        return dataSource.query(parameterizedSql.sql(), new ErrorPointRowMapper(),
                parameterizedSql.argsAsArray());
    }

    public QueryResult<ErrorMessageCount> readErrorMessageCounts(ErrorMessageQuery query)
            throws SQLException {
        ParameterizedSql parameterizedSql = buildErrorMessageQuery(query,
                "select error_message, count(*) from trace",
                "group by error_message order by count(*) desc");
        ImmutableList<ErrorMessageCount> points = dataSource.query(parameterizedSql.sql(),
                new ErrorMessageCountRowMapper(), parameterizedSql.argsAsArray());
        // one extra record over the limit is fetched above to identify if the limit was hit
        return QueryResult.from(points, query.limit());
    }

    public @Nullable Trace readTrace(String traceId) throws SQLException {
        List<Trace> traces = dataSource.query("select id, partial, start_time, capture_time,"
                + " duration, transaction_type, transaction_name, headline, error_message, user,"
                + " custom_attributes, metrics, thread_info, gc_infos, entries_capped_id,"
                + " profile_capped_id from trace where id = ?", new TraceRowMapper(), traceId);
        if (traces.isEmpty()) {
            return null;
        }
        if (traces.size() > 1) {
            logger.error("multiple records returned for trace id: {}", traceId);
        }
        return traces.get(0);
    }

    public @Nullable CharSource readEntries(String traceId) throws SQLException {
        return readFromCappedDatabase("entries_capped_id", traceId);
    }

    public @Nullable CharSource readProfile(String traceId) throws SQLException {
        return readFromCappedDatabase("profile_capped_id", traceId);
    }

    public void deleteAll() throws SQLException {
        dataSource.execute("truncate table trace_custom_attribute");
        dataSource.execute("truncate table trace");
    }

    void deleteBefore(long captureTime) throws SQLException {
        // delete 100 at a time, which is both faster than deleting all at once, and doesn't
        // lock the single jdbc connection for one large chunk of time
        while (true) {
            int deleted = dataSource.update("delete from trace_custom_attribute where"
                    + " capture_time < ? limit 100", captureTime);
            deleted += dataSource.update("delete from trace where capture_time < ? limit 100",
                    captureTime);
            if (deleted == 0) {
                break;
            }
        }
    }

    private @Nullable CharSource readFromCappedDatabase(@Untainted String columnName,
            String traceId) throws SQLException {
        return dataSource.query("select " + columnName + " from trace where id = ?",
                new CappedIdResultExtractor(), traceId);
    }

    @OnlyUsedByTests
    public long count() throws SQLException {
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
        sql += " and capture_time >= ? and capture_time <= ?";
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
            return cappedDatabase.read(cappedId, "{\"overwritten\":true}");

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
                    .duration(resultSet.getLong(3))
                    .error(resultSet.getBoolean(4))
                    .build();
        }
    }

    private class TraceRowMapper implements RowMapper<Trace> {

        @Override
        public Trace mapRow(ResultSet resultSet) throws SQLException {
            String id = resultSet.getString(1);
            // this checkNotNull is safe since id is the primary key and cannot be null
            checkNotNull(id);
            return ImmutableTrace.builder()
                    .id(id)
                    .active(false)
                    .partial(resultSet.getBoolean(2))
                    .startTime(resultSet.getLong(3))
                    .captureTime(resultSet.getLong(4))
                    .duration(resultSet.getLong(5))
                    .transactionType(Strings.nullToEmpty(resultSet.getString(6)))
                    .transactionName(Strings.nullToEmpty(resultSet.getString(7)))
                    .headline(Strings.nullToEmpty(resultSet.getString(8)))
                    .error(resultSet.getString(9))
                    .user(resultSet.getString(10))
                    .customAttributes(resultSet.getString(11))
                    .metrics(resultSet.getString(12))
                    .threadInfo(resultSet.getString(13))
                    .gcInfos(resultSet.getString(14))
                    .entriesExistence(RowMappers.getExistence(resultSet, 15, cappedDatabase))
                    .profileExistence(RowMappers.getExistence(resultSet, 16, cappedDatabase))
                    .build();
        }
    }

    private static class ErrorPointRowMapper implements RowMapper<TraceErrorPoint> {
        @Override
        public TraceErrorPoint mapRow(ResultSet resultSet) throws SQLException {
            long captureTime = resultSet.getLong(1);
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
