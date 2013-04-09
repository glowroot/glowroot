/**
 * Copyright 2011-2013 the original author or authors.
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

package io.informant.local.store;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.List;
import java.util.Locale;

import checkers.igj.quals.ReadOnly;
import checkers.nullness.quals.Nullable;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;
import com.google.common.io.CharSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.informant.common.Clock;
import io.informant.local.store.DataSource.RowMapper;
import io.informant.local.store.FileBlock.InvalidBlockIdFormatException;
import io.informant.local.store.Schemas.Column;
import io.informant.local.store.Schemas.Index;
import io.informant.local.store.Schemas.PrimaryKeyColumn;
import io.informant.markers.OnlyUsedByTests;
import io.informant.markers.Singleton;
import io.informant.markers.ThreadSafe;
import io.informant.snapshot.Snapshot;
import io.informant.snapshot.SnapshotSink;

/**
 * Data access object for storing and reading trace snapshot data from the embedded H2 database.
 * 
 * @author Trask Stalnaker
 * @since 0.5
 */
@Singleton
public class SnapshotDao implements SnapshotSink {

    private static final Logger logger = LoggerFactory.getLogger(SnapshotDao.class);

    private static final ImmutableList<Column> columns = ImmutableList.of(
            new PrimaryKeyColumn("id", Types.VARCHAR),
            new Column("captured_at", Types.BIGINT), // for searching only
            new Column("start_at", Types.BIGINT),
            new Column("duration", Types.BIGINT),
            new Column("stuck", Types.BOOLEAN),
            new Column("completed", Types.BOOLEAN),
            new Column("background", Types.BOOLEAN),
            new Column("error", Types.BOOLEAN),
            new Column("fine", Types.BOOLEAN), // for searching only
            new Column("headline", Types.VARCHAR),
            new Column("attributes", Types.VARCHAR), // json data
            new Column("user_id", Types.VARCHAR),
            new Column("error_text", Types.VARCHAR),
            new Column("error_detail", Types.VARCHAR), // json data
            new Column("exception", Types.VARCHAR), // json data
            new Column("metrics", Types.VARCHAR), // json data
            new Column("spans", Types.VARCHAR), // rolling file block id
            new Column("coarse_merged_stack_tree", Types.VARCHAR), // rolling file block id
            new Column("fine_merged_stack_tree", Types.VARCHAR)); // rolling file block id

    // this index includes all of the columns needed for the trace points query so h2 can return
    // result set directly from the index without having to reference the table for each row
    private static final ImmutableList<Index> indexes = ImmutableList.of(new Index("snapshot_idx",
            ImmutableList.of("captured_at", "duration", "id", "completed",
                    "error")));

    private final DataSource dataSource;
    private final RollingFile rollingFile;
    private final Clock clock;

    SnapshotDao(DataSource dataSource, RollingFile rollingFile, Clock clock)
            throws SQLException {
        this.dataSource = dataSource;
        this.rollingFile = rollingFile;
        this.clock = clock;
        upgradeSnapshotTable(dataSource);
        dataSource.syncTable("snapshot", columns);
        dataSource.syncIndexes("snapshot", indexes);
    }

    public void store(Snapshot snapshot) {
        logger.debug("storeSnapshot(): snapshot={}", snapshot);
        // capture time before writing to rolling file
        long capturedAt = clock.currentTimeMillis();
        String spansBlockId = null;
        CharSource spans = snapshot.getSpans();
        if (spans != null) {
            spansBlockId = rollingFile.write(spans).getId();
        }
        String coarseMergedStackTreeBlockId = null;
        CharSource coarseMergedStackTree = snapshot.getCoarseMergedStackTree();
        if (coarseMergedStackTree != null) {
            coarseMergedStackTreeBlockId = rollingFile.write(coarseMergedStackTree).getId();
        }
        String fineMergedStackTreeBlockId = null;
        CharSource fineMergedStackTree = snapshot.getFineMergedStackTree();
        if (fineMergedStackTree != null) {
            fineMergedStackTreeBlockId = rollingFile.write(fineMergedStackTree).getId();
        }
        try {
            dataSource.update("merge into snapshot (id, captured_at, start_at, duration, stuck,"
                    + " completed, background, error, fine, headline, attributes, user_id,"
                    + " error_text, error_detail, exception, metrics, spans,"
                    + " coarse_merged_stack_tree, fine_merged_stack_tree) values (?, ?, ?, ?, ?,"
                    + "?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)", snapshot.getId(), capturedAt,
                    snapshot.getStart(), snapshot.getDuration(), snapshot.isStuck(),
                    snapshot.isCompleted(), snapshot.isBackground(),
                    snapshot.getErrorText() != null, fineMergedStackTreeBlockId != null,
                    snapshot.getHeadline(), snapshot.getAttributes(), snapshot.getUserId(),
                    snapshot.getErrorText(), snapshot.getErrorDetail(), snapshot.getException(),
                    snapshot.getMetrics(), spansBlockId, coarseMergedStackTreeBlockId,
                    fineMergedStackTreeBlockId);
        } catch (SQLException e) {
            logger.error(e.getMessage(), e);
        }
    }

    @ReadOnly
    public List<TracePoint> readPoints(long capturedFrom, long capturedTo,
            long durationLow, long durationHigh, @Nullable Boolean background,
            boolean errorOnly, boolean fineOnly, @Nullable StringComparator headlineComparator,
            @Nullable String headline, @Nullable StringComparator userIdComparator,
            @Nullable String userId, int limit) {

        if (logger.isDebugEnabled()) {
            logger.debug("readPoints(): capturedFrom={}, capturedTo={}, durationLow={},"
                    + " durationHigh={}, background={}, errorOnly={}, fineOnly={},"
                    + " headlineComparator={}, headline={}, userIdComparator={}, userId={}",
                    capturedFrom, capturedTo, durationLow, durationHigh, background, errorOnly,
                    fineOnly, headlineComparator, headline, userIdComparator, userId);
        }
        try {
            // all of these columns should be in the same index so h2 can return result set directly
            // from the index without having to reference the table for each row
            String sql = "select id, captured_at, duration, completed, error from snapshot where"
                    + " captured_at >= ? and captured_at <= ?";
            List<Object> args = Lists.newArrayList();
            args.add(capturedFrom);
            args.add(capturedTo);
            if (durationLow != 0) {
                sql += " and duration >= ?";
                args.add(durationLow);
            }
            if (durationHigh != Long.MAX_VALUE) {
                sql += " and duration <= ?";
                args.add(durationHigh);
            }
            if (background != null) {
                sql += " and background = ?";
                args.add(background);
            }
            if (errorOnly) {
                sql += " and error = ?";
                args.add(true);
            }
            if (fineOnly) {
                sql += " and fine = ?";
                args.add(true);
            }
            if (headlineComparator != null && headline != null) {
                sql += " and upper(headline) " + headlineComparator.getComparator() + " ?";
                args.add(headlineComparator.formatParameter(headline.toUpperCase(Locale.ENGLISH)));
            }
            if (userIdComparator != null && userId != null) {
                sql += " and upper(user_id) " + userIdComparator.getComparator() + " ?";
                args.add(userIdComparator.formatParameter(userId.toUpperCase(Locale.ENGLISH)));
            }
            sql += " order by duration desc limit ?";
            args.add(limit);
            return dataSource.query(sql, args, new PointRowMapper());
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
            partiallyHydratedTraces = dataSource.query("select id, start_at, duration, stuck,"
                    + " completed, background, headline, attributes, user_id, error_text,"
                    + " error_detail, exception, metrics, spans, coarse_merged_stack_tree,"
                    + " fine_merged_stack_tree from snapshot where id = ?", ImmutableList.of(id),
                    new TraceRowMapper());
        } catch (SQLException e) {
            logger.error(e.getMessage(), e);
            return null;
        }
        if (partiallyHydratedTraces.isEmpty()) {
            return null;
        } else if (partiallyHydratedTraces.size() > 1) {
            logger.error("multiple records returned for id '{}'", id);
        }
        // read from rolling file outside of jdbc connection
        return partiallyHydratedTraces.get(0).fullyHydrate();
    }

    @Nullable
    public Snapshot readSnapshotWithoutDetail(String id) {
        logger.debug("readSnapshot(): id={}", id);
        List<Snapshot> snapshots;
        try {
            snapshots = dataSource.query("select id, start_at, duration, stuck, completed,"
                    + " background, headline, attributes, user_id, error_text, error_detail,"
                    + " exception, metrics from snapshot where id = ?", ImmutableList.of(id),
                    new SnapshotRowMapper());
        } catch (SQLException e) {
            logger.error(e.getMessage(), e);
            return null;
        }
        if (snapshots.isEmpty()) {
            return null;
        } else if (snapshots.size() > 1) {
            logger.error("multiple records returned for id '{}'", id);
        }
        return snapshots.get(0);
    }

    public void deleteAllSnapshots() {
        logger.debug("deleteAllSnapshots()");
        try {
            dataSource.execute("truncate table snapshot");
        } catch (SQLException e) {
            logger.error(e.getMessage(), e);
        }
    }

    void deleteSnapshotsBefore(long capturedAt) {
        logger.debug("deleteSnapshotsBefore(): capturedAt={}", capturedAt);
        try {
            dataSource.update("delete from snapshot where captured_at <= ?", capturedAt);
        } catch (SQLException e) {
            logger.error(e.getMessage(), e);
        }
    }

    @OnlyUsedByTests
    @Nullable
    public Snapshot getLastSnapshot(boolean summary) throws SQLException {
        List<String> ids = dataSource.query(
                "select id from snapshot order by captured_at desc limit 1", ImmutableList.of(),
                new RowMapper<String>() {
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
                .start(resultSet.getLong(2))
                .duration(resultSet.getLong(3))
                .stuck(resultSet.getBoolean(4))
                .completed(resultSet.getBoolean(5))
                .background(resultSet.getBoolean(6))
                .headline(resultSet.getString(7))
                .attributes(resultSet.getString(8))
                .userId(resultSet.getString(9))
                .errorText(resultSet.getString(10))
                .errorDetail(resultSet.getString(11))
                .exception(resultSet.getString(12))
                .metrics(resultSet.getString(13));
    }

    private static void upgradeSnapshotTable(DataSource dataSource) throws SQLException {
        if (!dataSource.tableExists("snapshot")) {
            return;
        }
        // 'description' column renamed to 'headline'
        for (Column column : dataSource.getColumns("snapshot")) {
            if (column.getName().equals("description")) {
                dataSource.execute("alter table snapshot alter column description rename to"
                        + " headline");
                break;
            }
        }
    }

    @ThreadSafe
    private class TraceRowMapper implements RowMapper<PartiallyHydratedTrace> {

        public PartiallyHydratedTrace mapRow(ResultSet resultSet) throws SQLException {
            Snapshot.Builder builder = createBuilder(resultSet);
            // wait and read from rolling file outside of the jdbc connection
            String spansFileBlockId = resultSet.getString(14);
            String coarseMergedStackTreeFileBlockId = resultSet.getString(15);
            String fineMergedStackTreeFileBlockId = resultSet.getString(16);
            return new PartiallyHydratedTrace(builder, spansFileBlockId,
                    coarseMergedStackTreeFileBlockId, fineMergedStackTreeFileBlockId);
        }
    }

    private class PartiallyHydratedTrace {

        private final Snapshot.Builder builder;
        // file block ids are stored temporarily while reading the trace snapshot from the
        // database so that reading from the rolling file can occur outside of the jdbc connection
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
                    builder.spans(rollingFile.read(block, "{\"rolledOver\":true}"));
                } catch (InvalidBlockIdFormatException e) {
                    logger.warn(e.getMessage(), e);
                }
            }
            if (coarseMergedStackTreeFileBlockId != null) {
                FileBlock block;
                try {
                    block = FileBlock.from(coarseMergedStackTreeFileBlockId);
                    builder.coarseMergedStackTree(rollingFile.read(block, "{\"rolledOver\":true}"));
                } catch (InvalidBlockIdFormatException e) {
                    logger.warn(e.getMessage(), e);
                }
            }
            if (fineMergedStackTreeFileBlockId != null) {
                FileBlock block;
                try {
                    block = FileBlock.from(fineMergedStackTreeFileBlockId);
                    builder.fineMergedStackTree(rollingFile.read(block, "{\"rolledOver\":true}"));
                } catch (InvalidBlockIdFormatException e) {
                    logger.warn(e.getMessage(), e);
                }
            }
            return builder.build();
        }
    }

    public static enum StringComparator {

        BEGINS("like", "%s%%"),
        EQUALS("=", "%s"),
        ENDS("like", "%%%s"),
        CONTAINS("like", "%%%s%%");

        private final String comparator;
        private final String parameterFormat;

        private StringComparator(String comparator, String parameterTemplate) {
            this.comparator = comparator;
            this.parameterFormat = parameterTemplate;
        }

        public String formatParameter(String parameter) {
            return String.format(parameterFormat, parameter);
        }

        public String getComparator() {
            return comparator;
        }
    }

    @ThreadSafe
    private static class PointRowMapper implements RowMapper<TracePoint> {

        public TracePoint mapRow(ResultSet resultSet) throws SQLException {
            return TracePoint.from(resultSet.getString(1), resultSet.getLong(2),
                    resultSet.getLong(3), resultSet.getBoolean(4), resultSet.getBoolean(5));
        }
    }

    @ThreadSafe
    private static class SnapshotRowMapper implements RowMapper<Snapshot> {

        public Snapshot mapRow(ResultSet resultSet) throws SQLException {
            return createBuilder(resultSet).build();
        }
    }
}
