/*
 * Copyright 2016-2018 the original author or authors.
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
package org.glowroot.central.repo;

import java.util.Collection;

import com.datastax.driver.core.Cluster;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.junit.runners.Parameterized.Parameter;
import org.junit.runners.Parameterized.Parameters;

import org.glowroot.central.util.ClusterManager;
import org.glowroot.central.util.Session;
import org.glowroot.central.v09support.TraceDaoWithV09Support;
import org.glowroot.common.live.ImmutableTracePointFilter;
import org.glowroot.common.live.LiveTraceRepository.TracePoint;
import org.glowroot.common.live.LiveTraceRepository.TracePointFilter;
import org.glowroot.common.live.StringComparator;
import org.glowroot.common.model.Result;
import org.glowroot.common.util.Clock;
import org.glowroot.common2.config.ImmutableCentralStorageConfig;
import org.glowroot.common2.repo.ImmutableTraceQuery;
import org.glowroot.common2.repo.TraceRepository.TraceQuery;
import org.glowroot.wire.api.model.TraceOuterClass.Trace;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

// NOTE this is mostly a copy of TraceDaoTest in glowroot-agent
@RunWith(value = Parameterized.class)
public class TraceDaoIT {

    private static final String AGENT_ID = "xyz";

    private static Cluster cluster;
    private static Session session;
    private static ClusterManager clusterManager;
    private static TraceDao traceDao;

    @Parameter
    public boolean partial;

    @Parameters(name = "partial={0}")
    public static Collection<Boolean> data() {
        return ImmutableList.of(false, true);
    }

    @BeforeClass
    public static void setUp() throws Exception {
        SharedSetupRunListener.startCassandra();
        cluster = Clusters.newCluster();
        session = new Session(cluster.newSession(), "glowroot_unit_tests", null);

        clusterManager = ClusterManager.create();
        ConfigRepositoryImpl configRepository = mock(ConfigRepositoryImpl.class);
        when(configRepository.getCentralStorageConfig())
                .thenReturn(ImmutableCentralStorageConfig.builder().build());
        Clock clock = mock(Clock.class);
        when(clock.currentTimeMillis()).thenReturn(200L);
        traceDao = new TraceDaoWithV09Support(ImmutableSet.of(), 0, 0, clock,
                new TraceDaoImpl(session, mock(TransactionTypeDao.class),
                        mock(FullQueryTextDao.class), mock(TraceAttributeNameDao.class),
                        configRepository, clock));
    }

    @AfterClass
    public static void tearDown() throws Exception {
        clusterManager.close();
        session.close();
        cluster.close();
        SharedSetupRunListener.stopCassandra();
    }

    @Before
    public void beforeEachTest() throws Exception {
        traceDao.truncateAll();
    }

    @Test
    public void shouldReadTrace() throws Exception {
        Trace trace = TraceTestData.createTrace(partial);
        traceDao.store(AGENT_ID, trace);
        TraceQuery query = ImmutableTraceQuery.builder()
                .transactionType("unit test")
                .from(0)
                .to(100)
                .build();
        TracePointFilter filter = ImmutableTracePointFilter.builder()
                .durationNanosLow(0)
                .build();
        Result<TracePoint> queryResult = traceDao.readSlowPoints(AGENT_ID, query, filter, 1);

        // when
        Trace.Header header2 = traceDao
                .readHeaderPlus(AGENT_ID, queryResult.records().get(0).traceId())
                .header();

        // then
        assertThat(header2.getPartial()).isEqualTo(trace.getHeader().getPartial());
        assertThat(header2.getStartTime()).isEqualTo(trace.getHeader().getStartTime());
        assertThat(header2.getCaptureTime()).isEqualTo(trace.getHeader().getCaptureTime());
        assertThat(header2.getDurationNanos()).isEqualTo(trace.getHeader().getDurationNanos());
        assertThat(header2.getHeadline()).isEqualTo("test headline");
        assertThat(header2.getUser()).isEqualTo(trace.getHeader().getUser());
    }

    @Test
    public void shouldReadTraceWithDurationNanosQualifier() throws Exception {
        // given
        Trace trace = TraceTestData.createTrace(partial);
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
        Trace trace = TraceTestData.createTrace(partial);
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
        Trace trace = TraceTestData.createTrace(partial);
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
        Trace trace = TraceTestData.createTrace(partial);
        traceDao.store(AGENT_ID, trace);
        TraceQuery query = ImmutableTraceQuery.builder()
                .transactionType("unit test")
                .from(0)
                .to(100)
                .build();
        TracePointFilter filter = ImmutableTracePointFilter.builder()
                .durationNanosLow(0)
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
        Trace trace = TraceTestData.createTrace(partial);
        traceDao.store(AGENT_ID, trace);
        TraceQuery query = ImmutableTraceQuery.builder()
                .transactionType("unit test")
                .from(0)
                .to(100)
                .build();
        TracePointFilter filter = ImmutableTracePointFilter.builder()
                .durationNanosLow(0)
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
        Trace trace = TraceTestData.createTrace(partial);
        traceDao.store(AGENT_ID, trace);
        TraceQuery query = ImmutableTraceQuery.builder()
                .transactionType("unit test")
                .from(0)
                .to(100)
                .build();
        TracePointFilter filter = ImmutableTracePointFilter.builder()
                .durationNanosLow(0)
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
        Trace trace = TraceTestData.createTrace(partial);
        traceDao.store(AGENT_ID, trace);
        TraceQuery query = ImmutableTraceQuery.builder()
                .transactionType("unit test")
                .from(0)
                .to(100)
                .build();
        TracePointFilter filter = ImmutableTracePointFilter.builder()
                .durationNanosLow(0)
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
        Trace trace = TraceTestData.createTrace(partial);
        traceDao.store(AGENT_ID, trace);
        TraceQuery query = ImmutableTraceQuery.builder()
                .transactionType("unit test")
                .from(0)
                .to(100)
                .build();
        TracePointFilter filter = ImmutableTracePointFilter.builder()
                .durationNanosLow(0)
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
