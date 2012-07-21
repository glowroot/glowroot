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
import org.informantproject.local.trace.StoredTrace.Builder;
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
public class TraceDao {

    private static final Logger logger = LoggerFactory.getLogger(TraceDao.class);

    private static final ImmutableList<Column> columns = ImmutableList.of(
            new PrimaryKeyColumn("id", Types.VARCHAR),
            new Column("captured_at", Types.BIGINT),
            new Column("start_at", Types.BIGINT),
            new Column("stuck", Types.BOOLEAN),
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

    private final DataSource dataSource;
    private final RollingFile rollingFile;
    private final Clock clock;

    private final boolean valid;

    @Inject
    TraceDao(DataSource dataSource, RollingFile rollingFile, Clock clock) {
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

    void storeTrace(StoredTrace storedTrace) {
        logger.debug("storeTrace(): storedTrace={}", storedTrace);
        if (!valid) {
            return;
        }
        // capture time before writing to rolling file
        long capturedAt = clock.currentTimeMillis();
        String spansBlockId = null;
        ByteStream spans = storedTrace.getSpans();
        if (spans != null) {
            try {
                spansBlockId = rollingFile.write(spans).getId();
            } catch (IOException e) {
                logger.error(e.getMessage(), e);
            }
        }
        String mergedStackTreeBlockId = null;
        ByteStream mergedStackTree = storedTrace.getMergedStackTree();
        if (mergedStackTree != null) {
            try {
                mergedStackTreeBlockId = rollingFile.write(mergedStackTree).getId();
            } catch (IOException e) {
                logger.error(e.getMessage(), e);
            }
        }
        try {
            dataSource.update("merge into trace (id, captured_at, start_at, stuck, duration,"
                    + " completed, description, username, attributes, metrics, spans,"
                    + " merged_stack_tree) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    storedTrace.getId(), capturedAt, storedTrace.getStartAt(),
                    storedTrace.isStuck(), storedTrace.getDuration(), storedTrace.isCompleted(),
                    storedTrace.getDescription(), storedTrace.getUsername(),
                    storedTrace.getAttributes(), storedTrace.getMetrics(), spansBlockId,
                    mergedStackTreeBlockId);
        } catch (SQLException e) {
            logger.error(e.getMessage(), e);
        }
    }

    public List<StoredTraceDuration> readStoredTraceDurations(long capturedFrom, long capturedTo,
            long durationLow, long durationHigh, @Nullable StringComparator usernameComparator,
            @Nullable String username) {

        logger.debug("readStoredTraceDurations(): capturedFrom={}, capturedTo={}, durationLow={},"
                + " durationHigh={}", new Object[] { capturedFrom, capturedTo, durationLow,
                durationHigh });
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
            return dataSource.query(sql, args.toArray(), new TraceDurationRowMapper());
        } catch (SQLException e) {
            logger.error(e.getMessage(), e);
            return ImmutableList.of();
        }
    }

    @Nullable
    public StoredTrace readStoredTrace(String id) {
        logger.debug("readStoredTrace(): id={}", id);
        if (!valid) {
            return null;
        }
        List<PartiallyHydratedTrace> partiallyHydratedTraces;
        try {
            partiallyHydratedTraces = dataSource.query("select id, captured_at, start_at, stuck,"
                    + " duration, completed, description, username, attributes, metrics, spans,"
                    + " merged_stack_tree from trace where id = ?", new Object[] { id },
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

    public List<StoredTrace> readStoredTraces(long capturedFrom, long capturedTo) {
        logger.debug("readStoredTraces(): capturedFrom={}, capturedTo={}", capturedFrom,
                capturedTo);
        if (!valid) {
            return ImmutableList.of();
        }
        List<PartiallyHydratedTrace> partiallyHydratedTraces;
        try {
            partiallyHydratedTraces = dataSource.query("select id, captured_at, start_at, stuck,"
                    + " duration, completed, description, username, attributes, metrics, spans,"
                    + " merged_stack_tree from trace where captured_at >= ? and captured_at <= ?",
                    new Object[] { capturedFrom, capturedTo }, new TraceRowMapper());
        } catch (SQLException e) {
            logger.error(e.getMessage(), e);
            return ImmutableList.of();
        }
        // read from rolling file outside of jdbc connection
        List<StoredTrace> storedTraces = Lists.newArrayList();
        for (PartiallyHydratedTrace trace : partiallyHydratedTraces) {
            storedTraces.add(trace.fullyHydrate());
        }
        return storedTraces;
    }

    public List<StoredTrace> readStoredTraces(long capturedFrom, long capturedTo, long lowDuration,
            long highDuration) {

        logger.debug("readStoredTraces(): capturedFrom={}, capturedTo={}, lowDuration={},"
                + " highDuration={}", new long[] { capturedFrom, capturedTo, lowDuration,
                highDuration });
        if (!valid) {
            return ImmutableList.of();
        }
        if (lowDuration <= 0 && highDuration == Long.MAX_VALUE) {
            return readStoredTraces(capturedFrom, capturedTo);
        }
        List<PartiallyHydratedTrace> partiallyHydratedTraces;
        try {
            partiallyHydratedTraces = dataSource.query("select id, captured_at, start_at, stuck,"
                    + " duration, completed, description, username, attributes, metrics, spans,"
                    + " merged_stack_tree from trace where captured_at >= ? and captured_at <= ?"
                    + " and duration >= ? and duration <= ?", new Object[] { capturedFrom,
                    capturedTo, lowDuration, highDuration }, new TraceRowMapper());
        } catch (SQLException e) {
            logger.error(e.getMessage(), e);
            return ImmutableList.of();
        }
        // read from rolling file outside of jdbc connection
        List<StoredTrace> storedTraces = Lists.newArrayList();
        for (PartiallyHydratedTrace trace : partiallyHydratedTraces) {
            storedTraces.add(trace.fullyHydrate());
        }
        return storedTraces;
    }

    public int deleteStoredTraces(final long capturedFrom, final long capturedTo) {
        logger.debug("deleteStoredTraces(): capturedFrom={}, capturedTo={}", capturedFrom,
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

    public void deleteAllStoredTraces() {
        logger.debug("deleteAllStoredTraces()");
        if (!valid) {
            return;
        }
        try {
            dataSource.execute("truncate table trace");
        } catch (SQLException e) {
            logger.error(e.getMessage(), e);
        }
    }

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

    private static class TraceDurationRowMapper implements RowMapper<StoredTraceDuration> {

        public StoredTraceDuration mapRow(ResultSet resultSet) throws SQLException {
            return new StoredTraceDuration(resultSet.getString(1), resultSet.getLong(2),
                    resultSet.getLong(3), resultSet.getBoolean(4));
        }
    }

    private class TraceRowMapper implements RowMapper<PartiallyHydratedTrace> {

        public PartiallyHydratedTrace mapRow(ResultSet resultSet) throws SQLException {
            StoredTrace.Builder builder = new StoredTrace.Builder();
            int columnIndex = 1;
            builder.id(resultSet.getString(columnIndex++));
            columnIndex++; // TODO place holder for captured_at
            builder.startAt(resultSet.getLong(columnIndex++));
            builder.stuck(resultSet.getBoolean(columnIndex++));
            builder.duration(resultSet.getLong(columnIndex++));
            builder.completed(resultSet.getBoolean(columnIndex++));
            builder.description(resultSet.getString(columnIndex++));
            builder.username(resultSet.getString(columnIndex++));
            builder.attributes(resultSet.getString(columnIndex++));
            builder.metrics(resultSet.getString(columnIndex++));
            // wait and read from rolling file outside of jdbc connection
            // return new TempStoredTrace(builder, )
            String spansFileBlockId = resultSet.getString(columnIndex++);
            String mergedStackTreeFileBlockId = resultSet.getString(columnIndex++);
            return new PartiallyHydratedTrace(builder, spansFileBlockId,
                    mergedStackTreeFileBlockId);
        }
    }

    private class PartiallyHydratedTrace {

        private final StoredTrace.Builder builder;
        // file block ids are stored temporarily while reading the stored trace from the
        // database so that reading from the rolling file can occur outside of the jdbc connection
        private final String spansFileBlockId;
        private final String mergedStackTreeFileBlockId;

        private PartiallyHydratedTrace(Builder builder, @Nullable String spansFileBlockId,
                @Nullable String mergedStackTreeFileBlockId) {

            this.builder = builder;
            this.spansFileBlockId = spansFileBlockId;
            this.mergedStackTreeFileBlockId = mergedStackTreeFileBlockId;
        }

        private StoredTrace fullyHydrate() {
            if (spansFileBlockId != null) {
                FileBlock block;
                try {
                    block = new FileBlock(spansFileBlockId);
                    builder.spans(rollingFile.read(block));
                } catch (InvalidBlockId e) {
                    logger.error(e.getMessage(), e);
                }
            }
            if (mergedStackTreeFileBlockId != null) {
                FileBlock block;
                try {
                    block = new FileBlock(mergedStackTreeFileBlockId);
                    builder.mergedStackTree(rollingFile.read(block));
                } catch (InvalidBlockId e) {
                    logger.error(e.getMessage(), e);
                }
            }
            return builder.build();
        }
    }
}
