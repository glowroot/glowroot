/*
 * Copyright 2016 the original author or authors.
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
package org.glowroot.server.storage;

import com.datastax.driver.core.Cluster;
import com.datastax.driver.core.Session;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import org.glowroot.common.live.ImmutableTracePointFilter;
import org.glowroot.common.live.LiveTraceRepository.TracePoint;
import org.glowroot.common.live.LiveTraceRepository.TracePointFilter;
import org.glowroot.common.live.StringComparator;
import org.glowroot.common.model.Result;
import org.glowroot.storage.config.ImmutableServerStorageConfig;
import org.glowroot.storage.repo.ConfigRepository;
import org.glowroot.storage.repo.ImmutableTraceQuery;
import org.glowroot.storage.repo.TraceRepository.TraceQuery;
import org.glowroot.wire.api.model.TraceOuterClass.Trace;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

// NOTE this is mostly a copy of TraceDaoTest in glowroot-agent
public class TraceDaoIT {

    private static final String AGENT_ID = "xyz";

    private static Cluster cluster;
    private static Session session;
    private static TraceDao traceDao;

    @BeforeClass
    public static void setUp() throws Exception {
        SharedSetupRunListener.startCassandra();
        cluster = Cluster.builder().addContactPoint("127.0.0.1").build();
        session = cluster.newSession();
        session.execute("create keyspace if not exists glowroot_unit_tests with replication ="
                + " { 'class' : 'SimpleStrategy', 'replication_factor' : 1 }");
        session.execute("use glowroot_unit_tests");

        ConfigRepository configRepository = mock(ConfigRepository.class);
        when(configRepository.getStorageConfig())
                .thenReturn(ImmutableServerStorageConfig.builder().build());
        traceDao = new TraceDao(session, mock(TransactionTypeDao.class), configRepository);
    }

    @AfterClass
    public static void tearDown() throws Exception {
        session.close();
        cluster.close();
        SharedSetupRunListener.stopCassandra();
    }

    @Test
    public void shouldReadTrace() throws Exception {
        // given
        Trace trace = TraceTestData.createTrace();
        traceDao.store(AGENT_ID, trace);
        TraceQuery query = ImmutableTraceQuery.builder()
                .transactionType("unit test")
                .from(0)
                .to(100)
                .build();
        TracePointFilter filter = ImmutableTracePointFilter.builder()
                .durationNanosLow(0)
                .durationNanosHigh(Long.MAX_VALUE)
                .build();
        Result<TracePoint> queryResult = traceDao.readSlowPoints(AGENT_ID, query, filter, 1);
        Trace.Header header =
                traceDao.readHeaderPlus(AGENT_ID, queryResult.records().get(0).traceId()).header();
        // then
        assertThat(header.getPartial()).isEqualTo(trace.getHeader().getPartial());
        assertThat(header.getStartTime()).isEqualTo(trace.getHeader().getStartTime());
        assertThat(header.getCaptureTime()).isEqualTo(trace.getHeader().getCaptureTime());
        assertThat(header.getDurationNanos()).isEqualTo(trace.getHeader().getDurationNanos());
        assertThat(header.getHeadline()).isEqualTo("test headline");
        assertThat(header.getUser()).isEqualTo(trace.getHeader().getUser());
    }

    @Test
    public void shouldReadTraceWithDurationNanosQualifier() throws Exception {
        // given
        Trace trace = TraceTestData.createTrace();
        traceDao.store(AGENT_ID, trace);
        TraceQuery query = ImmutableTraceQuery.builder()
                .transactionType("unit test")
                .from(0)
                .to(100)
                .build();
        TracePointFilter filter = ImmutableTracePointFilter.builder()
                .durationNanosLow(trace.getHeader().getDurationNanos())
                .durationNanosHigh(trace.getHeader().getDurationNanos())
                .build();
        // when
        Result<TracePoint> queryResult = traceDao.readSlowPoints(AGENT_ID, query, filter, 1);
        // then
        assertThat(queryResult.records()).hasSize(1);
    }

    @Test
    public void shouldNotReadTraceWithHighDurationNanosQualifier() throws Exception {
        // given
        Trace trace = TraceTestData.createTrace();
        traceDao.store(AGENT_ID, trace);
        TraceQuery query = ImmutableTraceQuery.builder()
                .transactionType("unit test")
                .from(0)
                .to(100)
                .build();
        TracePointFilter filter = ImmutableTracePointFilter.builder()
                .durationNanosLow(trace.getHeader().getDurationNanos() + 1)
                .durationNanosHigh(trace.getHeader().getDurationNanos() + 2)
                .build();
        // when
        Result<TracePoint> queryResult = traceDao.readSlowPoints(AGENT_ID, query, filter, 1);
        // then
        assertThat(queryResult.records()).isEmpty();
    }

    @Test
    public void shouldNotReadTraceWithLowDurationNanosQualifier() throws Exception {
        // given
        Trace trace = TraceTestData.createTrace();
        traceDao.store(AGENT_ID, trace);
        TraceQuery query = ImmutableTraceQuery.builder()
                .transactionType("unit test")
                .from(0)
                .to(100)
                .build();
        TracePointFilter filter = ImmutableTracePointFilter.builder()
                .durationNanosLow(trace.getHeader().getDurationNanos() - 2)
                .durationNanosHigh(trace.getHeader().getDurationNanos() - 1)
                .build();
        // when
        Result<TracePoint> queryResult = traceDao.readSlowPoints(AGENT_ID, query, filter, 1);
        // then
        assertThat(queryResult.records()).isEmpty();
    }

    @Test
    public void shouldReadTraceWithAttributeQualifier() throws Exception {
        // given
        Trace trace = TraceTestData.createTrace();
        traceDao.store(AGENT_ID, trace);
        TraceQuery query = ImmutableTraceQuery.builder()
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
        Result<TracePoint> queryResult = traceDao.readSlowPoints(AGENT_ID, query, filter, 1);
        // then
        assertThat(queryResult.records()).hasSize(1);
    }

    @Test
    public void shouldReadTraceWithAttributeQualifier2() throws Exception {
        // given
        Trace trace = TraceTestData.createTrace();
        traceDao.store(AGENT_ID, trace);
        TraceQuery query = ImmutableTraceQuery.builder()
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
        Result<TracePoint> queryResult = traceDao.readSlowPoints(AGENT_ID, query, filter, 1);
        // then
        assertThat(queryResult.records()).hasSize(1);
    }

    @Test
    public void shouldReadTraceWithAttributeQualifier3() throws Exception {
        // given
        Trace trace = TraceTestData.createTrace();
        traceDao.store(AGENT_ID, trace);
        TraceQuery query = ImmutableTraceQuery.builder()
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
        Result<TracePoint> queryResult = traceDao.readSlowPoints(AGENT_ID, query, filter, 1);
        // then
        assertThat(queryResult.records()).hasSize(1);
    }

    @Test
    public void shouldNotReadTraceWithNonMatchingAttributeQualifier() throws Exception {
        // given
        Trace trace = TraceTestData.createTrace();
        traceDao.store(AGENT_ID, trace);
        TraceQuery query = ImmutableTraceQuery.builder()
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
        Result<TracePoint> queryResult = traceDao.readSlowPoints(AGENT_ID, query, filter, 1);
        // then
        assertThat(queryResult.records()).isEmpty();
    }

    @Test
    public void shouldNotReadTraceWithNonMatchingAttributeQualifier2() throws Exception {
        // given
        Trace trace = TraceTestData.createTrace();
        traceDao.store(AGENT_ID, trace);
        TraceQuery query = ImmutableTraceQuery.builder()
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
        Result<TracePoint> queryResult = traceDao.readSlowPoints(AGENT_ID, query, filter, 1);
        // then
        assertThat(queryResult.records()).isEmpty();
    }
}
