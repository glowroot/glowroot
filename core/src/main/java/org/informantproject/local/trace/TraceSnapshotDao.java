/**
 * Copyright 2011-2012 the original author or authors.
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
package org.informantproject.local.trace;

import java.io.IOException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.List;

import javax.annotation.Nullable;
import javax.annotation.concurrent.ThreadSafe;

import org.informantproject.core.util.ByteStream;
import org.informantproject.core.util.Clock;
import org.informantproject.core.util.DataSource;
import org.informantproject.core.util.DataSource.Column;
import org.informantproject.core.util.DataSource.Index;
import org.informantproject.core.util.DataSource.PrimaryKeyColumn;
import org.informantproject.core.util.DataSource.RowMapper;
import org.informantproject.core.util.FileBlock;
import org.informantproject.core.util.FileBlock.InvalidBlockId;
import org.informantproject.core.util.RollingFile;
import org.informantproject.core.util.UnitTests.OnlyUsedByTests;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;
import com.google.inject.Inject;
import com.google.inject.Singleton;

/**
 * Data access object for storing and reading trace data from the embedded H2 database.
 * 
 * @author Trask Stalnaker
 * @since 0.5
 */
@Singleton
public class TraceSnapshotDao {

    private static final Logger logger = LoggerFactory.getLogger(TraceSnapshotDao.class);

    private static final ImmutableList<Column> columns = ImmutableList.of(
            new PrimaryKeyColumn("id", Types.VARCHAR),
            new Column("captured_at", Types.BIGINT), // for searching only
            new Column("start_at", Types.BIGINT),
            new Column("duration", Types.BIGINT),
            new Column("stuck", Types.BOOLEAN),
            new Column("completed", Types.BOOLEAN),
            new Column("error", Types.BOOLEAN), // for searching only
            new Column("fine", Types.BOOLEAN), // for searching only
            new Column("description", Types.VARCHAR),
            new Column("attributes", Types.VARCHAR), // json data
            new Column("user_id", Types.VARCHAR),
            new Column("error_text", Types.VARCHAR),
            new Column("error_detail", Types.VARCHAR), // json data
            new Column("error_stack_trace", Types.VARCHAR), // json data
            new Column("metrics", Types.VARCHAR), // json data
            new Column("spans", Types.VARCHAR), // rolling file block id
            new Column("coarse_merged_stack_tree", Types.VARCHAR), // rolling file block id
            new Column("fine_merged_stack_tree", Types.VARCHAR)); // rolling file block id

    private static final ImmutableList<Index> indexes = ImmutableList.of(new Index("trace_idx",
            "captured_at", "duration"));

    private final DataSource dataSource;
    private final RollingFile rollingFile;
    private final Clock clock;

    private final boolean valid;

    @Inject
    TraceSnapshotDao(DataSource dataSource, RollingFile rollingFile, Clock clock) {
        this.dataSource = dataSource;
        this.rollingFile = rollingFile;
        this.clock = clock;
        boolean errorOnInit = false;
        try {
            if (!dataSource.tableExists("trace")) {
                dataSource.createTable("trace", columns);
                dataSource.createIndexes("trace", indexes);
            } else if (dataSource.tableNeedsUpgrade("trace", columns)) {
                logger.warn("upgrading trace table schema, which unfortunately at this point just"
                        + " means dropping and re-create the table (losing existing data)");
                dataSource.execute("drop table trace");
                dataSource.createTable("trace", columns);
                dataSource.createIndexes("trace", indexes);
                logger.warn("the schema for the trace table was outdated so it was dropped"
                        + " and re-created, existing trace data was lost");
            }
        } catch (SQLException e) {
            errorOnInit = true;
            logger.error(e.getMessage(), e);
        }
        this.valid = !errorOnInit;
    }

    void storeSnapshot(TraceSnapshot snapshot) {
        logger.debug("storeSnapshot(): snapshot={}", snapshot);
        if (!valid) {
            return;
        }
        // capture time before writing to rolling file
        long capturedAt = clock.currentTimeMillis();
        String spansBlockId = null;
        ByteStream spans = snapshot.getSpans();
        if (spans != null) {
            try {
                spansBlockId = rollingFile.write(spans).getId();
            } catch (IOException e) {
                logger.error(e.getMessage(), e);
            }
        }
        String coarseMergedStackTreeBlockId = null;
        ByteStream coarseMergedStackTree = snapshot.getCoarseMergedStackTree();
        if (coarseMergedStackTree != null) {
            try {
                coarseMergedStackTreeBlockId = rollingFile.write(coarseMergedStackTree).getId();
            } catch (IOException e) {
                logger.error(e.getMessage(), e);
            }
        }
        String fineMergedStackTreeBlockId = null;
        ByteStream fineMergedStackTree = snapshot.getFineMergedStackTree();
        if (fineMergedStackTree != null) {
            try {
                fineMergedStackTreeBlockId = rollingFile.write(fineMergedStackTree).getId();
            } catch (IOException e) {
                logger.error(e.getMessage(), e);
            }
        }
        try {
            dataSource.update("merge into trace (id, captured_at, start_at, duration, stuck,"
                    + " completed, error, fine, description, attributes, user_id, error_text,"
                    + " error_detail, error_stack_trace, metrics, spans, coarse_merged_stack_tree,"
                    + " fine_merged_stack_tree) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?,"
                    + " ?, ?, ?, ?)", snapshot.getId(), capturedAt, snapshot.getStartAt(),
                    snapshot.getDuration(), snapshot.isStuck(), snapshot.isCompleted(),
                    snapshot.getErrorText() != null, fineMergedStackTreeBlockId != null,
                    snapshot.getDescription(), snapshot.getAttributes(),
                    snapshot.getUserId(), snapshot.getErrorText(), snapshot.getErrorDetail(),
                    snapshot.getErrorStackTrace(), snapshot.getMetrics(), spansBlockId,
                    coarseMergedStackTreeBlockId, fineMergedStackTreeBlockId);
        } catch (SQLException e) {
            logger.error(e.getMessage(), e);
        }
    }

    public List<TraceSnapshotSummary> readSummaries(long capturedFrom, long capturedTo,
            long durationLow, long durationHigh, @Nullable StringComparator userIdComparator,
            @Nullable String userId, boolean error, boolean fine) {

        logger.debug("readSummaries(): capturedFrom={}, capturedTo={}, durationLow={},"
                + " durationHigh={}, userIdComparator={}, userId={}, error={}, fine={}",
                new Object[] { capturedFrom, capturedTo, durationLow, durationHigh,
                        userIdComparator, userId, error, fine });
        if (!valid) {
            return ImmutableList.of();
        }
        try {
            String sql = "select id, captured_at, duration, completed from trace where"
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
            if (userIdComparator != null && userId != null) {
                sql += " and user_id " + userIdComparator.getComparator() + " ?";
                args.add(userIdComparator.formatParameter(userId));
            }
            if (error) {
                sql += " and error = ?";
                args.add(true);
            }
            if (fine) {
                sql += " and fine = ?";
                args.add(true);
            }
            return dataSource.query(sql, args.toArray(), new SummaryRowMapper());
        } catch (SQLException e) {
            logger.error(e.getMessage(), e);
            return ImmutableList.of();
        }
    }

    @Nullable
    public TraceSnapshot readSnapshot(String id) {
        logger.debug("readSnapshot(): id={}", id);
        if (!valid) {
            return null;
        }
        List<PartiallyHydratedTrace> partiallyHydratedTraces;
        try {
            partiallyHydratedTraces = dataSource.query("select id, start_at, duration, stuck,"
                    + " completed, description, attributes, user_id, error_text, error_detail,"
                    + " error_stack_trace, metrics, spans, coarse_merged_stack_tree,"
                    + " fine_merged_stack_tree from trace where id = ?", new Object[] { id },
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
    public TraceSnapshot readSnapshotWithoutDetail(String id) {
        logger.debug("readSnapshot(): id={}", id);
        if (!valid) {
            return null;
        }
        List<TraceSnapshot> snapshots;
        try {
            snapshots = dataSource.query("select id, start_at, duration, stuck, completed,"
                    + " description, attributes, user_id, error_text, error_detail,"
                    + " error_stack_trace, metrics from trace where id = ?", new Object[] { id },
                    new TraceSummaryRowMapper());
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

    public int deleteSnapshots(final long capturedFrom, final long capturedTo) {
        logger.debug("deleteSnapshots(): capturedFrom={}, capturedTo={}", capturedFrom,
                capturedTo);
        if (!valid) {
            return 0;
        }
        try {
            return dataSource.update("delete from trace where captured_at >= ? and captured_at"
                    + " <= ?", capturedFrom, capturedTo);
        } catch (SQLException e) {
            logger.error(e.getMessage(), e);
            return 0;
        }
    }

    public void deleteAllSnapshots() {
        logger.debug("deleteAllSnapshots()");
        if (!valid) {
            return;
        }
        try {
            dataSource.execute("truncate table trace");
        } catch (SQLException e) {
            logger.error(e.getMessage(), e);
        }
    }

    @OnlyUsedByTests
    long count() {
        if (!valid) {
            return 0;
        }
        try {
            return dataSource.queryForLong("select count(*) from trace");
        } catch (SQLException e) {
            logger.error(e.getMessage(), e);
            return 0;
        }
    }

    private static TraceSnapshot.Builder createBuilder(ResultSet resultSet) throws SQLException {
        return TraceSnapshot.builder()
                .id(resultSet.getString(1))
                .startAt(resultSet.getLong(2))
                .duration(resultSet.getLong(3))
                .stuck(resultSet.getBoolean(4))
                .completed(resultSet.getBoolean(5))
                .description(resultSet.getString(6))
                .attributes(resultSet.getString(7))
                .userId(resultSet.getString(8))
                .errorText(resultSet.getString(9))
                .errorDetail(resultSet.getString(10))
                .errorStackTrace(resultSet.getString(11))
                .metrics(resultSet.getString(12));
    }

    @ThreadSafe
    private class TraceRowMapper implements RowMapper<PartiallyHydratedTrace> {

        public PartiallyHydratedTrace mapRow(ResultSet resultSet) throws SQLException {
            TraceSnapshot.Builder builder = createBuilder(resultSet);
            // wait and read from rolling file outside of the jdbc connection
            String spansFileBlockId = resultSet.getString(13);
            String coarseMergedStackTreeFileBlockId = resultSet.getString(14);
            String fineMergedStackTreeFileBlockId = resultSet.getString(15);
            return new PartiallyHydratedTrace(builder, spansFileBlockId,
                    coarseMergedStackTreeFileBlockId, fineMergedStackTreeFileBlockId);
        }
    }

    private class PartiallyHydratedTrace {

        private final TraceSnapshot.Builder builder;
        // file block ids are stored temporarily while reading the stored trace from the
        // database so that reading from the rolling file can occur outside of the jdbc connection
        @Nullable
        private final String spansFileBlockId;
        @Nullable
        private final String coarseMergedStackTreeFileBlockId;
        @Nullable
        private final String fineMergedStackTreeFileBlockId;

        private PartiallyHydratedTrace(TraceSnapshot.Builder builder,
                @Nullable String spansFileBlockId,
                @Nullable String coarseMergedStackTreeFileBlockId,
                @Nullable String fineMergedStackTreeFileBlockId) {

            this.builder = builder;
            this.spansFileBlockId = spansFileBlockId;
            this.coarseMergedStackTreeFileBlockId = coarseMergedStackTreeFileBlockId;
            this.fineMergedStackTreeFileBlockId = fineMergedStackTreeFileBlockId;
        }

        private TraceSnapshot fullyHydrate() {
            if (spansFileBlockId != null) {
                FileBlock block;
                try {
                    block = FileBlock.from(spansFileBlockId);
                    builder.spans(rollingFile.read(block, "\"rolled over\""));
                } catch (InvalidBlockId e) {
                    logger.error(e.getMessage(), e);
                }
            }
            if (coarseMergedStackTreeFileBlockId != null) {
                FileBlock block;
                try {
                    block = FileBlock.from(coarseMergedStackTreeFileBlockId);
                    builder.coarseMergedStackTree(rollingFile.read(block, "\"rolled over\""));
                } catch (InvalidBlockId e) {
                    logger.error(e.getMessage(), e);
                }
            }
            if (fineMergedStackTreeFileBlockId != null) {
                FileBlock block;
                try {
                    block = FileBlock.from(fineMergedStackTreeFileBlockId);
                    builder.fineMergedStackTree(rollingFile.read(block, "\"rolled over\""));
                } catch (InvalidBlockId e) {
                    logger.error(e.getMessage(), e);
                }
            }
            return builder.build();
        }
    }

    public static enum StringComparator {

        BEGINS("like", "%s%%"), EQUALS("=", "%s"), CONTAINS("like", "%%%s%%");

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
    private static class SummaryRowMapper implements RowMapper<TraceSnapshotSummary> {

        public TraceSnapshotSummary mapRow(ResultSet resultSet) throws SQLException {
            return TraceSnapshotSummary.from(resultSet.getString(1), resultSet.getLong(2),
                    resultSet.getLong(3), resultSet.getBoolean(4));
        }
    }

    @ThreadSafe
    private static class TraceSummaryRowMapper implements RowMapper<TraceSnapshot> {

        public TraceSnapshot mapRow(ResultSet resultSet) throws SQLException {
            return createBuilder(resultSet).build();
        }
    }
}
