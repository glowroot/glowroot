/*
 * Copyright 2015-2016 the original author or authors.
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

import org.glowroot.common.live.LiveJvmService;
import org.glowroot.wire.api.model.DownstreamServiceOuterClass.Capabilities;
import org.glowroot.wire.api.model.DownstreamServiceOuterClass.HeapDumpFileInfo;
import org.glowroot.wire.api.model.DownstreamServiceOuterClass.MBeanDump;
import org.glowroot.wire.api.model.DownstreamServiceOuterClass.MBeanDumpKind;
import org.glowroot.wire.api.model.DownstreamServiceOuterClass.MBeanMeta;
import org.glowroot.wire.api.model.DownstreamServiceOuterClass.ThreadDump;

class LiveJvmServiceImpl implements LiveJvmService {

    private final DownstreamServiceImpl downstreamService;

    LiveJvmServiceImpl(DownstreamServiceImpl downstreamService) {
        this.downstreamService = downstreamService;
    }

    @Override
    public boolean isAvailable(String serverId) {
        return downstreamService.isAvailable(serverId);
    }

    @Override
    public ThreadDump getThreadDump(String serverId) throws Exception {
        return downstreamService.threadDump(serverId);
    }

    @Override
    public long getAvailableDiskSpace(String serverId, String directory) throws Exception {
        return downstreamService.availableDiskSpaceBytes(serverId, directory);
    }

    @Override
    public HeapDumpFileInfo heapDump(String serverId, String directory) throws Exception {
        return downstreamService.heapDump(serverId, directory);
    }

    @Override
    public void gc(String serverId) throws Exception {
        downstreamService.gc(serverId);
    }

    @Override
    public MBeanDump getMBeanDump(String serverId, MBeanDumpKind mbeanDumpKind,
            List<String> objectNames) throws Exception {
        return downstreamService.mbeanDump(serverId, mbeanDumpKind, objectNames);
    }

    @Override
    public List<String> getMatchingMBeanObjectNames(String serverId, String partialObjectName,
            int limit) throws Exception {
        return downstreamService.matchingMBeanObjectNames(serverId, partialObjectName, limit);
    }

    @Override
    public MBeanMeta getMBeanMeta(String serverId, String objectName) throws Exception {
        return downstreamService.mbeanMeta(serverId, objectName);
    }

    @Override
    public Capabilities getCapabilities(String serverId) throws Exception {
        return downstreamService.capabilities(serverId);
    }
}
