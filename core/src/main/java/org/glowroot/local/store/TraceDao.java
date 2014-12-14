/*
 * Copyright 2011-2014 the original author or authors.
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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.glowroot.collector.ImmutableTrace;
import org.glowroot.collector.Trace;
import org.glowroot.collector.TraceRepository;
import org.glowroot.local.store.DataSource.BatchAdder;
import org.glowroot.local.store.DataSource.RowMapper;
import org.glowroot.local.store.FileBlock.InvalidBlockIdFormatException;
import org.glowroot.local.store.Schemas.Column;
import org.glowroot.local.store.Schemas.Index;
import org.glowroot.markers.OnlyUsedByTests;

import static com.google.common.base.Preconditions.checkNotNull;

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
            ImmutableColumn.of("entries_id", Types.VARCHAR), // capped database id
            ImmutableColumn.of("profile_id", Types.VARCHAR), // capped database id
            ImmutableColumn.of("outlier_profile_id", Types.VARCHAR)); // capped database id

    // capture_time column is used for expiring records without using FK with on delete cascade
    private static final ImmutableList<Column> transactionCustomAttributeColumns =
            ImmutableList.<Column>of(
                    ImmutableColumn.of("trace_id", Types.VARCHAR),
                    ImmutableColumn.of("name", Types.VARCHAR),
                    ImmutableColumn.of("value", Types.VARCHAR),
                    ImmutableColumn.of("capture_time", Types.BIGINT));

    // TODO all of the columns needed for the trace points query are no longer in the same index
    // (number of columns has grown), either update the index or the comment
    //
    // this index includes all of the columns needed for the trace points query so h2 can return
    // result set directly from the index without having to reference the table for each row
    private static final ImmutableList<Index> traceIndexes = ImmutableList.<Index>of(
            ImmutableIndex.of("trace_idx", ImmutableList.of("capture_time", "transaction_type",
                    "duration", "id", "error")),
            // trace_error_message_idx is for readErrorMessageCounts()
            ImmutableIndex.of("trace_error_message_idx", ImmutableList.of("transaction_type",
                    "transaction_name", "capture_time", "error_message")),
            // trace_count_idx is for readOverallCount()
            ImmutableIndex.of("trace_count_idx",
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
    public void store(final Trace trace, CharSource entries, @Nullable CharSource profile,
            @Nullable CharSource outlierProfile) {
        String entriesId = cappedDatabase.write(entries).getId();
        String profileId = null;
        if (profile != null) {
            profileId = cappedDatabase.write(profile).getId();
        }
        String outlierProfileId = null;
        if (outlierProfile != null) {
            outlierProfileId = cappedDatabase.write(outlierProfile).getId();
        }
        try {
            dataSource.update("merge into trace (id, partial, start_time, capture_time, duration,"
                    + " transaction_type, transaction_name, headline, error, profiled,"
                    + " error_message, user, custom_attributes, metrics, thread_info, gc_infos,"
                    + " entries_id, profile_id, outlier_profile_id) values (?, ?, ?, ?, ?, ?, ?,"
                    + " ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)", trace.id(), trace.partial(),
                    trace.startTime(), trace.captureTime(), trace.duration(),
                    trace.transactionType(), trace.transactionName(), trace.headline(),
                    trace.error() != null, profileId != null, trace.error(), trace.user(),
                    trace.customAttributes(), trace.metrics(), trace.threadInfo(),
                    trace.gcInfos(), entriesId, profileId, outlierProfileId);
            final ImmutableSetMultimap<String, String> customAttributesForIndexing =
                    trace.customAttributesForIndexing();
            if (customAttributesForIndexing == null) {
                logger.warn("trace customAttributesForIndex was not provided");
            } else if (!customAttributesForIndexing.isEmpty()) {
                dataSource.batchUpdate("insert into trace_custom_attribute (trace_id, name,"
                        + " value, capture_time) values (?, ?, ?, ?)", new BatchAdder() {
                    @Override
                    public void addBatches(PreparedStatement preparedStatement)
                            throws SQLException {
                        // customAttributesForIndexing is final and null check performed above
                        checkNotNull(customAttributesForIndexing);
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
        } catch (SQLException e) {
            logger.error(e.getMessage(), e);
        }
    }

    public QueryResult<TracePoint> readPoints(TracePointQuery query) throws SQLException {
        ParameterizedSql parameterizedSql = getParameterizedSql(query);
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

    public ImmutableList<ErrorPoint> readErrorPoints(ErrorMessageQuery query, long resolutionMillis)
            throws SQLException {
        ParameterizedSql parameterizedSql = getErrorPointParameterizedSql(query, resolutionMillis);
        return dataSource.query(parameterizedSql.sql(), new ErrorPointRowMapper(),
                parameterizedSql.argsAsArray());
    }

    public QueryResult<ErrorMessageCount> readErrorMessageCounts(ErrorMessageQuery query)
            throws SQLException {
        ParameterizedSql parameterizedSql = getErrorMessageCountParameterizedSql(query);
        ImmutableList<ErrorMessageCount> points = dataSource.query(parameterizedSql.sql(),
                new ErrorMessageCountRowMapper(), parameterizedSql.argsAsArray());
        // one extra record over the limit is fetched above to identify if the limit was hit
        return QueryResult.from(points, query.limit());
    }

    public @Nullable Trace readTrace(String traceId) throws SQLException {
        List<Trace> traces = dataSource.query("select id, partial, start_time, capture_time,"
                + " duration, transaction_type, transaction_name, headline, error_message, user,"
                + " custom_attributes, metrics, thread_info, gc_infos, entries_id, profile_id,"
                + " outlier_profile_id from trace where id = ?", new TraceRowMapper(), traceId);
        if (traces.isEmpty()) {
            return null;
        }
        if (traces.size() > 1) {
            logger.error("multiple records returned for trace id: {}", traceId);
        }
        return traces.get(0);
    }

    public @Nullable CharSource readEntries(String traceId) throws SQLException {
        return readFromCappedDatabase("entries_id", traceId);
    }

    public @Nullable CharSource readProfile(String traceId) throws SQLException {
        return readFromCappedDatabase("profile_id", traceId);
    }

    public @Nullable CharSource readOutlierProfile(String traceId) throws SQLException {
        return readFromCappedDatabase("outlier_profile_id", traceId);
    }

    public void deleteAll() {
        try {
            dataSource.execute("truncate table trace_custom_attribute");
            dataSource.execute("truncate table trace");
        } catch (SQLException e) {
            logger.error(e.getMessage(), e);
        }
    }

    void deleteBefore(long captureTime) {
        try {
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
        } catch (SQLException e) {
            logger.error(e.getMessage(), e);
        }
    }

    private @Nullable CharSource readFromCappedDatabase(String columnName, String traceId)
            throws SQLException {
        List<String> ids = dataSource.query("select " + columnName + " from trace where id = ?",
                new SingleStringRowMapper(), traceId);
        if (ids.isEmpty()) {
            // trace must have just expired while user was viewing it
            logger.debug("no trace found for id: {}", traceId);
            return CharSource.wrap("{\"expired\":true}");
        }
        if (ids.size() > 1) {
            throw new SQLException("Multiple records returned for trace id: " + traceId);
        }
        String id = ids.get(0);
        if (id.equals("")) {
            return null;
        }
        FileBlock fileBlock;
        try {
            fileBlock = FileBlock.from(id);
        } catch (InvalidBlockIdFormatException e) {
            throw new SQLException(e);
        }
        return cappedDatabase.read(fileBlock, "{\"overwritten\":true}");
    }

    @OnlyUsedByTests
    public @Nullable Trace getLastTrace() throws SQLException {
        List<String> ids = dataSource.query("select id from trace order by capture_time desc"
                + " limit 1", new StringRowMapper());
        if (ids.isEmpty()) {
            return null;
        }
        return readTrace(ids.get(0));
    }

    @OnlyUsedByTests
    public long count() throws SQLException {
        return dataSource.queryForLong("select count(*) from trace");
    }

    private static ParameterizedSql getParameterizedSql(TracePointQuery query) {
        // TODO all of these columns are no longer in the same index (number of columns has grown)
        // either update index or comment
        //
        // all of these columns should be in the same index so h2 can return result set directly
        // from the index without having to reference the table for each row
        //
        // capture time lower bound is non-inclusive so that aggregate data intervals can be mapped
        // to their trace points (aggregate data intervals are non-inclusive on lower bound and
        // inclusive on upper bound)
        String sql = "select trace.id, trace.capture_time, trace.duration, trace.error from trace";
        List<Object> args = Lists.newArrayList();
        ParameterizedSql customAttributeJoin = getTraceCustomAttributeJoin(query);
        if (customAttributeJoin != null) {
            sql += customAttributeJoin.sql();
            args.addAll(customAttributeJoin.args());
        } else {
            sql += " where";
        }
        sql += " trace.capture_time > ? and trace.capture_time <= ?";
        args.add(query.from());
        args.add(query.to());
        long durationLow = query.durationLow();
        if (durationLow != 0) {
            sql += " and trace.duration >= ?";
            args.add(durationLow);
        }
        Long durationHigh = query.durationHigh();
        if (durationHigh != null) {
            sql += " and trace.duration <= ?";
            args.add(durationHigh);
        }
        String transactionType = query.transactionType();
        if (!Strings.isNullOrEmpty(transactionType)) {
            sql += " and trace.transaction_type = ?";
            args.add(transactionType);
        }
        if (query.errorOnly()) {
            sql += " and trace.error = ?";
            args.add(true);
        }
        StringComparator transactionNameComparator = query.transactionNameComparator();
        String transactionName = query.transactionName();
        if (transactionNameComparator != null && !Strings.isNullOrEmpty(transactionName)) {
            sql += " and upper(trace.transaction_name) "
                    + transactionNameComparator.getComparator() + " ?";
            args.add(transactionNameComparator.formatParameter(
                    transactionName.toUpperCase(Locale.ENGLISH)));
        }
        StringComparator headlineComparator = query.headlineComparator();
        String headline = query.headline();
        if (headlineComparator != null && !Strings.isNullOrEmpty(headline)) {
            sql += " and upper(trace.headline) " + headlineComparator.getComparator() + " ?";
            args.add(headlineComparator.formatParameter(headline.toUpperCase(Locale.ENGLISH)));
        }
        StringComparator errorComparator = query.errorComparator();
        String error = query.error();
        if (errorComparator != null && !Strings.isNullOrEmpty(error)) {
            sql += " and upper(trace.error_message) " + errorComparator.getComparator() + " ?";
            args.add(errorComparator.formatParameter(error.toUpperCase(Locale.ENGLISH)));
        }
        StringComparator userComparator = query.userComparator();
        String user = query.user();
        if (userComparator != null && !Strings.isNullOrEmpty(user)) {
            sql += " and upper(trace.user) " + userComparator.getComparator() + " ?";
            args.add(userComparator.formatParameter(user.toUpperCase(Locale.ENGLISH)));
        }
        sql += " order by trace.duration desc limit ?";
        // +1 is to identify if limit was exceeded
        args.add(query.limit() + 1);
        return ImmutableParameterizedSql.of(sql, args);
    }

    private static @Nullable ParameterizedSql getTraceCustomAttributeJoin(TracePointQuery query) {
        String criteria = "";
        List<Object> criteriaArgs = Lists.newArrayList();
        String customAttributeName = query.customAttributeName();
        if (!Strings.isNullOrEmpty(customAttributeName)) {
            criteria += " upper(attr.name) = ? and";
            criteriaArgs.add(customAttributeName.toUpperCase(Locale.ENGLISH));
        }
        StringComparator customAttributeValueComparator = query.customAttributeValueComparator();
        String customAttributeValue = query.customAttributeValue();
        if (customAttributeValueComparator != null
                && !Strings.isNullOrEmpty(customAttributeValue)) {
            criteria += " upper(attr.value) " + customAttributeValueComparator.getComparator()
                    + " ? and";
            criteriaArgs.add(customAttributeValueComparator.formatParameter(
                    customAttributeValue.toUpperCase(Locale.ENGLISH)));
        }
        if (criteria.equals("")) {
            return null;
        } else {
            String sql = ", trace_custom_attribute attr where attr.trace_id = trace.id and"
                    + " attr.capture_time > ? and attr.capture_time <= ? and" + criteria;
            List<Object> args = Lists.newArrayList();
            args.add(query.from());
            args.add(query.to());
            args.addAll(criteriaArgs);
            return ImmutableParameterizedSql.of(sql, args);
        }
    }

    private ParameterizedSql getErrorPointParameterizedSql(ErrorMessageQuery query,
            long resolutionMillis) {
        // need ".0" to force double result
        String captureTimeSql = "ceil(capture_time / " + resolutionMillis + ".0) * "
                + resolutionMillis;
        return buildErrorMessageQuery(query,
                "select " + captureTimeSql + ", count(*) from trace",
                "group by " + captureTimeSql + " order by " + captureTimeSql);
    }

    private ParameterizedSql getErrorMessageCountParameterizedSql(ErrorMessageQuery query) {
        return buildErrorMessageQuery(query, "select error_message, count(*) from trace",
                "group by error_message order by count(*) desc");
    }

    private ParameterizedSql buildErrorMessageQuery(ErrorMessageQuery query, String selectClause,
            String groupByClause) {
        String sql = selectClause;
        List<Object> args = Lists.newArrayList();
        sql += " where error = ?";
        args.add(true);
        String transactionType = query.transactionType();
        String transactionName = query.transactionName();
        if (transactionType != null && transactionName != null) {
            sql += " and transaction_type = ? and transaction_name = ?";
            args.add(transactionType);
            args.add(transactionName);
        }
        sql += " and capture_time >= ? and capture_time <= ? and error = ?";
        args.add(query.from());
        args.add(query.to());
        args.add(true);
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
        for (Column column : dataSource.getColumns("trace")) {
            if (column.name().equals("grouping")) {
                dataSource.execute("alter table trace alter column grouping rename to"
                        + " transaction_name");
                dataSource.execute("alter table trace add column headline varchar");
                dataSource.execute("update trace set headline = transaction_name");
                break;
            }
            if (column.name().equals("bucket")) {
                // first grouping was renamed to bucket, then to transaction_name
                dataSource.execute("alter table trace alter column bucket rename to"
                        + " transaction_name");
            }
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
                    .entriesExistence(
                            RowMappers.getExistence(resultSet.getString(15), cappedDatabase))
                    .profileExistence(
                            RowMappers.getExistence(resultSet.getString(16), cappedDatabase))
                    .outlierProfileExistence(
                            RowMappers.getExistence(resultSet.getString(17), cappedDatabase))
                    .build();
        }
    }

    private static class ErrorPointRowMapper implements RowMapper<ErrorPoint> {
        @Override
        public ErrorPoint mapRow(ResultSet resultSet) throws SQLException {
            long captureTime = resultSet.getLong(1);
            long errorCount = resultSet.getLong(2);
            return new ErrorPoint(captureTime, errorCount);
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

    private static class StringRowMapper implements RowMapper<String> {
        @Override
        public String mapRow(ResultSet resultSet) throws SQLException {
            // this checkNotNull is safe since id is the primary key and cannot be null
            return checkNotNull(resultSet.getString(1));
        }
    }

    private static class SingleStringRowMapper implements RowMapper<String> {
        @Override
        public String mapRow(ResultSet resultSet) throws SQLException {
            // need to use empty instead of null since this is added to ImmutableList which does not
            // allow nulls
            return Strings.nullToEmpty(resultSet.getString(1));
        }
    }
}
