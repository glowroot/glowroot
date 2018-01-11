/*
 * Copyright 2016-2017 the original author or authors.
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

import com.datastax.driver.core.Cluster;
import com.google.common.collect.ImmutableSet;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import org.glowroot.central.util.ClusterManager;
import org.glowroot.central.util.Session;
import org.glowroot.central.v09support.TraceDaoWithV09Support;
import org.glowroot.common.config.ImmutableCentralStorageConfig;
import org.glowroot.common.live.ImmutableTracePointFilter;
import org.glowroot.common.live.LiveTraceRepository.TracePoint;
import org.glowroot.common.live.LiveTraceRepository.TracePointFilter;
import org.glowroot.common.live.StringComparator;
import org.glowroot.common.model.Result;
import org.glowroot.common.repo.ImmutableTraceQuery;
import org.glowroot.common.repo.TraceRepository.TraceQuery;
import org.glowroot.common.util.Clock;
import org.glowroot.wire.api.model.TraceOuterClass.Trace;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

// NOTE this is mostly a copy of TraceDaoTest in glowroot-agent
public class TraceDaoIT {

    private static final String AGENT_ID = "xyz";

    private static Cluster cluster;
    private static Session session;
    private static ClusterManager clusterManager;
    private static TraceDao traceDao;

    @BeforeClass
    public static void setUp() throws Exception {
        SharedSetupRunListener.startCassandra();
        cluster = Clusters.newCluster();
        session = new Session(cluster.newSession());
        session.createKeyspaceIfNotExists("glowroot_unit_tests");
        session.execute("use glowroot_unit_tests");

        clusterManager = ClusterManager.create();
        ConfigRepositoryImpl configRepository = mock(ConfigRepositoryImpl.class);
        when(configRepository.getCentralStorageConfig())
                .thenReturn(ImmutableCentralStorageConfig.builder().build());
        traceDao = new TraceDaoWithV09Support(ImmutableSet.of(), 0, 0, Clock.systemClock(),
                new TraceDaoImpl(session, mock(TransactionTypeDao.class),
                        mock(FullQueryTextDao.class), mock(TraceAttributeNameDao.class),
                        configRepository, Clock.systemClock()));
    }

    @AfterClass
    public static void tearDown() throws Exception {
        clusterManager.close();
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
        TracePointFilter filter = ImmutableTracePointFilter.builder().build();
        Result<TracePoint> queryResult = traceDao.readSlowPoints(AGENT_ID, query, filter, 1);

        // when
        Trace.Header header = traceDao
                .readHeaderPlus(AGENT_ID, queryResult.records().get(0).traceId())
                .header();

        // then
        assertThat(header.getPartial()).isEqualTo(trace.getHeader().getPartial());
        assertThat(header.getStartTime()).isEqualTo(trace.getHeader().getStartTime());
        assertThat(header.getCaptureTime()).isEqualTo(trace.getHeader().getCaptureTime());
        assertThat(header.getDurationNanos()).isEqualTo(trace.getHeader().getDurationNanos());
        assertThat(header.getHeadline()).isEqualTo("test headline");
        assertThat(header.getUser()).isEqualTo(trace.getHeader().getUser());
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
