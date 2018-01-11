/*
 * Copyright 2011-2017 the original author or authors.
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
package org.glowroot.agent.embedded.repo;

import java.io.File;

import com.google.common.base.Ticker;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import org.glowroot.agent.collector.Collector.TraceReader;
import org.glowroot.agent.embedded.util.CappedDatabase;
import org.glowroot.agent.embedded.util.DataSource;
import org.glowroot.common.live.ImmutableTracePointFilter;
import org.glowroot.common.live.LiveTraceRepository.TracePoint;
import org.glowroot.common.live.LiveTraceRepository.TracePointFilter;
import org.glowroot.common.live.StringComparator;
import org.glowroot.common.model.Result;
import org.glowroot.common.repo.ImmutableTraceQuery;
import org.glowroot.common.repo.TraceRepository.TraceQuery;
import org.glowroot.wire.api.model.TraceOuterClass.Trace;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

// NOTE this is mostly a copy of TraceDaoIT.java in glowroot-central
//
// this is not an integration test (*IT.java) since then it would run against shaded agent and fail
// due to shading issues
public class TraceDaoTest {

    private static final String AGENT_ID = "";

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
        traceDao = new TraceDao(dataSource, cappedDatabase, mock(TransactionTypeDao.class),
                mock(FullQueryTextDao.class), mock(TraceAttributeNameDao.class));
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
        Trace.Header header = TraceTestData.createTraceHeader();
        traceDao.store(TraceTestData.createTraceReader(header));
        TraceQuery query = ImmutableTraceQuery.builder()
                .transactionType("unit test")
                .from(0)
                .to(100)
                .build();
        TracePointFilter filter = ImmutableTracePointFilter.builder().build();
        Result<TracePoint> queryResult = traceDao.readSlowPoints(AGENT_ID, query, filter, 1);

        // when
        Trace.Header header2 = traceDao
                .readHeaderPlus(AGENT_ID, queryResult.records().get(0).traceId())
                .header();

        // then
        assertThat(header2.getPartial()).isEqualTo(header.getPartial());
        assertThat(header2.getStartTime()).isEqualTo(header.getStartTime());
        assertThat(header2.getCaptureTime()).isEqualTo(header.getCaptureTime());
        assertThat(header2.getDurationNanos()).isEqualTo(header.getDurationNanos());
        assertThat(header2.getHeadline()).isEqualTo("test headline");
        assertThat(header2.getUser()).isEqualTo(header.getUser());
    }

    @Test
    public void shouldReadTraceWithAttributeQualifier() throws Exception {
        // given
        traceDao.store(TraceTestData.createTraceReader());
        TraceQuery query = ImmutableTraceQuery.builder()
                .transactionType("unit test")
                .from(0)
                .to(100)
                .build();
        TracePointFilter filter = ImmutableTracePointFilter.builder()
                .attributeName("abc")
                .attributeValueComparator(StringComparator.EQUALS)
                .attributeValue("xyz")
                .build();

        // when
        Result<TracePoint> queryResult = traceDao.readSlowPoints(AGENT_ID, query, filter, 1);

        // then
        assertThat(queryResult.records()).hasSize(1);
    }

    @Test
    public void shouldReadTraceWithAttributeQualifier2() throws Exception {
        // given
        traceDao.store(TraceTestData.createTraceReader());
        TraceQuery query = ImmutableTraceQuery.builder()
                .transactionType("unit test")
                .from(0)
                .to(100)
                .build();
        TracePointFilter filter = ImmutableTracePointFilter.builder()
                .attributeName("abc")
                .attributeValueComparator(null)
                .attributeValue(null)
                .build();

        // when
        Result<TracePoint> queryResult = traceDao.readSlowPoints(AGENT_ID, query, filter, 1);

        // then
        assertThat(queryResult.records()).hasSize(1);
    }

    @Test
    public void shouldReadTraceWithAttributeQualifier3() throws Exception {
        // given
        traceDao.store(TraceTestData.createTraceReader());
        TraceQuery query = ImmutableTraceQuery.builder()
                .transactionType("unit test")
                .from(0)
                .to(100)
                .build();
        TracePointFilter filter = ImmutableTracePointFilter.builder()
                .attributeName(null)
                .attributeValueComparator(StringComparator.EQUALS)
                .attributeValue("xyz")
                .build();

        // when
        Result<TracePoint> queryResult = traceDao.readSlowPoints(AGENT_ID, query, filter, 1);

        // then
        assertThat(queryResult.records()).hasSize(1);
    }

    @Test
    public void shouldNotReadTraceWithNonMatchingAttributeQualifier() throws Exception {
        // given
        traceDao.store(TraceTestData.createTraceReader());
        TraceQuery query = ImmutableTraceQuery.builder()
                .transactionType("unit test")
                .from(0)
                .to(100)
                .build();
        TracePointFilter filter = ImmutableTracePointFilter.builder()
                .attributeName("abc")
                .attributeValueComparator(StringComparator.EQUALS)
                .attributeValue("abc")
                .build();

        // when
        Result<TracePoint> queryResult = traceDao.readSlowPoints(AGENT_ID, query, filter, 1);

        // then
        assertThat(queryResult.records()).isEmpty();
    }

    @Test
    public void shouldNotReadTraceWithNonMatchingAttributeQualifier2() throws Exception {
        // given
        traceDao.store(TraceTestData.createTraceReader());
        TraceQuery query = ImmutableTraceQuery.builder()
                .transactionType("unit test")
                .from(0)
                .to(100)
                .build();
        TracePointFilter filter = ImmutableTracePointFilter.builder()
                .attributeName(null)
                .attributeValueComparator(StringComparator.EQUALS)
                .attributeValue("xyz1")
                .build();

        // when
        Result<TracePoint> queryResult = traceDao.readSlowPoints(AGENT_ID, query, filter, 1);

        // then
        assertThat(queryResult.records()).isEmpty();
    }

    @Test
    public void shouldDeletedTrace() throws Exception {
        // given
        TraceReader traceReader = TraceTestData.createTraceReader();
        traceDao.store(traceReader);
        // when
        traceDao.deleteBefore(100);
        // then
        assertThat(traceDao.readHeaderPlus(AGENT_ID, traceReader.traceId())).isNull();
    }
}
