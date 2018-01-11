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
package org.glowroot.central;

import com.google.common.collect.ImmutableList;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;

import org.glowroot.central.repo.AgentDao;
import org.glowroot.central.repo.V09AgentRollupDao;
import org.glowroot.central.util.ClusterManager;
import org.glowroot.common.live.LiveJvmService.AgentNotConnectedException;
import org.glowroot.wire.api.model.AgentConfigOuterClass.AgentConfig;
import org.glowroot.wire.api.model.DownstreamServiceOuterClass.MBeanDumpRequest.MBeanDumpKind;

import static org.mockito.Mockito.mock;

public class DownstreamServiceNotConnectedTest {

    private static ClusterManager clusterManager;

    private DownstreamServiceImpl downstreamService =
            new DownstreamServiceImpl(mock(AgentDao.class), mock(V09AgentRollupDao.class),
                    clusterManager);

    @Rule
    public ExpectedException thrown = ExpectedException.none();

    @BeforeClass
    public static void setUp() throws Exception {
        clusterManager = ClusterManager.create();
    }

    @AfterClass
    public static void tearDown() throws Exception {
        clusterManager.close();
    }

    @Test
    public void shouldNotThrowAgentNotConnectExceptionOnUpdateAgentConfig() throws Exception {
        downstreamService.updateAgentConfigIfConnected("a", AgentConfig.getDefaultInstance());
    }

    @Test
    public void shouldThrowAgentNotConnectExceptionOnThreadDump() throws Exception {
        thrown.expect(AgentNotConnectedException.class);
        downstreamService.threadDump("a");
    }

    @Test
    public void shouldThrowAgentNotConnectExceptionOnAvailableDiskSpaceBytes() throws Exception {
        thrown.expect(AgentNotConnectedException.class);
        downstreamService.availableDiskSpaceBytes("a", "dummy");
    }

    @Test
    public void shouldThrowAgentNotConnectExceptionOnHeapDump() throws Exception {
        thrown.expect(AgentNotConnectedException.class);
        downstreamService.heapDump("a", "dummy");
    }

    @Test
    public void shouldThrowAgentNotConnectExceptionOnGc() throws Exception {
        thrown.expect(AgentNotConnectedException.class);
        downstreamService.gc("a");
    }

    @Test
    public void shouldThrowAgentNotConnectExceptionOnMbeanDump() throws Exception {
        thrown.expect(AgentNotConnectedException.class);
        downstreamService.mbeanDump("a", MBeanDumpKind.ALL_MBEANS_INCLUDE_ATTRIBUTES,
                ImmutableList.of());
    }

    @Test
    public void shouldThrowAgentNotConnectExceptionOnMatchingMBeanObjectNames() throws Exception {
        thrown.expect(AgentNotConnectedException.class);
        downstreamService.matchingMBeanObjectNames("a", "b", 3);
    }

    @Test
    public void shouldThrowAgentNotConnectExceptionOnMbeanMeta() throws Exception {
        thrown.expect(AgentNotConnectedException.class);
        downstreamService.mbeanMeta("a", "dummy");
    }

    @Test
    public void shouldThrowAgentNotConnectExceptionOnCapabilities() throws Exception {
        thrown.expect(AgentNotConnectedException.class);
        downstreamService.capabilities("a");
    }

    @Test
    public void shouldThrowAgentNotConnectExceptionOnGlobalMeta() throws Exception {
        thrown.expect(AgentNotConnectedException.class);
        downstreamService.globalMeta("a");
    }

    @Test
    public void shouldThrowAgentNotConnectExceptionOnPreloadClasspathCache() throws Exception {
        thrown.expect(AgentNotConnectedException.class);
        downstreamService.preloadClasspathCache("a");
    }

    @Test
    public void shouldThrowAgentNotConnectExceptionOnMatchingClassNames() throws Exception {
        thrown.expect(AgentNotConnectedException.class);
        downstreamService.matchingClassNames("a", "b", 3);
    }

    @Test
    public void shouldThrowAgentNotConnectExceptionOnMatchingMethodNames() throws Exception {
        thrown.expect(AgentNotConnectedException.class);
        downstreamService.matchingMethodNames("a", "b", "c", 4);
    }

    @Test
    public void shouldThrowAgentNotConnectExceptionOnMethodSignatures() throws Exception {
        thrown.expect(AgentNotConnectedException.class);
        downstreamService.methodSignatures("a", "b", "c");
    }

    @Test
    public void shouldThrowAgentNotConnectExceptionOnReweave() throws Exception {
        thrown.expect(AgentNotConnectedException.class);
        downstreamService.reweave("a");
    }

    @Test
    public void shouldThrowAgentNotConnectExceptionOnGetHeader() throws Exception {
        thrown.expect(AgentNotConnectedException.class);
        downstreamService.getHeader("a", "dummy");
    }

    @Test
    public void shouldThrowAgentNotConnectExceptionOnGetEntries() throws Exception {
        thrown.expect(AgentNotConnectedException.class);
        downstreamService.getEntries("a", "dummy");
    }

    @Test
    public void shouldThrowAgentNotConnectExceptionOngetMainThreadProfile() throws Exception {
        thrown.expect(AgentNotConnectedException.class);
        downstreamService.getMainThreadProfile("a", "dummy");
    }

    @Test
    public void shouldThrowAgentNotConnectExceptionOnGetAuxThreadProfile() throws Exception {
        thrown.expect(AgentNotConnectedException.class);
        downstreamService.getAuxThreadProfile("a", "dummy");
    }

    @Test
    public void shouldThrowAgentNotConnectExceptionOnGetFullTrace() throws Exception {
        thrown.expect(AgentNotConnectedException.class);
        downstreamService.getFullTrace("a", "dummy");
    }
}
