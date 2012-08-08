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
import java.util.Map;

import javax.annotation.Nullable;

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
            new Column("captured_at", Types.BIGINT),
            new Column("start_at", Types.BIGINT),
            new Column("stuck", Types.BOOLEAN),
            new Column("error", Types.BOOLEAN),
            new Column("duration", Types.BIGINT),
            new Column("completed", Types.BOOLEAN),
            new Column("description", Types.VARCHAR),
            new Column("username", Types.VARCHAR),
            new Column("attributes", Types.VARCHAR),
            new Column("metrics", Types.VARCHAR),
            new Column("spans", Types.VARCHAR),
            new Column("merged_stack_tree", Types.VARCHAR));

    private static final ImmutableList<Index> indexes = ImmutableList.of(new Index("trace_idx",
            "captured_at", "duration"));

    private final StackTraceDao stackTraceDao;
    private final DataSource dataSource;
    private final RollingFile rollingFile;
    private final Clock clock;

    private final boolean valid;

    @Inject
    TraceSnapshotDao(StackTraceDao stackTraceDao, DataSource dataSource,
            RollingFile rollingFile, Clock clock) {

        this.stackTraceDao = stackTraceDao;
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
        Map<String, String> spanStackTraces = snapshot.getSpanStackTraces();
        if (spanStackTraces != null && !spanStackTraces.isEmpty()) {
            stackTraceDao.storeStackTraces(spanStackTraces);
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
        String mergedStackTreeBlockId = null;
        ByteStream mergedStackTree = snapshot.getMergedStackTree();
        if (mergedStackTree != null) {
            try {
                mergedStackTreeBlockId = rollingFile.write(mergedStackTree).getId();
            } catch (IOException e) {
                logger.error(e.getMessage(), e);
            }
        }
        try {
            dataSource.update("merge into trace (id, captured_at, start_at, stuck, error,"
                    + " duration, completed, description, username, attributes, metrics, spans,"
                    + " merged_stack_tree) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    snapshot.getId(), capturedAt, snapshot.getStartAt(), snapshot.isStuck(),
                    snapshot.isError(), snapshot.getDuration(), snapshot.isCompleted(),
                    snapshot.getDescription(), snapshot.getUsername(), snapshot.getAttributes(),
                    snapshot.getMetrics(), spansBlockId, mergedStackTreeBlockId);
        } catch (SQLException e) {
            logger.error(e.getMessage(), e);
        }
    }

    public List<TraceSnapshotSummary> readSummaries(long capturedFrom, long capturedTo,
            long durationLow, long durationHigh, @Nullable StringComparator usernameComparator,
            @Nullable String username) {

        logger.debug("readSummaries(): capturedFrom={}, capturedTo={}, durationLow={},"
                + " durationHigh={}", new Object[] { capturedFrom, capturedTo,
                durationLow, durationHigh });
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
            if (usernameComparator != null && username != null) {
                sql += " and username " + usernameComparator.getComparator() + " ?";
                args.add(usernameComparator.formatParameter(username));
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
            partiallyHydratedTraces = dataSource.query("select id, captured_at, start_at, stuck,"
                    + " error, duration, completed, description, username, attributes, metrics,"
                    + " spans, merged_stack_tree from trace where id = ?", new Object[] { id },
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

    public String readStackTrace(String hash) {
        return stackTraceDao.readStackTrace(hash);
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

    private static class SummaryRowMapper implements RowMapper<TraceSnapshotSummary> {

        public TraceSnapshotSummary mapRow(ResultSet resultSet) throws SQLException {
            return TraceSnapshotSummary.from(resultSet.getString(1), resultSet.getLong(2),
                    resultSet.getLong(3), resultSet.getBoolean(4));
        }
    }

    private class TraceRowMapper implements RowMapper<PartiallyHydratedTrace> {

        public PartiallyHydratedTrace mapRow(ResultSet resultSet) throws SQLException {
            TraceSnapshot.Builder builder = TraceSnapshot.builder()
                    .id(resultSet.getString(1))
                    // column 2 is 'captured_at'
                    .startAt(resultSet.getLong(3))
                    .stuck(resultSet.getBoolean(4))
                    .error(resultSet.getBoolean(5))
                    .duration(resultSet.getLong(6))
                    .completed(resultSet.getBoolean(7))
                    .description(resultSet.getString(8))
                    .username(resultSet.getString(9))
                    .attributes(resultSet.getString(10))
                    .metrics(resultSet.getString(11));
            // wait and read from rolling file outside of the jdbc connection
            String spansFileBlockId = resultSet.getString(12);
            String mergedStackTreeFileBlockId = resultSet.getString(13);
            return new PartiallyHydratedTrace(builder, spansFileBlockId,
                    mergedStackTreeFileBlockId);
        }
    }

    private class PartiallyHydratedTrace {

        private final TraceSnapshot.Builder builder;
        // file block ids are stored temporarily while reading the stored trace from the
        // database so that reading from the rolling file can occur outside of the jdbc connection
        @Nullable
        private final String spansFileBlockId;
        @Nullable
        private final String mergedStackTreeFileBlockId;

        private PartiallyHydratedTrace(TraceSnapshot.Builder builder,
                @Nullable String spansFileBlockId, @Nullable String mergedStackTreeFileBlockId) {

            this.builder = builder;
            this.spansFileBlockId = spansFileBlockId;
            this.mergedStackTreeFileBlockId = mergedStackTreeFileBlockId;
        }

        private TraceSnapshot fullyHydrate() {
            if (spansFileBlockId != null) {
                FileBlock block;
                try {
                    block = FileBlock.from(spansFileBlockId);
                    builder.spans(rollingFile.read(block));
                } catch (InvalidBlockId e) {
                    logger.error(e.getMessage(), e);
                }
            }
            if (mergedStackTreeFileBlockId != null) {
                FileBlock block;
                try {
                    block = FileBlock.from(mergedStackTreeFileBlockId);
                    builder.mergedStackTree(rollingFile.read(block));
                } catch (InvalidBlockId e) {
                    logger.error(e.getMessage(), e);
                }
            }
            return builder.build();
        }
    }
}
