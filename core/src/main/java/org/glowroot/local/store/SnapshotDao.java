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
import java.util.Map.Entry;

import javax.annotation.Nullable;

import com.google.common.base.Strings;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSetMultimap;
import com.google.common.io.CharSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.glowroot.collector.Snapshot;
import org.glowroot.collector.Snapshot.Existence;
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
            new Column("spans_id", Types.VARCHAR), // capped database id
            new Column("coarse_profile_id", Types.VARCHAR), // capped database id
            new Column("fine_profile_id", Types.VARCHAR)); // capped database id

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
    public void store(final Snapshot snapshot, CharSource spans,
            @Nullable CharSource coarseProfile, @Nullable CharSource fineProfile) {
        logger.debug("store(): snapshot={}", snapshot);
        String spansId = cappedDatabase.write(spans).getId();
        String coarseProfileId = null;
        if (coarseProfile != null) {
            coarseProfileId = cappedDatabase.write(coarseProfile).getId();
        }
        String fineProfileId = null;
        if (fineProfile != null) {
            fineProfileId = cappedDatabase.write(fineProfile).getId();
        }
        try {
            dataSource.update("merge into snapshot (id, stuck, start_time, capture_time, duration,"
                    + " background, error, fine, transaction_name, headline, error_message, user,"
                    + " attributes, metrics, jvm_info, spans_id, coarse_profile_id,"
                    + " fine_profile_id) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?,"
                    + " ?, ?)", snapshot.getId(), snapshot.isStuck(), snapshot.getStartTime(),
                    snapshot.getCaptureTime(), snapshot.getDuration(), snapshot.isBackground(),
                    snapshot.getError() != null, fineProfileId != null,
                    snapshot.getTransactionName(), snapshot.getHeadline(), snapshot.getError(),
                    snapshot.getUser(), snapshot.getAttributes(), snapshot.getMetrics(),
                    snapshot.getJvmInfo(), spansId, coarseProfileId, fineProfileId);
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
    public Snapshot readSnapshot(String traceId) {
        logger.debug("readSnapshot(): id={}", traceId);
        List<Snapshot> snapshots;
        try {
            snapshots = dataSource.query("select id, stuck, start_time, capture_time, duration,"
                    + " background, transaction_name, headline, error_message, user, attributes,"
                    + " metrics, jvm_info, spans_id, coarse_profile_id, fine_profile_id from "
                    + " snapshot where id = ?", ImmutableList.of(traceId), new SnapshotRowMapper());
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
    public CharSource readCoarseProfile(String traceId) throws SQLException {
        return readFromCappedDatabase("coarse_profile_id", traceId);
    }

    @Nullable
    public CharSource readFineProfile(String traceId) throws SQLException {
        return readFromCappedDatabase("fine_profile_id", traceId);
    }

    public List<ErrorAggregate> readErrorAggregates(ErrorAggregateQuery query) {
        logger.debug("readPoints(): query={}", query);
        try {
            ParameterizedSql parameterizedSql = query.getParameterizedSql();
            return dataSource.query(parameterizedSql.getSql(), parameterizedSql.getArgs(),
                    new ErrorAggregateRowMapper());
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
                return resultSet.getString(1);
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

    private class ErrorAggregateRowMapper implements RowMapper<ErrorAggregate> {

        @Override
        public ErrorAggregate mapRow(ResultSet resultSet) throws SQLException {
            String transactionName = resultSet.getString(1);
            String error = resultSet.getString(2);
            long count = resultSet.getLong(3);
            return new ErrorAggregate(transactionName, error, count);
        }
    }

    private static class PointRowMapper implements RowMapper<TracePoint> {

        @Override
        public TracePoint mapRow(ResultSet resultSet) throws SQLException {
            return TracePoint.from(resultSet.getString(1), resultSet.getLong(2),
                    resultSet.getLong(3), resultSet.getBoolean(4));
        }
    }

    private class SnapshotRowMapper implements RowMapper<Snapshot> {

        @Override
        public Snapshot mapRow(ResultSet resultSet) throws SQLException {
            Snapshot.Builder snapshot = Snapshot.builder();
            snapshot.id(resultSet.getString(1));
            snapshot.stuck(resultSet.getBoolean(2));
            snapshot.startTime(resultSet.getLong(3));
            snapshot.captureTime(resultSet.getLong(4));
            snapshot.duration(resultSet.getLong(5));
            snapshot.background(resultSet.getBoolean(6));
            snapshot.transactionName(resultSet.getString(7));
            snapshot.headline(resultSet.getString(8));
            snapshot.error(resultSet.getString(9));
            snapshot.user(resultSet.getString(10));
            snapshot.attributes(resultSet.getString(11));
            snapshot.metrics(resultSet.getString(12));
            snapshot.jvmInfo(resultSet.getString(13));
            snapshot.spansExistence(getExistence(resultSet.getString(14)));
            snapshot.coarseProfileExistence(getExistence(resultSet.getString(15)));
            snapshot.fineProfileExistence(getExistence(resultSet.getString(16)));
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
