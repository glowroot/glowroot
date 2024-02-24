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
package org.glowroot.central;

import com.google.common.collect.ImmutableList;
import org.glowroot.central.util.ClusterManager;
import org.glowroot.common.live.LiveJvmService.AgentNotConnectedException;
import org.glowroot.wire.api.model.AgentConfigOuterClass.AgentConfig;
import org.glowroot.wire.api.model.DownstreamServiceOuterClass.MBeanDumpRequest.MBeanDumpKind;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;

public class DownstreamServiceNotConnectedTest {

    private static ClusterManager clusterManager;

    private DownstreamServiceImpl downstreamService =
            new DownstreamServiceImpl(mock(GrpcCommon.class), clusterManager);

    @BeforeAll
    public static void setUp() throws Exception {
        clusterManager = ClusterManager.create();
    }

    @AfterAll
    public static void tearDown() throws Exception {
        clusterManager.close();
    }

    @Test
    public void shouldNotThrowAgentNotConnectExceptionOnUpdateAgentConfig() throws Exception {
        downstreamService.updateAgentConfigIfConnected("a", AgentConfig.getDefaultInstance());
    }

    @Test
    public void shouldThrowAgentNotConnectExceptionOnThreadDump() {
        assertThrows(AgentNotConnectedException.class, () ->
            downstreamService.threadDump("a"));
    }

    @Test
    public void shouldThrowAgentNotConnectExceptionOnAvailableDiskSpaceBytes() {
        assertThrows(AgentNotConnectedException.class, () ->
            downstreamService.availableDiskSpaceBytes("a", "dummy"));
    }

    @Test
    public void shouldThrowAgentNotConnectExceptionOnHeapDump() {
        assertThrows(AgentNotConnectedException.class, () ->
            downstreamService.heapDump("a", "dummy"));
    }

    @Test
    public void shouldThrowAgentNotConnectExceptionOnExplicitGcDisabled() {
        assertThrows(AgentNotConnectedException.class, () ->
            downstreamService.isExplicitGcDisabled("a"));
    }

    @Test
    public void shouldThrowAgentNotConnectExceptionOnGc() {
        assertThrows(AgentNotConnectedException.class, () ->
            downstreamService.forceGC("a"));
    }

    @Test
    public void shouldThrowAgentNotConnectExceptionOnMbeanDump() {
        assertThrows(AgentNotConnectedException.class, () ->
            downstreamService.mbeanDump("a", MBeanDumpKind.ALL_MBEANS_INCLUDE_ATTRIBUTES,
                    ImmutableList.of()));
    }

    @Test
    public void shouldThrowAgentNotConnectExceptionOnMatchingMBeanObjectNames() {
        assertThrows(AgentNotConnectedException.class, () ->
            downstreamService.matchingMBeanObjectNames("a", "b", 3));
    }

    @Test
    public void shouldThrowAgentNotConnectExceptionOnMbeanMeta() {
        assertThrows(AgentNotConnectedException.class, () ->
            downstreamService.mbeanMeta("a", "dummy"));
    }

    @Test
    public void shouldThrowAgentNotConnectExceptionOnCapabilities() {
        assertThrows(AgentNotConnectedException.class, () ->
            downstreamService.capabilities("a"));
    }

    @Test
    public void shouldThrowAgentNotConnectExceptionOnGlobalMeta() {
        assertThrows(AgentNotConnectedException.class, () ->
            downstreamService.globalMeta("a"));
    }

    @Test
    public void shouldThrowAgentNotConnectExceptionOnPreloadClasspathCache() {
        assertThrows(AgentNotConnectedException.class, () ->
            downstreamService.preloadClasspathCache("a"));
    }

    @Test
    public void shouldThrowAgentNotConnectExceptionOnMatchingClassNames() {
        assertThrows(AgentNotConnectedException.class, () ->
            downstreamService.matchingClassNames("a", "b", 3));
    }

    @Test
    public void shouldThrowAgentNotConnectExceptionOnMatchingMethodNames() {
        assertThrows(AgentNotConnectedException.class, () ->
            downstreamService.matchingMethodNames("a", "b", "c", 4));
    }

    @Test
    public void shouldThrowAgentNotConnectExceptionOnMethodSignatures() {
        assertThrows(AgentNotConnectedException.class, () ->
            downstreamService.methodSignatures("a", "b", "c"));
    }

    @Test
    public void shouldThrowAgentNotConnectExceptionOnReweave() {
        assertThrows(AgentNotConnectedException.class, () ->
            downstreamService.reweave("a"));
    }

    @Test
    public void shouldThrowAgentNotConnectExceptionOnGetHeader() {
        assertThrows(AgentNotConnectedException.class, () ->
            downstreamService.getHeader("a", "dummy"));
    }

    @Test
    public void shouldThrowAgentNotConnectExceptionOnGetEntries() {
        assertThrows(AgentNotConnectedException.class, () ->
            downstreamService.getEntries("a", "dummy"));
    }

    @Test
    public void shouldThrowAgentNotConnectExceptionOngetMainThreadProfile() {
        assertThrows(AgentNotConnectedException.class, () ->
            downstreamService.getMainThreadProfile("a", "dummy"));
    }

    @Test
    public void shouldThrowAgentNotConnectExceptionOnGetAuxThreadProfile() {
        assertThrows(AgentNotConnectedException.class, () ->
            downstreamService.getAuxThreadProfile("a", "dummy"));
    }

    @Test
    public void shouldThrowAgentNotConnectExceptionOnGetFullTrace() {
        assertThrows(AgentNotConnectedException.class, () ->
            downstreamService.getFullTrace("a", "dummy"));
    }
}
