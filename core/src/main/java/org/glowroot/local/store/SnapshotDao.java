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

import checkers.nullness.quals.Nullable;
import com.google.common.base.Strings;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSetMultimap;
import com.google.common.collect.Lists;
import com.google.common.io.CharSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.glowroot.collector.Snapshot;
import org.glowroot.collector.SnapshotRepository;
import org.glowroot.local.store.DataSource.BatchAdder;
import org.glowroot.local.store.DataSource.RowMapper;
import org.glowroot.local.store.FileBlock.InvalidBlockIdFormatException;
import org.glowroot.local.store.Schemas.Column;
import org.glowroot.local.store.Schemas.Index;
import org.glowroot.local.store.Schemas.PrimaryKeyColumn;
import org.glowroot.local.store.TracePointQuery.ParameterizedSql;
import org.glowroot.local.store.TracePointQuery.StringComparator;
import org.glowroot.markers.OnlyUsedByTests;
import org.glowroot.markers.Singleton;
import org.glowroot.markers.ThreadSafe;

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
            new Column("background", Types.BOOLEAN),
            new Column("error", Types.BOOLEAN), // for searching only
            new Column("fine", Types.BOOLEAN), // for searching only
            new Column("transaction_name", Types.VARCHAR),
            new Column("headline", Types.VARCHAR),
            new Column("error_message", Types.VARCHAR),
            new Column("user", Types.VARCHAR),
            new Column("attributes", Types.VARCHAR), // json data
            new Column("metrics", Types.VARCHAR), // json data
            new Column("jvm_info", Types.VARCHAR), // json data
            new Column("spans", Types.VARCHAR), // capped database block id
            new Column("coarse_merged_stack_tree", Types.VARCHAR), // capped database block id
            new Column("fine_merged_stack_tree", Types.VARCHAR)); // capped database block id

    // capture_time column is used for expiring records without using FK with on delete cascade
    private static final ImmutableList<Column> snapshotAttributeColumns = ImmutableList.of(
            new Column("snapshot_id", Types.VARCHAR),
            new Column("name", Types.VARCHAR),
            new Column("value", Types.VARCHAR),
            new Column("capture_time", Types.BIGINT));

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
    public void store(final Snapshot snapshot) {
        logger.debug("store(): snapshot={}", snapshot);
        String spansBlockId = null;
        CharSource spans = snapshot.getSpans();
        if (spans != null) {
            spansBlockId = cappedDatabase.write(spans).getId();
        }
        String coarseMergedStackTreeBlockId = null;
        CharSource coarseMergedStackTree = snapshot.getCoarseMergedStackTree();
        if (coarseMergedStackTree != null) {
            coarseMergedStackTreeBlockId = cappedDatabase.write(coarseMergedStackTree).getId();
        }
        String fineMergedStackTreeBlockId = null;
        CharSource fineMergedStackTree = snapshot.getFineMergedStackTree();
        if (fineMergedStackTree != null) {
            fineMergedStackTreeBlockId = cappedDatabase.write(fineMergedStackTree).getId();
        }
        try {
            dataSource.update("merge into snapshot (id, stuck, start_time, capture_time, duration,"
                    + " background, error, fine, transaction_name, headline, error_message, user,"
                    + " attributes, metrics, jvm_info, spans, coarse_merged_stack_tree,"
                    + " fine_merged_stack_tree) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?,"
                    + " ?, ?, ?, ?)", snapshot.getId(), snapshot.isStuck(),
                    snapshot.getStartTime(), snapshot.getCaptureTime(), snapshot.getDuration(),
                    snapshot.isBackground(), snapshot.getError() != null,
                    fineMergedStackTreeBlockId != null, snapshot.getTransactionName(),
                    snapshot.getHeadline(), snapshot.getError(), snapshot.getUser(),
                    snapshot.getAttributes(), snapshot.getMetrics(), snapshot.getJvmInfo(),
                    spansBlockId, coarseMergedStackTreeBlockId, fineMergedStackTreeBlockId);
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

    public ImmutableList<TracePoint> readPoints(TracePointQuery query) {
        logger.debug("readPoints(): query={}", query);
        try {
            ParameterizedSql parameterizedSql = query.getParameterizedSql();
            return dataSource.query(parameterizedSql.getSql(), parameterizedSql.getArgs(),
                    new PointRowMapper());
        } catch (SQLException e) {
            logger.error(e.getMessage(), e);
            return ImmutableList.of();
        }
    }

    @Nullable
    public Snapshot readSnapshot(String id) {
        logger.debug("readSnapshot(): id={}", id);
        List<PartiallyHydratedTrace> partiallyHydratedTraces;
        try {
            partiallyHydratedTraces = dataSource.query("select id, stuck, start_time,"
                    + " capture_time, duration, background, transaction_name, headline,"
                    + " error_message, user, attributes, metrics, jvm_info, spans,"
                    + " coarse_merged_stack_tree, fine_merged_stack_tree from snapshot"
                    + " where id = ?", ImmutableList.of(id),
                    new TraceRowMapper());
        } catch (SQLException e) {
            logger.error(e.getMessage(), e);
            return null;
        }
        if (partiallyHydratedTraces.isEmpty()) {
            return null;
        } else if (partiallyHydratedTraces.size() > 1) {
            logger.error("multiple records returned for id: {}", id);
        }
        // read from the capped database outside of jdbc connection
        return partiallyHydratedTraces.get(0).fullyHydrate();
    }

    @Nullable
    public Snapshot readSnapshotWithoutDetail(String id) {
        logger.debug("readSnapshot(): id={}", id);
        List<Snapshot> snapshots;
        try {
            snapshots = dataSource.query("select id, stuck, start_time, capture_time, duration,"
                    + " background, transaction_name, headline, error_message, user, attributes,"
                    + " metrics, jvm_info from snapshot where id = ?", ImmutableList.of(id),
                    new SnapshotRowMapper());
        } catch (SQLException e) {
            logger.error(e.getMessage(), e);
            return null;
        }
        if (snapshots.isEmpty()) {
            return null;
        } else if (snapshots.size() > 1) {
            logger.error("multiple records returned for id: {}", id);
        }
        return snapshots.get(0);
    }

    public List<ErrorAggregate> readErrorAggregates(long captureTimeFrom, long captureTimeTo,
            @Nullable StringComparator errorComparator, @Nullable String error, int limit) {
        String sql = "select transaction_name, error_message, count(*) from snapshot where"
                + " error = ? and capture_time >= ? and capture_time <= ?";
        List<Object> args = Lists.newArrayList();
        args.add(true);
        args.add(captureTimeFrom);
        args.add(captureTimeTo);
        if (errorComparator != null && !Strings.isNullOrEmpty(error)) {
            sql += " and upper(error_message) " + errorComparator.getComparator() + " ?";
            args.add(errorComparator.formatParameter(error.toUpperCase(Locale.ENGLISH)));
        }
        sql += " group by transaction_name, error_message order by count(*) desc limit ?";
        args.add(limit);
        try {
            return dataSource.query(sql, args, new ErrorAggregateRowMapper());
        } catch (SQLException e) {
            logger.error(e.getMessage(), e);
            return ImmutableList.of();
        }
    }

    public void deleteAllSnapshots() {
        logger.debug("deleteAllSnapshots()");
        try {
            dataSource.execute("truncate table snapshot_attribute");
            dataSource.execute("truncate table snapshot");
        } catch (SQLException e) {
            logger.error(e.getMessage(), e);
        }
    }

    void deleteSnapshotsBefore(long captureTime) {
        logger.debug("deleteSnapshotsBefore(): captureTime={}", captureTime);
        try {
            dataSource.update("delete from snapshot_attribute where capture_time < ?", captureTime);
            dataSource.update("delete from snapshot where capture_time < ?", captureTime);
        } catch (SQLException e) {
            logger.error(e.getMessage(), e);
        }
    }

    @OnlyUsedByTests
    @Nullable
    public Snapshot getLastSnapshot(boolean summary) throws SQLException {
        List<String> ids = dataSource.query("select id from snapshot order by capture_time desc"
                + " limit 1", ImmutableList.of(), new RowMapper<String>() {
            @Override
            public String mapRow(ResultSet resultSet) throws SQLException {
                return resultSet.getString(1);
            }
        });
        if (ids.isEmpty()) {
            return null;
        }
        if (summary) {
            return readSnapshotWithoutDetail(ids.get(0));
        } else {
            return readSnapshot(ids.get(0));
        }
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

    private static Snapshot.Builder createBuilder(ResultSet resultSet) throws SQLException {
        return Snapshot.builder()
                .id(resultSet.getString(1))
                .stuck(resultSet.getBoolean(2))
                .startTime(resultSet.getLong(3))
                .captureTime(resultSet.getLong(4))
                .duration(resultSet.getLong(5))
                .background(resultSet.getBoolean(6))
                .transactionName(resultSet.getString(7))
                .headline(resultSet.getString(8))
                .error(resultSet.getString(9))
                .user(resultSet.getString(10))
                .attributes(resultSet.getString(11))
                .metrics(resultSet.getString(12))
                .jvmInfo(resultSet.getString(13));
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

    @ThreadSafe
    private class TraceRowMapper implements RowMapper<PartiallyHydratedTrace> {

        @Override
        public PartiallyHydratedTrace mapRow(ResultSet resultSet) throws SQLException {
            Snapshot.Builder builder = createBuilder(resultSet);
            // wait and read from the capped database outside of the jdbc connection
            String spansFileBlockId = resultSet.getString(14);
            String coarseMergedStackTreeFileBlockId = resultSet.getString(15);
            String fineMergedStackTreeFileBlockId = resultSet.getString(16);
            return new PartiallyHydratedTrace(builder, spansFileBlockId,
                    coarseMergedStackTreeFileBlockId, fineMergedStackTreeFileBlockId);
        }
    }

    @ThreadSafe
    private class ErrorAggregateRowMapper implements RowMapper<ErrorAggregate> {

        @Override
        public ErrorAggregate mapRow(ResultSet resultSet) throws SQLException {
            String transactionName = resultSet.getString(1);
            String error = resultSet.getString(2);
            long count = resultSet.getLong(3);
            return new ErrorAggregate(transactionName, error, count);
        }
    }

    private class PartiallyHydratedTrace {

        private final Snapshot.Builder builder;
        // file block ids are stored temporarily while reading the trace snapshot from the database
        // so that reading from the capped database can occur outside of the jdbc connection
        @Nullable
        private final String spansFileBlockId;
        @Nullable
        private final String coarseMergedStackTreeFileBlockId;
        @Nullable
        private final String fineMergedStackTreeFileBlockId;

        private PartiallyHydratedTrace(Snapshot.Builder builder,
                @Nullable String spansFileBlockId,
                @Nullable String coarseMergedStackTreeFileBlockId,
                @Nullable String fineMergedStackTreeFileBlockId) {

            this.builder = builder;
            this.spansFileBlockId = spansFileBlockId;
            this.coarseMergedStackTreeFileBlockId = coarseMergedStackTreeFileBlockId;
            this.fineMergedStackTreeFileBlockId = fineMergedStackTreeFileBlockId;
        }

        private Snapshot fullyHydrate() {
            if (spansFileBlockId != null) {
                FileBlock block;
                try {
                    block = FileBlock.from(spansFileBlockId);
                    builder.spans(cappedDatabase.read(block, "{\"overwritten\":true}"));
                } catch (InvalidBlockIdFormatException e) {
                    logger.warn(e.getMessage(), e);
                }
            }
            if (coarseMergedStackTreeFileBlockId != null) {
                FileBlock block;
                try {
                    block = FileBlock.from(coarseMergedStackTreeFileBlockId);
                    builder.coarseMergedStackTree(
                            cappedDatabase.read(block, "{\"overwritten\":true}"));
                } catch (InvalidBlockIdFormatException e) {
                    logger.warn(e.getMessage(), e);
                }
            }
            if (fineMergedStackTreeFileBlockId != null) {
                FileBlock block;
                try {
                    block = FileBlock.from(fineMergedStackTreeFileBlockId);
                    builder.fineMergedStackTree(
                            cappedDatabase.read(block, "{\"overwritten\":true}"));
                } catch (InvalidBlockIdFormatException e) {
                    logger.warn(e.getMessage(), e);
                }
            }
            return builder.build();
        }
    }

    @ThreadSafe
    private static class PointRowMapper implements RowMapper<TracePoint> {

        @Override
        public TracePoint mapRow(ResultSet resultSet) throws SQLException {
            return TracePoint.from(resultSet.getString(1), resultSet.getLong(2),
                    resultSet.getLong(3), resultSet.getBoolean(4));
        }
    }

    @ThreadSafe
    private static class SnapshotRowMapper implements RowMapper<Snapshot> {

        @Override
        public Snapshot mapRow(ResultSet resultSet) throws SQLException {
            return createBuilder(resultSet).build();
        }
    }
}
