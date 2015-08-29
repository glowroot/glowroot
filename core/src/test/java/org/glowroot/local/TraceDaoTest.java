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
package org.glowroot.local;

import java.io.File;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import org.glowroot.collector.spi.Trace;
import org.glowroot.common.repo.ImmutableTracePointQuery;
import org.glowroot.common.repo.Result;
import org.glowroot.common.repo.StringComparator;
import org.glowroot.common.repo.TraceRepository.TraceHeader;
import org.glowroot.common.repo.TraceRepository.TracePoint;
import org.glowroot.common.repo.TraceRepository.TracePointQuery;
import org.glowroot.common.util.Tickers;
import org.glowroot.local.util.CappedDatabase;
import org.glowroot.local.util.DataSource;

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
        // new TracePointQuery(0, 100, 0, Long.MAX_VALUE, "unit test", false,
        // false, null, null, null, null, null, null, null, null, null, null, null, 1);
        // when
        Result<TracePoint> queryResult = traceDao.readPoints(query);
        TraceHeader traceHeader2 = traceDao.readTraceHeader(queryResult.records().get(0).id());
        // then
        assertThat(traceHeader2.id()).isEqualTo(trace.id());
        assertThat(traceHeader2.partial()).isEqualTo(trace.partial());
        assertThat(traceHeader2.startTime()).isEqualTo(trace.startTime());
        assertThat(traceHeader2.captureTime()).isEqualTo(trace.captureTime());
        assertThat(traceHeader2.durationNanos()).isEqualTo(trace.durationNanos());
        assertThat(traceHeader2.headline()).isEqualTo("test headline");
        assertThat(traceHeader2.user()).isEqualTo(trace.user());
    }

    @Test
    public void shouldReadTraceWithDurationQualifier() throws Exception {
        // given
        Trace trace = TraceTestData.createTrace();
        traceDao.collect(trace);
        TracePointQuery query = ImmutableTracePointQuery.builder()
                .from(0)
                .to(100)
                .durationNanosLow(trace.durationNanos())
                .durationNanosHigh(trace.durationNanos())
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
    public void shouldNotReadTraceWithHighDurationQualifier() throws Exception {
        // given
        Trace trace = TraceTestData.createTrace();
        traceDao.collect(trace);
        TracePointQuery query = ImmutableTracePointQuery.builder()
                .from(0)
                .to(0)
                .durationNanosLow(trace.durationNanos() + 1)
                .durationNanosHigh(trace.durationNanos() + 2)
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
    public void shouldNotReadTraceWithLowDurationQualifier() throws Exception {
        // given
        Trace trace = TraceTestData.createTrace();
        traceDao.collect(trace);
        TracePointQuery query = ImmutableTracePointQuery.builder()
                .from(0)
                .to(0)
                .durationNanosLow(trace.durationNanos() - 2)
                .durationNanosHigh(trace.durationNanos() - 1)
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
    public void shouldReadTraceWithCustomAttributeQualifier() throws Exception {
        // given
        Trace trace = TraceTestData.createTrace();
        traceDao.collect(trace);
        TracePointQuery query = ImmutableTracePointQuery.builder()
                .from(0)
                .to(100)
                .durationNanosLow(0)
                .durationNanosHigh(Long.MAX_VALUE)
                .transactionType("unit test")
                .customAttributeName("abc")
                .customAttributeValueComparator(StringComparator.EQUALS)
                .customAttributeValue("xyz")
                .errorOnly(false)
                .limit(1)
                .build();
        // when
        Result<TracePoint> queryResult = traceDao.readPoints(query);
        // then
        assertThat(queryResult.records()).hasSize(1);
    }

    @Test
    public void shouldReadTraceWithCustomAttributeQualifier2() throws Exception {
        // given
        Trace trace = TraceTestData.createTrace();
        traceDao.collect(trace);
        TracePointQuery query = ImmutableTracePointQuery.builder()
                .from(0)
                .to(100)
                .durationNanosLow(0)
                .durationNanosHigh(Long.MAX_VALUE)
                .transactionType("unit test")
                .customAttributeName("abc")
                .customAttributeValueComparator(null)
                .customAttributeValue(null)
                .errorOnly(false)
                .limit(1)
                .build();
        // when
        Result<TracePoint> queryResult = traceDao.readPoints(query);
        // then
        assertThat(queryResult.records()).hasSize(1);
    }

    @Test
    public void shouldReadTraceWithCustomAttributeQualifier3() throws Exception {
        // given
        Trace trace = TraceTestData.createTrace();
        traceDao.collect(trace);
        TracePointQuery query = ImmutableTracePointQuery.builder()
                .from(0)
                .to(100)
                .durationNanosLow(0)
                .durationNanosHigh(Long.MAX_VALUE)
                .transactionType("unit test")
                .customAttributeName(null)
                .customAttributeValueComparator(StringComparator.EQUALS)
                .customAttributeValue("xyz")
                .errorOnly(false)
                .limit(1)
                .build();
        // when
        Result<TracePoint> queryResult = traceDao.readPoints(query);
        // then
        assertThat(queryResult.records()).hasSize(1);
    }

    @Test
    public void shouldNotReadTraceWithNonMatchingCustomAttributeQualifier() throws Exception {
        // given
        Trace trace = TraceTestData.createTrace();
        traceDao.collect(trace);
        TracePointQuery query = ImmutableTracePointQuery.builder()
                .from(0)
                .to(100)
                .durationNanosLow(0)
                .durationNanosHigh(Long.MAX_VALUE)
                .transactionType("unit test")
                .customAttributeName("abc")
                .customAttributeValueComparator(StringComparator.EQUALS)
                .customAttributeValue("abc")
                .errorOnly(false)
                .limit(1)
                .build();
        // when
        Result<TracePoint> queryResult = traceDao.readPoints(query);
        // then
        assertThat(queryResult.records()).isEmpty();
    }

    @Test
    public void shouldNotReadTraceWithNonMatchingCustomAttributeQualifier2() throws Exception {
        // given
        Trace trace = TraceTestData.createTrace();
        traceDao.collect(trace);
        TracePointQuery query = ImmutableTracePointQuery.builder()
                .from(0)
                .to(100)
                .durationNanosLow(0)
                .durationNanosHigh(Long.MAX_VALUE)
                .transactionType("unit test")
                .customAttributeName(null)
                .customAttributeValueComparator(StringComparator.EQUALS)
                .customAttributeValue("xyz1")
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
