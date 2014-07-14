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

import com.google.common.base.Strings;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSetMultimap;
import com.google.common.collect.Lists;
import com.google.common.io.CharSource;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.glowroot.collector.Existence;
import org.glowroot.collector.Snapshot;
import org.glowroot.collector.SnapshotRepository;
import org.glowroot.local.store.DataSource.BatchAdder;
import org.glowroot.local.store.DataSource.RowMapper;
import org.glowroot.local.store.FileBlock.InvalidBlockIdFormatException;
import org.glowroot.local.store.Schemas.Column;
import org.glowroot.local.store.Schemas.Index;
import org.glowroot.local.store.Schemas.PrimaryKeyColumn;
import org.glowroot.markers.OnlyUsedByTests;
import org.glowroot.markers.Singleton;

import static com.google.common.base.Preconditions.checkNotNull;

/**
 * Data access object for storing and reading trace snapshot data from the embedded H2 database.
 * 
 * @author Trask Stalnaker
 * @since 0.5
 */
@Singleton
public class SnapshotDao implements SnapshotRepository {

    private static final Logger logger = LoggerFactory.getLogger(SnapshotDao.class);

    private static final ImmutableList<Column> snapshotColumns = ImmutableList.of(
            new PrimaryKeyColumn("id", Types.VARCHAR),
            new Column("stuck", Types.BIGINT),
            new Column("start_time", Types.BIGINT),
            new Column("capture_time", Types.BIGINT),
            new Column("duration", Types.BIGINT), // nanoseconds
            new Column("transaction_type", Types.VARCHAR),
            new Column("transaction_name", Types.VARCHAR),
            new Column("headline", Types.VARCHAR),
            new Column("error", Types.BOOLEAN), // for searching only
            new Column("profiled", Types.BOOLEAN), // for searching only
            new Column("error_message", Types.VARCHAR),
            new Column("user", Types.VARCHAR),
            new Column("attributes", Types.VARCHAR), // json data
            new Column("trace_metrics", Types.VARCHAR), // json data
            new Column("thread_info", Types.VARCHAR), // json data
            new Column("gc_infos", Types.VARCHAR), // json data
            new Column("spans_id", Types.VARCHAR), // capped database id
            new Column("profile_id", Types.VARCHAR), // capped database id
            new Column("outlier_profile_id", Types.VARCHAR)); // capped database id

    // capture_time column is used for expiring records without using FK with on delete cascade
    private static final ImmutableList<Column> snapshotAttributeColumns = ImmutableList.of(
            new Column("snapshot_id", Types.VARCHAR),
            new Column("name", Types.VARCHAR),
            new Column("value", Types.VARCHAR),
            new Column("capture_time", Types.BIGINT));

    // TODO all of the columns needed for the trace points query are no longer in the same index
    // (number of columns has grown), either update the index or the comment
    //
    // this index includes all of the columns needed for the trace points query so h2 can return
    // result set directly from the index without having to reference the table for each row
    private static final ImmutableList<Index> snapshotIndexes = ImmutableList.of(
            new Index("snapshot_idx", ImmutableList.of("capture_time", "duration", "id", "error")));

    private final DataSource dataSource;
    private final CappedDatabase cappedDatabase;

    SnapshotDao(DataSource dataSource, CappedDatabase cappedDatabase) throws SQLException {
        this.dataSource = dataSource;
        this.cappedDatabase = cappedDatabase;
        upgradeSnapshotTable(dataSource);
        dataSource.syncTable("snapshot", snapshotColumns);
        dataSource.syncIndexes("snapshot", snapshotIndexes);
        dataSource.syncTable("snapshot_attribute", snapshotAttributeColumns);
    }

    @Override
    public void store(final Snapshot snapshot, CharSource spans, @Nullable CharSource profile,
            @Nullable CharSource outlierProfile) {
        String spansId = cappedDatabase.write(spans).getId();
        String profileId = null;
        if (profile != null) {
            profileId = cappedDatabase.write(profile).getId();
        }
        String outlierProfileId = null;
        if (outlierProfile != null) {
            outlierProfileId = cappedDatabase.write(outlierProfile).getId();
        }
        try {
            dataSource.update("merge into snapshot (id, stuck, start_time, capture_time, duration,"
                    + " transaction_type, transaction_name, headline, error, profiled,"
                    + " error_message, user, attributes, trace_metrics, thread_info, gc_infos,"
                    + " spans_id, profile_id, outlier_profile_id) values (?, ?, ?, ?, ?, ?, ?, ?,"
                    + " ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)", snapshot.getId(), snapshot.isStuck(),
                    snapshot.getStartTime(), snapshot.getCaptureTime(), snapshot.getDuration(),
                    snapshot.getTransactionType(), snapshot.getTransactionName(),
                    snapshot.getHeadline(), snapshot.getError() != null, profileId != null,
                    snapshot.getError(), snapshot.getUser(), snapshot.getAttributes(),
                    snapshot.getTraceMetrics(), snapshot.getThreadInfo(), snapshot.getGcInfos(),
                    spansId, profileId, outlierProfileId);
            final ImmutableSetMultimap<String, String> attributesForIndexing =
                    snapshot.getAttributesForIndexing();
            if (attributesForIndexing == null) {
                logger.warn("snapshot attributesForIndex was not provided");
            } else if (!attributesForIndexing.isEmpty()) {
                dataSource.batchUpdate("insert into snapshot_attribute (snapshot_id, name, value,"
                        + " capture_time) values (?, ?, ?, ?)", new BatchAdder() {
                    @Override
                    public void addBatches(PreparedStatement preparedStatement)
                            throws SQLException {
                        // attributesForIndexing is final and null check already performed above
                        checkNotNull(attributesForIndexing);
                        for (Entry<String, String> entry : attributesForIndexing.entries()) {
                            preparedStatement.setString(1, snapshot.getId());
                            preparedStatement.setString(2, entry.getKey());
                            preparedStatement.setString(3, entry.getValue());
                            preparedStatement.setLong(4, snapshot.getCaptureTime());
                            preparedStatement.addBatch();
                        }
                    }
                });
            }
        } catch (SQLException e) {
            logger.error(e.getMessage(), e);
        }
    }

    public QueryResult<TracePoint> readPoints(TracePointQuery query) {
        try {
            ParameterizedSql parameterizedSql = getParameterizedSql(query);
            ImmutableList<TracePoint> points = dataSource.query(parameterizedSql.getSql(),
                    parameterizedSql.getArgs(), new PointRowMapper());
            // one extra record over the limit is fetched above to identify if the limit was hit
            return QueryResult.from(points, query.getLimit());
        } catch (SQLException e) {
            logger.error(e.getMessage(), e);
            return QueryResult.empty();
        }
    }

    @Nullable
    public Snapshot readSnapshot(String traceId) {
        List<Snapshot> snapshots;
        try {
            snapshots = dataSource.query("select id, stuck, start_time, capture_time, duration,"
                    + " transaction_type, transaction_name, headline, error_message, user,"
                    + " attributes, trace_metrics, thread_info, gc_infos, spans_id,"
                    + " profile_id, outlier_profile_id from snapshot where id = ?",
                    ImmutableList.of(traceId), new SnapshotRowMapper());
        } catch (SQLException e) {
            logger.error(e.getMessage(), e);
            return null;
        }
        if (snapshots.isEmpty()) {
            return null;
        }
        if (snapshots.size() > 1) {
            logger.error("multiple records returned for trace id: {}", traceId);
        }
        return snapshots.get(0);
    }

    @Nullable
    public CharSource readSpans(String traceId) throws SQLException {
        return readFromCappedDatabase("spans_id", traceId);
    }

    @Nullable
    public CharSource readProfile(String traceId) throws SQLException {
        return readFromCappedDatabase("profile_id", traceId);
    }

    @Nullable
    public CharSource readOutlierProfile(String traceId) throws SQLException {
        return readFromCappedDatabase("outlier_profile_id", traceId);
    }

    public QueryResult<ErrorAggregate> readErrorAggregates(ErrorAggregateQuery query) {
        try {
            ParameterizedSql parameterizedSql = getParameterizedSql(query);
            ImmutableList<ErrorAggregate> errorAggregates =
                    dataSource.query(parameterizedSql.getSql(), parameterizedSql.getArgs(),
                            new ErrorAggregateRowMapper());
            // one extra record over the limit is fetched above to identify if the limit was hit
            return QueryResult.from(errorAggregates, query.getLimit());
        } catch (SQLException e) {
            logger.error(e.getMessage(), e);
            return QueryResult.empty();
        }
    }

    public void deleteAll() {
        try {
            dataSource.execute("truncate table snapshot_attribute");
            dataSource.execute("truncate table snapshot");
        } catch (SQLException e) {
            logger.error(e.getMessage(), e);
        }
    }

    void deleteBefore(long captureTime) {
        try {
            // delete 100 at a time, which is both faster than deleting all at once, and doesn't
            // lock the single jdbc connection for one large chunk of time
            while (true) {
                int deleted = dataSource.update("delete from snapshot_attribute"
                        + " where capture_time < ? limit 100", captureTime);
                deleted += dataSource.update("delete from snapshot where capture_time < ?"
                        + " limit 100", captureTime);
                if (deleted == 0) {
                    break;
                }
            }
        } catch (SQLException e) {
            logger.error(e.getMessage(), e);
        }
    }

    @Nullable
    private CharSource readFromCappedDatabase(String columnName, String traceId)
            throws SQLException {
        List<String> ids = dataSource.query("select " + columnName + " from snapshot where id = ?",
                ImmutableList.of(traceId), new SingleStringRowMapper());
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
    @Nullable
    public Snapshot getLastSnapshot() throws SQLException {
        List<String> ids = dataSource.query("select id from snapshot order by capture_time desc"
                + " limit 1", ImmutableList.of(), new RowMapper<String>() {
            @Override
            public String mapRow(ResultSet resultSet) throws SQLException {
                // this checkNotNull is safe since id is the primary key and cannot be null
                return checkNotNull(resultSet.getString(1));
            }
        });
        if (ids.isEmpty()) {
            return null;
        }
        return readSnapshot(ids.get(0));
    }

    @OnlyUsedByTests
    public long count() {
        try {
            return dataSource.queryForLong("select count(*) from snapshot");
        } catch (SQLException e) {
            logger.error(e.getMessage(), e);
            return 0;
        }
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
        String sql = "select snapshot.id, snapshot.capture_time, snapshot.duration, snapshot.error"
                + " from snapshot";
        List<Object> args = Lists.newArrayList();
        ParameterizedSql attributeJoin = getSnapshotAttributeJoin(query);
        if (attributeJoin != null) {
            sql += attributeJoin.getSql();
            args.addAll(attributeJoin.getArgs());
        } else {
            sql += " where";
        }
        sql += " snapshot.capture_time > ? and snapshot.capture_time <= ?";
        args.add(query.getFrom());
        args.add(query.getTo());
        long durationLow = query.getDurationLow();
        if (durationLow != 0) {
            sql += " and snapshot.duration >= ?";
            args.add(durationLow);
        }
        Long durationHigh = query.getDurationHigh();
        if (durationHigh != null) {
            sql += " and snapshot.duration <= ?";
            args.add(durationHigh);
        }
        String transactionType = query.getTransactionType();
        if (!Strings.isNullOrEmpty(transactionType)) {
            sql += " and snapshot.transaction_type = ?";
            args.add(transactionType);
        }
        if (query.isErrorOnly()) {
            sql += " and snapshot.error = ?";
            args.add(true);
        }
        if (query.isProfiledOnly()) {
            sql += " and snapshot.profiled = ?";
            args.add(true);
        }
        StringComparator transactionNameComparator = query.getTransactionNameComparator();
        String transactionName = query.getTransactionName();
        if (transactionNameComparator != null && !Strings.isNullOrEmpty(transactionName)) {
            sql += " and upper(snapshot.transaction_name) "
                    + transactionNameComparator.getComparator() + " ?";
            args.add(transactionNameComparator.formatParameter(transactionName
                    .toUpperCase(Locale.ENGLISH)));
        }
        StringComparator headlineComparator = query.getHeadlineComparator();
        String headline = query.getHeadline();
        if (headlineComparator != null && !Strings.isNullOrEmpty(headline)) {
            sql += " and upper(snapshot.headline) " + headlineComparator.getComparator() + " ?";
            args.add(headlineComparator.formatParameter(headline.toUpperCase(Locale.ENGLISH)));
        }
        StringComparator errorComparator = query.getErrorComparator();
        String error = query.getError();
        if (errorComparator != null && !Strings.isNullOrEmpty(error)) {
            sql += " and upper(snapshot.error_message) " + errorComparator.getComparator() + " ?";
            args.add(errorComparator.formatParameter(error.toUpperCase(Locale.ENGLISH)));
        }
        StringComparator userComparator = query.getUserComparator();
        String user = query.getUser();
        if (userComparator != null && !Strings.isNullOrEmpty(user)) {
            sql += " and upper(snapshot.user) " + userComparator.getComparator() + " ?";
            args.add(userComparator.formatParameter(user.toUpperCase(Locale.ENGLISH)));
        }
        sql += " order by snapshot.duration desc limit ?";
        // +1 is to identify if limit was exceeded
        args.add(query.getLimit() + 1);
        return new ParameterizedSql(sql, args);
    }

    @Nullable
    private static ParameterizedSql getSnapshotAttributeJoin(TracePointQuery query) {
        String criteria = "";
        List<Object> criteriaArgs = Lists.newArrayList();
        String attributeName = query.getAttributeName();
        if (!Strings.isNullOrEmpty(attributeName)) {
            criteria += " upper(attr.name) = ? and";
            criteriaArgs.add(attributeName.toUpperCase(Locale.ENGLISH));
        }
        StringComparator attributeValueComparator = query.getAttributeValueComparator();
        String attributeValue = query.getAttributeValue();
        if (attributeValueComparator != null && !Strings.isNullOrEmpty(attributeValue)) {
            criteria += " upper(attr.value) " + attributeValueComparator.getComparator() + " ? and";
            criteriaArgs.add(attributeValueComparator.formatParameter(
                    attributeValue.toUpperCase(Locale.ENGLISH)));
        }
        if (criteria.equals("")) {
            return null;
        } else {
            String sql = ", snapshot_attribute attr where attr.snapshot_id = snapshot.id"
                    + " and attr.capture_time > ? and attr.capture_time <= ? and" + criteria;
            List<Object> args = Lists.newArrayList();
            args.add(query.getFrom());
            args.add(query.getTo());
            args.addAll(criteriaArgs);
            return new ParameterizedSql(sql, args);
        }
    }

    private static ParameterizedSql getParameterizedSql(ErrorAggregateQuery query) {
        String sql = "select transaction_name, error_message, count(*) from snapshot where"
                + " error = ? and capture_time >= ? and capture_time <= ?";
        List<Object> args = Lists.newArrayList();
        args.add(true);
        args.add(query.getFrom());
        args.add(query.getTo());
        for (String include : query.getIncludes()) {
            sql += " and upper(error_message) like ?";
            args.add('%' + include.toUpperCase(Locale.ENGLISH) + '%');
        }
        for (String exclude : query.getExcludes()) {
            sql += " and upper(error_message) not like ?";
            args.add('%' + exclude.toUpperCase(Locale.ENGLISH) + '%');
        }
        sql += " group by transaction_name, error_message ";
        sql += query.getSortDirection().getOrderByClause(query.getSortAttribute());
        sql += " limit ?";
        // +1 is to identify if limit was exceeded
        args.add(query.getLimit() + 1);
        return new ParameterizedSql(sql, args);
    }

    private static void upgradeSnapshotTable(DataSource dataSource) throws SQLException {
        if (!dataSource.tableExists("snapshot")) {
            return;
        }
        for (Column column : dataSource.getColumns("snapshot")) {
            if (column.getName().equals("grouping")) {
                dataSource.execute(
                        "alter table snapshot alter column grouping rename to transaction_name");
                dataSource.execute("alter table snapshot add column headline varchar");
                dataSource.execute("update snapshot set headline = transaction_name");
                break;
            }
            if (column.getName().equals("bucket")) {
                // first grouping was renamed to bucket, then to transaction_name
                dataSource.execute(
                        "alter table snapshot alter column bucket rename to transaction_name");
            }
        }
    }

    private static class ErrorAggregateRowMapper implements RowMapper<ErrorAggregate> {

        @Override
        public ErrorAggregate mapRow(ResultSet resultSet) throws SQLException {
            String transactionName = resultSet.getString(1);
            String error = resultSet.getString(2);
            long count = resultSet.getLong(3);
            return new ErrorAggregate(Strings.nullToEmpty(transactionName),
                    Strings.nullToEmpty(error), count);
        }
    }

    private static class PointRowMapper implements RowMapper<TracePoint> {

        @Override
        public TracePoint mapRow(ResultSet resultSet) throws SQLException {
            String id = resultSet.getString(1);
            // this checkNotNull is safe since id is the primary key and cannot be null
            checkNotNull(id);
            return TracePoint.from(id, resultSet.getLong(2),
                    resultSet.getLong(3), resultSet.getBoolean(4));
        }
    }

    private class SnapshotRowMapper implements RowMapper<Snapshot> {

        // TODO figure out how to get checker framework to pass without suppress warnings
        // seems to have broken in checker 1.8.2 (or maybe just became more strict)
        @Override
        @SuppressWarnings("contracts.precondition.not.satisfied")
        public Snapshot mapRow(ResultSet resultSet) throws SQLException {
            Snapshot.Builder snapshot = Snapshot.builder();
            String id = resultSet.getString(1);
            // this checkNotNull is safe since id is the primary key and cannot be null
            checkNotNull(id);
            snapshot.id(id);
            snapshot.stuck(resultSet.getBoolean(2));
            snapshot.startTime(resultSet.getLong(3));
            snapshot.captureTime(resultSet.getLong(4));
            snapshot.duration(resultSet.getLong(5));
            snapshot.transactionType(Strings.nullToEmpty(resultSet.getString(6)));
            snapshot.transactionName(Strings.nullToEmpty(resultSet.getString(7)));
            snapshot.headline(Strings.nullToEmpty(resultSet.getString(8)));
            snapshot.error(resultSet.getString(9));
            snapshot.user(resultSet.getString(10));
            snapshot.attributes(resultSet.getString(11));
            snapshot.traceMetrics(resultSet.getString(12));
            snapshot.threadInfo(resultSet.getString(13));
            snapshot.gcInfos(resultSet.getString(14));
            snapshot.spansExistence(getExistence(resultSet.getString(15)));
            snapshot.profileExistence(getExistence(resultSet.getString(16)));
            snapshot.outlierProfileExistence(getExistence(resultSet.getString(17)));
            return snapshot.build();
        }

        private Existence getExistence(@Nullable String fileBlockId) {
            if (fileBlockId == null) {
                return Existence.NO;
            }
            FileBlock fileBlock;
            try {
                fileBlock = FileBlock.from(fileBlockId);
            } catch (InvalidBlockIdFormatException e) {
                logger.warn(e.getMessage(), e);
                return Existence.NO;
            }
            if (cappedDatabase.isExpired(fileBlock)) {
                return Existence.EXPIRED;
            } else {
                return Existence.YES;
            }
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
