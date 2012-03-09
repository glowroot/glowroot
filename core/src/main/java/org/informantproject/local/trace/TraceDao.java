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
import java.util.Collections;
import java.util.List;

import org.informantproject.core.util.Clock;
import org.informantproject.core.util.DataSource;
import org.informantproject.core.util.DataSource.Column;
import org.informantproject.core.util.DataSource.Index;
import org.informantproject.core.util.DataSource.PrimaryKeyColumn;
import org.informantproject.core.util.DataSource.RowMapper;
import org.informantproject.core.util.FileBlock;
import org.informantproject.core.util.FileBlock.InvalidBlockId;
import org.informantproject.core.util.RollingFile;
import org.informantproject.core.util.RollingFile.FileBlockNoLongerExists;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.collect.ImmutableList;
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

    private static ImmutableList<Column> columns = ImmutableList.of(
            new PrimaryKeyColumn("id", Types.VARCHAR),
            new Column("captured_at", Types.BIGINT),
            new Column("start_at", Types.BIGINT),
            new Column("stuck", Types.BOOLEAN),
            new Column("duration", Types.BIGINT),
            new Column("completed", Types.BOOLEAN),
            new Column("description", Types.VARCHAR),
            new Column("username", Types.VARCHAR),
            new Column("metrics", Types.VARCHAR),
            new Column("context_map", Types.VARCHAR),
            new Column("spans", Types.VARCHAR),
            new Column("merged_stack_tree", Types.VARCHAR));

    private static ImmutableList<Index> indexes = ImmutableList.of(
            new Index("trace_idx", "captured_at", "duration"));

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
        try {
            spansBlockId = rollingFile.write(storedTrace.getSpans()).getId();
        } catch (IOException e) {
            logger.error(e.getMessage(), e);
        }
        String mergedStackTreeBlockId = null;
        if (storedTrace.getMergedStackTree() != null) {
            try {
                mergedStackTreeBlockId = rollingFile.write(storedTrace.getMergedStackTree())
                        .getId();
            } catch (IOException e) {
                logger.error(e.getMessage(), e);
            }
        }
        try {
            dataSource.update("merge into trace (id, captured_at, start_at, stuck, duration,"
                    + " completed, description, username, metrics, context_map, spans,"
                    + " merged_stack_tree) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    storedTrace.getId(), capturedAt, storedTrace.getStartAt(),
                    storedTrace.isStuck(), storedTrace.getDuration(), storedTrace.isCompleted(),
                    storedTrace.getDescription(), storedTrace.getUsername(),
                    storedTrace.getMetrics(), storedTrace.getContextMap(), spansBlockId,
                    mergedStackTreeBlockId);
        } catch (SQLException e) {
            logger.error(e.getMessage(), e);
        }
    }

    public List<StoredTraceDuration> readStoredTraceDurations(long capturedFrom, long capturedTo) {
        logger.debug("readStoredTraceDurations(): capturedFrom={}, capturedTo={}", capturedFrom,
                capturedTo);
        if (!valid) {
            return Collections.emptyList();
        }
        try {
            return dataSource.query("select id, captured_at, duration, completed from trace where"
                    + " captured_at >= ? and captured_at <= ?", new Object[] { capturedFrom,
                    capturedTo }, new TraceDurationRowMapper());
        } catch (SQLException e) {
            logger.error(e.getMessage(), e);
            return Collections.emptyList();
        }
    }

    public StoredTrace readStoredTrace(String id) {
        logger.debug("readStoredTrace(): id={}", id);
        if (!valid) {
            return null;
        }
        List<StoredTrace> storedTraces;
        try {
            storedTraces = dataSource.query("select id, captured_at, start_at, stuck, duration,"
                    + " completed, description, username, metrics, context_map, spans,"
                    + " merged_stack_tree from trace where id = ?", new Object[] { id },
                    new TraceRowMapper());
        } catch (SQLException e) {
            logger.error(e.getMessage(), e);
            return null;
        }
        if (storedTraces.isEmpty()) {
            return null;
        } else if (storedTraces.size() > 1) {
            logger.error("multiple records returned for id '{}'", id);
        }
        // read from rolling file outside of jdbc connection
        StoredTrace storedTrace = storedTraces.get(0);
        fillInRollingFileData(storedTraces);
        return storedTrace;
    }

    public List<StoredTrace> readStoredTraces(long capturedFrom, long capturedTo) {
        logger.debug("readStoredTraces(): capturedFrom={}, capturedTo={}", capturedFrom,
                capturedTo);
        if (!valid) {
            return Collections.emptyList();
        }
        List<StoredTrace> storedTraces;
        try {
            storedTraces = dataSource.query("select id, captured_at, start_at, stuck, duration,"
                    + " completed, description, username, metrics, context_map, spans,"
                    + " merged_stack_tree from trace where captured_at >= ? and captured_at <= ?",
                    new Object[] { capturedFrom, capturedTo }, new TraceRowMapper());
        } catch (SQLException e) {
            logger.error(e.getMessage(), e);
            return Collections.emptyList();
        }
        // read from rolling file outside of jdbc connection
        fillInRollingFileData(storedTraces);
        return storedTraces;
    }

    public List<StoredTrace> readStoredTraces(long capturedFrom, long capturedTo, long lowDuration,
            long highDuration) {

        logger.debug("readStoredTraces(): capturedFrom={}, capturedTo={}, lowDuration={},"
                + " highDuration={}", new long[] { capturedFrom, capturedTo, lowDuration,
                highDuration });
        if (!valid) {
            return Collections.emptyList();
        }
        if (lowDuration <= 0 && highDuration == Long.MAX_VALUE) {
            return readStoredTraces(capturedFrom, capturedTo);
        }
        List<StoredTrace> storedTraces;
        try {
            storedTraces = dataSource.query("select id, captured_at, start_at, stuck, duration,"
                    + " completed, description, username, metrics, context_map, spans,"
                    + " merged_stack_tree from trace where captured_at >= ? and captured_at <= ?"
                    + " and duration >= ? and duration <= ?", new Object[] { capturedFrom,
                    capturedTo, lowDuration, highDuration }, new TraceRowMapper());
        } catch (SQLException e) {
            logger.error(e.getMessage(), e);
            return Collections.emptyList();
        }
        // read from rolling file outside of jdbc connection
        fillInRollingFileData(storedTraces);
        return storedTraces;
    }

    private void fillInRollingFileData(List<StoredTrace> storedTraces) {
        for (StoredTrace storedTrace : storedTraces) {
            fillInRollingFileData(storedTrace);
        }
    }

    private void fillInRollingFileData(StoredTrace storedTrace) {
        if (storedTrace.getSpans() != null) {
            String sp = null;
            try {
                sp = rollingFile.read(new FileBlock(storedTrace.getSpans().toString()));
            } catch (IOException e) {
                logger.error(e.getMessage(), e);
            } catch (InvalidBlockId e) {
                logger.error(e.getMessage(), e);
            } catch (FileBlockNoLongerExists e) {
            }
            storedTrace.setSpans(sp);
        }
        if (storedTrace.getMergedStackTree() != null) {
            String mst = null;
            try {
                mst = rollingFile.read(new FileBlock(storedTrace.getMergedStackTree()
                        .toString()));
            } catch (IOException e) {
                logger.error(e.getMessage(), e);
            } catch (InvalidBlockId e) {
                logger.error(e.getMessage(), e);
            } catch (FileBlockNoLongerExists e) {
                // TODO provide user message in this case
            }
            storedTrace.setMergedStackTree(mst);
        }
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

    private static class TraceDurationRowMapper implements RowMapper<StoredTraceDuration> {
        public StoredTraceDuration mapRow(ResultSet resultSet) throws SQLException {
            return new StoredTraceDuration(resultSet.getString(1), resultSet.getLong(2),
                    resultSet.getLong(3), resultSet.getBoolean(4));
        }
    }

    private static class TraceRowMapper implements RowMapper<StoredTrace> {
        public StoredTrace mapRow(ResultSet resultSet) throws SQLException {
            StoredTrace storedTrace = new StoredTrace();
            int columnIndex = 1;
            storedTrace.setId(resultSet.getString(columnIndex++));
            columnIndex++; // TODO place holder for captured_at
            storedTrace.setStartAt(resultSet.getLong(columnIndex++));
            storedTrace.setStuck(resultSet.getBoolean(columnIndex++));
            storedTrace.setDuration(resultSet.getLong(columnIndex++));
            storedTrace.setCompleted(resultSet.getBoolean(columnIndex++));
            storedTrace.setDescription(resultSet.getString(columnIndex++));
            storedTrace.setUsername(resultSet.getString(columnIndex++));
            storedTrace.setMetrics(resultSet.getString(columnIndex++));
            storedTrace.setContextMap(resultSet.getString(columnIndex++));
            // wait and read from rolling file outside of jdbc connection
            storedTrace.setSpans(resultSet.getString(columnIndex++));
            storedTrace.setMergedStackTree(resultSet.getString(columnIndex++));
            return storedTrace;
        }
    }
}
