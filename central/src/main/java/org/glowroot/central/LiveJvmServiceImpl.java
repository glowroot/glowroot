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
package org.glowroot.central;

import java.util.List;
import java.util.Map;

import org.glowroot.common.live.LiveJvmService;

public class LiveJvmServiceImpl implements LiveJvmService {

    @Override
    public Map<String, MBeanTreeInnerNode> getMBeanTree(MBeanTreeRequest request) {
        throw new UnsupportedOperationException();
    }

    @Override
    public Map<String, /*@Nullable*/Object> getMBeanSortedAttributeMap(String serverId,
            String objectName) {
        throw new UnsupportedOperationException();
    }

    @Override
    public List<String> getMatchingMBeanObjectNames(String serverId, String partialMBeanObjectName,
            int limit) {
        throw new UnsupportedOperationException();
    }

    @Override
    public MBeanMeta getMBeanMeta(String serverId, String mbeanObjectName) {
        throw new UnsupportedOperationException();
    }

    @Override
    public AllThreads getAllThreads() {
        throw new UnsupportedOperationException();
    }

    @Override
    public String getHeapDumpDefaultDirectory(String serverId) {
        throw new UnsupportedOperationException();
    }

    @Override
    public long getAvailableDiskSpace(String serverId, String directory) {
        throw new UnsupportedOperationException();
    }

    @Override
    public HeapFile dumpHeap(String serverId, String directory) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void gc() {
        throw new UnsupportedOperationException();
    }

    @Override
    public Capabilities getCapabilities(String serverId) {
        throw new UnsupportedOperationException();
    }
}
