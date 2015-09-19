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
package org.glowroot.server.simplerepo;

import java.io.File;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import org.glowroot.collector.spi.model.TraceOuterClass.Trace;
import org.glowroot.common.util.Tickers;
import org.glowroot.live.ImmutableTracePointQuery;
import org.glowroot.live.LiveTraceRepository.TracePoint;
import org.glowroot.live.LiveTraceRepository.TracePointQuery;
import org.glowroot.live.StringComparator;
import org.glowroot.server.repo.Result;
import org.glowroot.server.simplerepo.util.CappedDatabase;
import org.glowroot.server.simplerepo.util.DataSource;

import static org.assertj.core.api.Assertions.assertThat;

public class TraceDaoTest {

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
        cappedDatabase = new CappedDatabase(cappedFile, 1000000, Tickers.getTicker());
        traceDao = new TraceDao(dataSource, cappedDatabase);
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
        traceDao.collect(trace);
        TracePointQuery query = ImmutableTracePointQuery.builder()
                .from(0)
                .to(100)
                .durationNanosLow(0)
                .durationNanosHigh(Long.MAX_VALUE)
                .transactionType("unit test")
                .errorOnly(false)
                .limit(1)
                .build();
        Result<TracePoint> queryResult = traceDao.readPoints(query);
        Trace.Header header = traceDao.readHeader(queryResult.records().get(0).id()).header();
        // then
        assertThat(header.getId()).isEqualTo(trace.getHeader().getId());
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
        traceDao.collect(trace);
        TracePointQuery query = ImmutableTracePointQuery.builder()
                .from(0)
                .to(100)
                .durationNanosLow(trace.getHeader().getDurationNanos())
                .durationNanosHigh(trace.getHeader().getDurationNanos())
                .transactionType("unit test")
                .errorOnly(false)
                .limit(1)
                .build();
        // when
        Result<TracePoint> queryResult = traceDao.readPoints(query);
        // then
        assertThat(queryResult.records()).hasSize(1);
    }

    @Test
    public void shouldNotReadTraceWithHighTotalNanosQualifier() throws Exception {
        // given
        Trace trace = TraceTestData.createTrace();
        traceDao.collect(trace);
        TracePointQuery query = ImmutableTracePointQuery.builder()
                .from(0)
                .to(0)
                .durationNanosLow(trace.getHeader().getDurationNanos() + 1)
                .durationNanosHigh(trace.getHeader().getDurationNanos() + 2)
                .transactionType("unit test")
                .errorOnly(false)
                .limit(1)
                .build();
        // when
        Result<TracePoint> queryResult = traceDao.readPoints(query);
        // then
        assertThat(queryResult.records()).isEmpty();
    }

    @Test
    public void shouldNotReadTraceWithLowTotalNanosQualifier() throws Exception {
        // given
        Trace trace = TraceTestData.createTrace();
        traceDao.collect(trace);
        TracePointQuery query = ImmutableTracePointQuery.builder()
                .from(0)
                .to(0)
                .durationNanosLow(trace.getHeader().getDurationNanos() - 2)
                .durationNanosHigh(trace.getHeader().getDurationNanos() - 1)
                .transactionType("unit test")
                .errorOnly(false)
                .limit(1)
                .build();
        // when
        Result<TracePoint> queryResult = traceDao.readPoints(query);
        // then
        assertThat(queryResult.records()).isEmpty();
    }

    @Test
    public void shouldReadTraceWithAttributeQualifier() throws Exception {
        // given
        Trace trace = TraceTestData.createTrace();
        traceDao.collect(trace);
        TracePointQuery query = ImmutableTracePointQuery.builder()
                .from(0)
                .to(100)
                .durationNanosLow(0)
                .durationNanosHigh(Long.MAX_VALUE)
                .transactionType("unit test")
                .attributeName("abc")
                .attributeValueComparator(StringComparator.EQUALS)
                .attributeValue("xyz")
                .errorOnly(false)
                .limit(1)
                .build();
        // when
        Result<TracePoint> queryResult = traceDao.readPoints(query);
        // then
        assertThat(queryResult.records()).hasSize(1);
    }

    @Test
    public void shouldReadTraceWithAttributeQualifier2() throws Exception {
        // given
        Trace trace = TraceTestData.createTrace();
        traceDao.collect(trace);
        TracePointQuery query = ImmutableTracePointQuery.builder()
                .from(0)
                .to(100)
                .durationNanosLow(0)
                .durationNanosHigh(Long.MAX_VALUE)
                .transactionType("unit test")
                .attributeName("abc")
                .attributeValueComparator(null)
                .attributeValue(null)
                .errorOnly(false)
                .limit(1)
                .build();
        // when
        Result<TracePoint> queryResult = traceDao.readPoints(query);
        // then
        assertThat(queryResult.records()).hasSize(1);
    }

    @Test
    public void shouldReadTraceWithAttributeQualifier3() throws Exception {
        // given
        Trace trace = TraceTestData.createTrace();
        traceDao.collect(trace);
        TracePointQuery query = ImmutableTracePointQuery.builder()
                .from(0)
                .to(100)
                .durationNanosLow(0)
                .durationNanosHigh(Long.MAX_VALUE)
                .transactionType("unit test")
                .attributeName(null)
                .attributeValueComparator(StringComparator.EQUALS)
                .attributeValue("xyz")
                .errorOnly(false)
                .limit(1)
                .build();
        // when
        Result<TracePoint> queryResult = traceDao.readPoints(query);
        // then
        assertThat(queryResult.records()).hasSize(1);
    }

    @Test
    public void shouldNotReadTraceWithNonMatchingAttributeQualifier() throws Exception {
        // given
        Trace trace = TraceTestData.createTrace();
        traceDao.collect(trace);
        TracePointQuery query = ImmutableTracePointQuery.builder()
                .from(0)
                .to(100)
                .durationNanosLow(0)
                .durationNanosHigh(Long.MAX_VALUE)
                .transactionType("unit test")
                .attributeName("abc")
                .attributeValueComparator(StringComparator.EQUALS)
                .attributeValue("abc")
                .errorOnly(false)
                .limit(1)
                .build();
        // when
        Result<TracePoint> queryResult = traceDao.readPoints(query);
        // then
        assertThat(queryResult.records()).isEmpty();
    }

    @Test
    public void shouldNotReadTraceWithNonMatchingAttributeQualifier2() throws Exception {
        // given
        Trace trace = TraceTestData.createTrace();
        traceDao.collect(trace);
        TracePointQuery query = ImmutableTracePointQuery.builder()
                .from(0)
                .to(100)
                .durationNanosLow(0)
                .durationNanosHigh(Long.MAX_VALUE)
                .transactionType("unit test")
                .attributeName(null)
                .attributeValueComparator(StringComparator.EQUALS)
                .attributeValue("xyz1")
                .errorOnly(false)
                .limit(1)
                .build();
        // when
        Result<TracePoint> queryResult = traceDao.readPoints(query);
        // then
        assertThat(queryResult.records()).isEmpty();
    }

    @Test
    public void shouldDeletedTrace() throws Exception {
        // given
        Trace trace = TraceTestData.createTrace();
        traceDao.collect(trace);
        // when
        traceDao.deleteBefore(100);
        // then
        assertThat(traceDao.count()).isEqualTo(0);
    }
}
