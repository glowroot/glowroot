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

public class TraceDaoTest {

    private DataSource dataSource;
    private File cappedFile;
    private ScheduledExecutorService scheduledExecutor;
    private CappedDatabase cappedDatabase;
    private TraceDao traceDao;

    @Before
    public void beforeEachTest() throws Exception {
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
        traceDao.store(trace, entries, null);
        TracePointQuery query = ImmutableTracePointQuery.builder()
                .from(0)
                .to(100)
                .durationLow(0)
                .durationHigh(Long.MAX_VALUE)
                .transactionType("unit test")
                .errorOnly(false)
                .limit(1)
                .build();
        // new TracePointQuery(0, 100, 0, Long.MAX_VALUE, "unit test", false,
        // false, null, null, null, null, null, null, null, null, null, null, null, 1);
        // when
        QueryResult<TracePoint> queryResult = traceDao.readPoints(query);
        Trace trace2 = traceDao.readTrace(queryResult.records().get(0).id());
        // then
        assertThat(trace2.id()).isEqualTo(trace.id());
        assertThat(trace2.partial()).isEqualTo(trace.partial());
        assertThat(trace2.startTime()).isEqualTo(trace.startTime());
        assertThat(trace2.captureTime()).isEqualTo(trace.captureTime());
        assertThat(trace2.duration()).isEqualTo(trace.duration());
        assertThat(trace2.headline()).isEqualTo("test headline");
        assertThat(trace2.user()).isEqualTo(trace.user());
    }

    @Test
    public void shouldReadTraceWithDurationQualifier() throws SQLException {
        // given
        Trace trace = TraceTestData.createTrace();
        CharSource entries = TraceTestData.createEntries();
        traceDao.store(trace, entries, null);
        TracePointQuery query = ImmutableTracePointQuery.builder()
                .from(0)
                .to(100)
                .durationLow(trace.duration())
                .durationHigh(trace.duration())
                .transactionType("unit test")
                .errorOnly(false)
                .limit(1)
                .build();
        // when
        QueryResult<TracePoint> queryResult = traceDao.readPoints(query);
        // then
        assertThat(queryResult.records()).hasSize(1);
    }

    @Test
    public void shouldNotReadTraceWithHighDurationQualifier() throws SQLException {
        // given
        Trace trace = TraceTestData.createTrace();
        CharSource entries = TraceTestData.createEntries();
        traceDao.store(trace, entries, null);
        TracePointQuery query = ImmutableTracePointQuery.builder()
                .from(0)
                .to(0)
                .durationLow(trace.duration() + 1)
                .durationHigh(trace.duration() + 2)
                .transactionType("unit test")
                .errorOnly(false)
                .limit(1)
                .build();
        // when
        QueryResult<TracePoint> queryResult = traceDao.readPoints(query);
        // then
        assertThat(queryResult.records()).isEmpty();
    }

    @Test
    public void shouldNotReadTraceWithLowDurationQualifier() throws SQLException {
        // given
        Trace trace = TraceTestData.createTrace();
        CharSource entries = TraceTestData.createEntries();
        traceDao.store(trace, entries, null);
        TracePointQuery query = ImmutableTracePointQuery.builder()
                .from(0)
                .to(0)
                .durationLow(trace.duration() - 2)
                .durationHigh(trace.duration() - 1)
                .transactionType("unit test")
                .errorOnly(false)
                .limit(1)
                .build();
        // when
        QueryResult<TracePoint> queryResult = traceDao.readPoints(query);
        // then
        assertThat(queryResult.records()).isEmpty();
    }

    @Test
    public void shouldReadTraceWithCustomAttributeQualifier() throws SQLException {
        // given
        Trace trace = TraceTestData.createTrace();
        CharSource entries = TraceTestData.createEntries();
        traceDao.store(trace, entries, null);
        TracePointQuery query = ImmutableTracePointQuery.builder()
                .from(0)
                .to(100)
                .durationLow(0)
                .durationHigh(Long.MAX_VALUE)
                .transactionType("unit test")
                .customAttributeName("abc")
                .customAttributeValueComparator(StringComparator.EQUALS)
                .customAttributeValue("xyz")
                .errorOnly(false)
                .limit(1)
                .build();
        // when
        QueryResult<TracePoint> queryResult = traceDao.readPoints(query);
        // then
        assertThat(queryResult.records()).hasSize(1);
    }

    @Test
    public void shouldReadTraceWithCustomAttributeQualifier2() throws SQLException {
        // given
        Trace trace = TraceTestData.createTrace();
        CharSource entries = TraceTestData.createEntries();
        traceDao.store(trace, entries, null);
        TracePointQuery query = ImmutableTracePointQuery.builder()
                .from(0)
                .to(100)
                .durationLow(0)
                .durationHigh(Long.MAX_VALUE)
                .transactionType("unit test")
                .customAttributeName("abc")
                .customAttributeValueComparator(null)
                .customAttributeValue(null)
                .errorOnly(false)
                .limit(1)
                .build();
        // when
        QueryResult<TracePoint> queryResult = traceDao.readPoints(query);
        // then
        assertThat(queryResult.records()).hasSize(1);
    }

    @Test
    public void shouldReadTraceWithCustomAttributeQualifier3() throws SQLException {
        // given
        Trace trace = TraceTestData.createTrace();
        CharSource entries = TraceTestData.createEntries();
        traceDao.store(trace, entries, null);
        TracePointQuery query = ImmutableTracePointQuery.builder()
                .from(0)
                .to(100)
                .durationLow(0)
                .durationHigh(Long.MAX_VALUE)
                .transactionType("unit test")
                .customAttributeName(null)
                .customAttributeValueComparator(StringComparator.EQUALS)
                .customAttributeValue("xyz")
                .errorOnly(false)
                .limit(1)
                .build();
        // when
        QueryResult<TracePoint> queryResult = traceDao.readPoints(query);
        // then
        assertThat(queryResult.records()).hasSize(1);
    }

    @Test
    public void shouldNotReadTraceWithNonMatchingCustomAttributeQualifier() throws SQLException {
        // given
        Trace trace = TraceTestData.createTrace();
        CharSource entries = TraceTestData.createEntries();
        traceDao.store(trace, entries, null);
        TracePointQuery query = ImmutableTracePointQuery.builder()
                .from(0)
                .to(100)
                .durationLow(0)
                .durationHigh(Long.MAX_VALUE)
                .transactionType("unit test")
                .customAttributeName("abc")
                .customAttributeValueComparator(StringComparator.EQUALS)
                .customAttributeValue("abc")
                .errorOnly(false)
                .limit(1)
                .build();
        // when
        QueryResult<TracePoint> queryResult = traceDao.readPoints(query);
        // then
        assertThat(queryResult.records()).isEmpty();
    }

    @Test
    public void shouldNotReadTraceWithNonMatchingCustomAttributeQualifier2() throws SQLException {
        // given
        Trace trace = TraceTestData.createTrace();
        CharSource entries = TraceTestData.createEntries();
        traceDao.store(trace, entries, null);
        TracePointQuery query = ImmutableTracePointQuery.builder()
                .from(0)
                .to(100)
                .durationLow(0)
                .durationHigh(Long.MAX_VALUE)
                .transactionType("unit test")
                .customAttributeName(null)
                .customAttributeValueComparator(StringComparator.EQUALS)
                .customAttributeValue("xyz1")
                .errorOnly(false)
                .limit(1)
                .build();
        // when
        QueryResult<TracePoint> queryResult = traceDao.readPoints(query);
        // then
        assertThat(queryResult.records()).isEmpty();
    }

    @Test
    public void shouldDeletedTrace() throws SQLException {
        // given
        Trace trace = TraceTestData.createTrace();
        CharSource entries = TraceTestData.createEntries();
        traceDao.store(trace, entries, null);
        // when
        traceDao.deleteBefore(100);
        // then
        assertThat(traceDao.count()).isEqualTo(0);
    }
}
