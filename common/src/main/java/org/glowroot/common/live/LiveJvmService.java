/*
 * Copyright 2015 the original author or authors.
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
package org.glowroot.common.live;

import java.util.List;

import org.immutables.value.Value;

import org.glowroot.common.util.Styles;
import org.glowroot.wire.api.model.DownstreamServiceOuterClass.HeapDumpFileInfo;
import org.glowroot.wire.api.model.DownstreamServiceOuterClass.MBeanDump;
import org.glowroot.wire.api.model.DownstreamServiceOuterClass.MBeanDumpRequest;
import org.glowroot.wire.api.model.DownstreamServiceOuterClass.ThreadDump;

public interface LiveJvmService {

    ThreadDump getThreadDump(String serverId) throws Exception;

    long getAvailableDiskSpace(String serverId, String directory) throws Exception;

    HeapDumpFileInfo heapDump(String serverId, String directory) throws Exception;

    void gc(String serverId) throws Exception;

    MBeanDump getMBeanDump(String serverId, MBeanDumpRequest request) throws Exception;

    List<String> getMatchingMBeanObjectNames(String serverId, String partialMBeanObjectName,
            int limit) throws InterruptedException;

    MBeanMeta getMBeanMeta(String serverId, String mbeanObjectName) throws Exception;

    Capabilities getCapabilities(String serverId);

    @Value.Immutable
    @Styles.AllParameters
    interface MBeanMeta {
        boolean unmatched();
        boolean unavailable();
        List<String> attributeNames();
    }

    @Value.Immutable
    interface Capabilities {
        Availability threadCpuTime();
        Availability threadContentionTime();
        Availability threadAllocatedBytes();
    }

    @Value.Immutable
    @Styles.AllParameters
    interface Availability {
        boolean available();
        // reason only needed when available is false
        String getReason();
    }
}
