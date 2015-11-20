/*
 * Copyright 2011-2015 the original author or authors.
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
package org.glowroot.storage.simplerepo;

import java.io.File;

import com.google.common.base.Ticker;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import org.glowroot.common.live.ImmutableTracePointFilter;
import org.glowroot.common.live.LiveTraceRepository.TracePoint;
import org.glowroot.common.live.LiveTraceRepository.TracePointFilter;
import org.glowroot.common.live.StringComparator;
import org.glowroot.storage.repo.ImmutableTraceQuery;
import org.glowroot.storage.repo.Result;
import org.glowroot.storage.repo.TraceRepository.TraceQuery;
import org.glowroot.storage.simplerepo.util.CappedDatabase;
import org.glowroot.storage.simplerepo.util.DataSource;
import org.glowroot.wire.api.model.TraceOuterClass.Trace;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

public class TraceDaoTest {

    private static final String SERVER_ID = "";

    private DataSource dataSource;
    private File cappedFile;
    private CappedDatabase cappedDatabase;
    private TraceDao traceDao;

    @Before
    public void beforeEachTest() throws Exception {
        dataSource = new DataSource();
        if (dataSource.tableExists("trace")) {
            dataSource.execute("drop table trace");
        }
        cappedFile = File.createTempFile("glowroot-test-", ".capped.db");
        cappedDatabase = new CappedDatabase(cappedFile, 1000000, Ticker.systemTicker());
        traceDao = new TraceDao(dataSource, cappedDatabase, mock(TransactionTypeDao.class));
    }

    @After
    public void afterEachTest() throws Exception {
        dataSource.close();
        cappedDatabase.close();
        cappedFile.delete();
    }

    @Test
    public void shouldReadTrace() throws Exception {
        // given
        Trace trace = TraceTestData.createTrace();
        traceDao.collect(SERVER_ID, trace);
        TraceQuery query = ImmutableTraceQuery.builder()
                .serverRollup(SERVER_ID)
                .transactionType("unit test")
                .from(0)
                .to(100)
                .build();
        TracePointFilter filter = ImmutableTracePointFilter.builder()
                .durationNanosLow(0)
                .durationNanosHigh(Long.MAX_VALUE)
                .build();
        Result<TracePoint> queryResult = traceDao.readSlowPoints(query, filter, 1);
        Trace.Header header =
                traceDao.readHeader(SERVER_ID, queryResult.records().get(0).traceId()).header();
        // then
        assertThat(header.getPartial()).isEqualTo(trace.getHeader().getPartial());
        assertThat(header.getStartTime()).isEqualTo(trace.getHeader().getStartTime());
        assertThat(header.getCaptureTime()).isEqualTo(trace.getHeader().getCaptureTime());
        assertThat(header.getDurationNanos()).isEqualTo(trace.getHeader().getDurationNanos());
        assertThat(header.getHeadline()).isEqualTo("test headline");
        assertThat(header.getUser()).isEqualTo(trace.getHeader().getUser());
    }

    @Test
    public void shouldReadTraceWithTotalNanosQualifier() throws Exception {
        // given
        Trace trace = TraceTestData.createTrace();
        traceDao.collect(SERVER_ID, trace);
        TraceQuery query = ImmutableTraceQuery.builder()
                .serverRollup(SERVER_ID)
                .transactionType("unit test")
                .from(0)
                .to(100)
                .build();
        TracePointFilter filter = ImmutableTracePointFilter.builder()
                .durationNanosLow(trace.getHeader().getDurationNanos())
                .durationNanosHigh(trace.getHeader().getDurationNanos())
                .build();
        // when
        Result<TracePoint> queryResult = traceDao.readSlowPoints(query, filter, 1);
        // then
        assertThat(queryResult.records()).hasSize(1);
    }

    @Test
    public void shouldNotReadTraceWithHighTotalNanosQualifier() throws Exception {
        // given
        Trace trace = TraceTestData.createTrace();
        traceDao.collect(SERVER_ID, trace);
        TraceQuery query = ImmutableTraceQuery.builder()
                .serverRollup(SERVER_ID)
                .transactionType("unit test")
                .from(0)
                .to(100)
                .build();
        TracePointFilter filter = ImmutableTracePointFilter.builder()
                .durationNanosLow(trace.getHeader().getDurationNanos() + 1)
                .durationNanosHigh(trace.getHeader().getDurationNanos() + 2)
                .build();
        // when
        Result<TracePoint> queryResult = traceDao.readSlowPoints(query, filter, 1);
        // then
        assertThat(queryResult.records()).isEmpty();
    }

    @Test
    public void shouldNotReadTraceWithLowTotalNanosQualifier() throws Exception {
        // given
        Trace trace = TraceTestData.createTrace();
        traceDao.collect(SERVER_ID, trace);
        TraceQuery query = ImmutableTraceQuery.builder()
                .serverRollup(SERVER_ID)
                .transactionType("unit test")
                .from(0)
                .to(100)
                .build();
        TracePointFilter filter = ImmutableTracePointFilter.builder()
                .durationNanosLow(trace.getHeader().getDurationNanos() - 2)
                .durationNanosHigh(trace.getHeader().getDurationNanos() - 1)
                .build();
        // when
        Result<TracePoint> queryResult = traceDao.readSlowPoints(query, filter, 1);
        // then
        assertThat(queryResult.records()).isEmpty();
    }

    @Test
    public void shouldReadTraceWithAttributeQualifier() throws Exception {
        // given
        Trace trace = TraceTestData.createTrace();
        traceDao.collect(SERVER_ID, trace);
        TraceQuery query = ImmutableTraceQuery.builder()
                .serverRollup(SERVER_ID)
                .transactionType("unit test")
                .from(0)
                .to(100)
                .build();
        TracePointFilter filter = ImmutableTracePointFilter.builder()
                .durationNanosLow(0)
                .durationNanosHigh(Long.MAX_VALUE)
                .attributeName("abc")
                .attributeValueComparator(StringComparator.EQUALS)
                .attributeValue("xyz")
                .build();
        // when
        Result<TracePoint> queryResult = traceDao.readSlowPoints(query, filter, 1);
        // then
        assertThat(queryResult.records()).hasSize(1);
    }

    @Test
    public void shouldReadTraceWithAttributeQualifier2() throws Exception {
        // given
        Trace trace = TraceTestData.createTrace();
        traceDao.collect(SERVER_ID, trace);
        TraceQuery query = ImmutableTraceQuery.builder()
                .serverRollup(SERVER_ID)
                .transactionType("unit test")
                .from(0)
                .to(100)
                .build();
        TracePointFilter filter = ImmutableTracePointFilter.builder()
                .durationNanosLow(0)
                .durationNanosHigh(Long.MAX_VALUE)
                .attributeName("abc")
                .attributeValueComparator(null)
                .attributeValue(null)
                .build();
        // when
        Result<TracePoint> queryResult = traceDao.readSlowPoints(query, filter, 1);
        // then
        assertThat(queryResult.records()).hasSize(1);
    }

    @Test
    public void shouldReadTraceWithAttributeQualifier3() throws Exception {
        // given
        Trace trace = TraceTestData.createTrace();
        traceDao.collect(SERVER_ID, trace);
        TraceQuery query = ImmutableTraceQuery.builder()
                .serverRollup(SERVER_ID)
                .transactionType("unit test")
                .from(0)
                .to(100)
                .build();
        TracePointFilter filter = ImmutableTracePointFilter.builder()
                .durationNanosLow(0)
                .durationNanosHigh(Long.MAX_VALUE)
                .attributeName(null)
                .attributeValueComparator(StringComparator.EQUALS)
                .attributeValue("xyz")
                .build();
        // when
        Result<TracePoint> queryResult = traceDao.readSlowPoints(query, filter, 1);
        // then
        assertThat(queryResult.records()).hasSize(1);
    }

    @Test
    public void shouldNotReadTraceWithNonMatchingAttributeQualifier() throws Exception {
        // given
        Trace trace = TraceTestData.createTrace();
        traceDao.collect(SERVER_ID, trace);
        TraceQuery query = ImmutableTraceQuery.builder()
                .serverRollup(SERVER_ID)
                .transactionType("unit test")
                .from(0)
                .to(100)
                .build();
        TracePointFilter filter = ImmutableTracePointFilter.builder()
                .durationNanosLow(0)
                .durationNanosHigh(Long.MAX_VALUE)
                .attributeName("abc")
                .attributeValueComparator(StringComparator.EQUALS)
                .attributeValue("abc")
                .build();
        // when
        Result<TracePoint> queryResult = traceDao.readSlowPoints(query, filter, 1);
        // then
        assertThat(queryResult.records()).isEmpty();
    }

    @Test
    public void shouldNotReadTraceWithNonMatchingAttributeQualifier2() throws Exception {
        // given
        Trace trace = TraceTestData.createTrace();
        traceDao.collect(SERVER_ID, trace);
        TraceQuery query = ImmutableTraceQuery.builder()
                .serverRollup(SERVER_ID)
                .transactionType("unit test")
                .from(0)
                .to(100)
                .build();
        TracePointFilter filter = ImmutableTracePointFilter.builder()
                .durationNanosLow(0)
                .durationNanosHigh(Long.MAX_VALUE)
                .attributeName(null)
                .attributeValueComparator(StringComparator.EQUALS)
                .attributeValue("xyz1")
                .build();
        // when
        Result<TracePoint> queryResult = traceDao.readSlowPoints(query, filter, 1);
        // then
        assertThat(queryResult.records()).isEmpty();
    }

    @Test
    public void shouldDeletedTrace() throws Exception {
        // given
        Trace trace = TraceTestData.createTrace();
        traceDao.collect(SERVER_ID, trace);
        // when
        traceDao.deleteBefore(100);
        // then
        assertThat(traceDao.readHeader(SERVER_ID, trace.getId())).isNull();
    }
}
