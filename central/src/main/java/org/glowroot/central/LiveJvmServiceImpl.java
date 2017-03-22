/*
 * Copyright 2015-2017 the original author or authors.
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

import java.util.List;
import java.util.Map;

import org.glowroot.common.live.LiveJvmService;
import org.glowroot.wire.api.model.DownstreamServiceOuterClass.Capabilities;
import org.glowroot.wire.api.model.DownstreamServiceOuterClass.HeapDumpFileInfo;
import org.glowroot.wire.api.model.DownstreamServiceOuterClass.HeapHistogram;
import org.glowroot.wire.api.model.DownstreamServiceOuterClass.MBeanDump;
import org.glowroot.wire.api.model.DownstreamServiceOuterClass.MBeanDumpRequest.MBeanDumpKind;
import org.glowroot.wire.api.model.DownstreamServiceOuterClass.MBeanMeta;
import org.glowroot.wire.api.model.DownstreamServiceOuterClass.ThreadDump;

class LiveJvmServiceImpl implements LiveJvmService {

    private final DownstreamServiceImpl downstreamService;

    LiveJvmServiceImpl(DownstreamServiceImpl downstreamService) {
        this.downstreamService = downstreamService;
    }

    @Override
    public boolean isAvailable(String agentId) throws Exception {
        return downstreamService.isAvailable(agentId);
    }

    @Override
    public ThreadDump getThreadDump(String agentId) throws Exception {
        return downstreamService.threadDump(agentId);
    }

    @Override
    public String getJstack(String agentId) throws Exception {
        return downstreamService.jstack(agentId);
    }

    @Override
    public long getAvailableDiskSpace(String agentId, String directory) throws Exception {
        return downstreamService.availableDiskSpaceBytes(agentId, directory);
    }

    @Override
    public HeapDumpFileInfo heapDump(String agentId, String directory) throws Exception {
        return downstreamService.heapDump(agentId, directory);
    }

    @Override
    public HeapHistogram heapHistogram(String agentId) throws Exception {
        return downstreamService.heapHistogram(agentId);
    }

    @Override
    public void gc(String agentId) throws Exception {
        downstreamService.gc(agentId);
    }

    @Override
    public MBeanDump getMBeanDump(String agentId, MBeanDumpKind mbeanDumpKind,
            List<String> objectNames) throws Exception {
        return downstreamService.mbeanDump(agentId, mbeanDumpKind, objectNames);
    }

    @Override
    public List<String> getMatchingMBeanObjectNames(String agentId, String partialObjectName,
            int limit) throws Exception {
        return downstreamService.matchingMBeanObjectNames(agentId, partialObjectName, limit);
    }

    @Override
    public MBeanMeta getMBeanMeta(String agentId, String objectName) throws Exception {
        return downstreamService.mbeanMeta(agentId, objectName);
    }

    @Override
    public Map<String, String> getSystemProperties(String agentId) throws Exception {
        return downstreamService.systemProperties(agentId);
    }

    @Override
    public Capabilities getCapabilities(String agentId) throws Exception {
        return downstreamService.capabilities(agentId);
    }
}
