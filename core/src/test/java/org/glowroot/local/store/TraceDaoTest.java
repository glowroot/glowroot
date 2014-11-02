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

import java.io.File;
import java.io.IOException;
import java.sql.SQLException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

import com.google.common.io.CharSource;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import org.glowroot.collector.Trace;
import org.glowroot.common.Ticker;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * @author Trask Stalnaker
 * @since 0.5
 */
public class TraceDaoTest {

    private DataSource dataSource;
    private File cappedFile;
    private ScheduledExecutorService scheduledExecutor;
    private CappedDatabase cappedDatabase;
    private TraceDao traceDao;

    @Before
    public void beforeEachTest() throws SQLException, IOException {
        dataSource = new DataSource();
        if (dataSource.tableExists("trace")) {
            dataSource.execute("drop table trace");
        }
        cappedFile = File.createTempFile("glowroot-test-", ".capped.db");
        scheduledExecutor = Executors.newSingleThreadScheduledExecutor();
        cappedDatabase = new CappedDatabase(cappedFile, 1000000, scheduledExecutor,
                Ticker.systemTicker());
        traceDao = new TraceDao(dataSource, cappedDatabase);
    }

    @After
    public void afterEachTest() throws Exception {
        scheduledExecutor.shutdownNow();
        dataSource.close();
        cappedDatabase.close();
        cappedFile.delete();
    }

    @Test
    public void shouldReadTrace() throws SQLException {
        // given
        Trace trace = TraceTestData.createTrace();
        CharSource entries = TraceTestData.createEntries();
        traceDao.store(trace, entries, null, null);
        TracePointQuery query = new TracePointQuery(0, 100, 0, Long.MAX_VALUE, "unit test", false,
                false, null, null, null, null, null, null, null, null, null, null, null, 1);
        // when
        QueryResult<TracePoint> queryResult = traceDao.readPoints(query);
        Trace trace2 = traceDao.readTrace(queryResult.getRecords().get(0).getId());
        // then
        assertThat(trace2.getId()).isEqualTo(trace.getId());
        assertThat(trace2.isPartial()).isEqualTo(trace.isPartial());
        assertThat(trace2.getStartTime()).isEqualTo(trace.getStartTime());
        assertThat(trace2.getCaptureTime()).isEqualTo(trace.getCaptureTime());
        assertThat(trace2.getDuration()).isEqualTo(trace.getDuration());
        assertThat(trace2.getHeadline()).isEqualTo("test headline");
        assertThat(trace2.getUser()).isEqualTo(trace.getUser());
    }

    @Test
    public void shouldReadTraceWithDurationQualifier() throws SQLException {
        // given
        Trace trace = TraceTestData.createTrace();
        CharSource entries = TraceTestData.createEntries();
        traceDao.store(trace, entries, null, null);
        TracePointQuery query = new TracePointQuery(0, 100, trace.getDuration(),
                trace.getDuration(), "unit test", false, false, null, null, null, null, null,
                null, null, null, null, null, null, 1);
        // when
        QueryResult<TracePoint> queryResult = traceDao.readPoints(query);
        // then
        assertThat(queryResult.getRecords()).hasSize(1);
    }

    @Test
    public void shouldNotReadTraceWithHighDurationQualifier() throws SQLException {
        // given
        Trace trace = TraceTestData.createTrace();
        CharSource entries = TraceTestData.createEntries();
        traceDao.store(trace, entries, null, null);
        TracePointQuery query = new TracePointQuery(0, 0, trace.getDuration() + 1,
                trace.getDuration() + 2, "unit test", false, false, null, null, null, null,
                null, null, null, null, null, null, null, 1);
        // when
        QueryResult<TracePoint> queryResult = traceDao.readPoints(query);
        // then
        assertThat(queryResult.getRecords()).isEmpty();
    }

    @Test
    public void shouldNotReadTraceWithLowDurationQualifier() throws SQLException {
        // given
        Trace trace = TraceTestData.createTrace();
        CharSource entries = TraceTestData.createEntries();
        traceDao.store(trace, entries, null, null);
        TracePointQuery query = new TracePointQuery(0, 0, trace.getDuration() - 2,
                trace.getDuration() - 1, "unit test", false, false, null, null, null, null,
                null, null, null, null, null, null, null, 1);
        // when
        QueryResult<TracePoint> queryResult = traceDao.readPoints(query);
        // then
        assertThat(queryResult.getRecords()).isEmpty();
    }

    @Test
    public void shouldReadTraceWithAttributeQualifier() throws SQLException {
        // given
        Trace trace = TraceTestData.createTrace();
        CharSource entries = TraceTestData.createEntries();
        traceDao.store(trace, entries, null, null);
        TracePointQuery query = new TracePointQuery(0, 100, 0, Long.MAX_VALUE, "unit test", false,
                false, null, null, null, null, null, null, null, null, "abc",
                StringComparator.EQUALS, "xyz", 1);
        // when
        QueryResult<TracePoint> queryResult = traceDao.readPoints(query);
        // then
        assertThat(queryResult.getRecords()).hasSize(1);
    }

    @Test
    public void shouldReadTraceWithAttributeQualifier2() throws SQLException {
        // given
        Trace trace = TraceTestData.createTrace();
        CharSource entries = TraceTestData.createEntries();
        traceDao.store(trace, entries, null, null);
        TracePointQuery query = new TracePointQuery(0, 100, 0, Long.MAX_VALUE, "unit test", false,
                false, null, null, null, null, null, null, null, null, "abc", null, null, 1);
        // when
        QueryResult<TracePoint> queryResult = traceDao.readPoints(query);
        // then
        assertThat(queryResult.getRecords()).hasSize(1);
    }

    @Test
    public void shouldReadTraceWithAttributeQualifier3() throws SQLException {
        // given
        Trace trace = TraceTestData.createTrace();
        CharSource entries = TraceTestData.createEntries();
        traceDao.store(trace, entries, null, null);
        TracePointQuery query = new TracePointQuery(0, 100, 0, Long.MAX_VALUE, "unit test", false,
                false, null, null, null, null, null, null, null, null, null,
                StringComparator.EQUALS, "xyz", 1);
        // when
        QueryResult<TracePoint> queryResult = traceDao.readPoints(query);
        // then
        assertThat(queryResult.getRecords()).hasSize(1);
    }

    @Test
    public void shouldNotReadTraceWithNonMatchingAttributeQualifier() throws SQLException {
        // given
        Trace trace = TraceTestData.createTrace();
        CharSource entries = TraceTestData.createEntries();
        traceDao.store(trace, entries, null, null);
        TracePointQuery query = new TracePointQuery(0, 100, 0, Long.MAX_VALUE, "unit test", false,
                false, null, null, null, null, null, null, null, null, "abc",
                StringComparator.EQUALS, "abc", 1);
        // when
        QueryResult<TracePoint> queryResult = traceDao.readPoints(query);
        // then
        assertThat(queryResult.getRecords()).isEmpty();
    }

    @Test
    public void shouldNotReadTraceWithNonMatchingAttributeQualifier2() throws SQLException {
        // given
        Trace trace = TraceTestData.createTrace();
        CharSource entries = TraceTestData.createEntries();
        traceDao.store(trace, entries, null, null);
        TracePointQuery query = new TracePointQuery(0, 100, 0, Long.MAX_VALUE, "unit test", false,
                false, null, null, null, null, null, null, null, null, null,
                StringComparator.EQUALS, "xyz1", 1);
        // when
        QueryResult<TracePoint> queryResult = traceDao.readPoints(query);
        // then
        assertThat(queryResult.getRecords()).isEmpty();
    }

    @Test
    public void shouldDeletedTrace() throws SQLException {
        // given
        Trace trace = TraceTestData.createTrace();
        CharSource entries = TraceTestData.createEntries();
        traceDao.store(trace, entries, null, null);
        // when
        traceDao.deleteBefore(100);
        // then
        assertThat(traceDao.count()).isEqualTo(0);
    }
}
