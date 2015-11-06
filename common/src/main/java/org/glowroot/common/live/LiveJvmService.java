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

import java.io.IOException;
import java.util.List;
import java.util.Map;

import javax.annotation.Nullable;

import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.Ordering;
import org.immutables.value.Value;

import org.glowroot.common.util.Styles;
import org.glowroot.common.util.UsedByJsonSerialization;

public interface LiveJvmService {

    Map<String, MBeanTreeInnerNode> getMBeanTree(MBeanTreeRequest request) throws Exception;

    Map<String, /*@Nullable*/Object> getMBeanSortedAttributeMap(String serverId, String objectName)
            throws Exception;

    List<String> getMatchingMBeanObjectNames(String serverId, String partialMBeanObjectName,
            int limit) throws InterruptedException;

    MBeanMeta getMBeanMeta(String serverId, String mbeanObjectName) throws Exception;

    AllThreads getAllThreads();

    String getHeapDumpDefaultDirectory(String serverId);

    long getAvailableDiskSpace(String serverId, String directory) throws IOException;

    HeapFile dumpHeap(String serverId, String directory) throws Exception;

    void gc();

    Capabilities getCapabilities(String serverId);

    @Value.Immutable
    interface MBeanTreeRequest {
        String serverId();
        List<String> expanded();
    }

    interface MBeanTreeNode {
        String getNodeName();
    }

    static class MBeanTreeInnerNode implements MBeanTreeNode {

        private static final Ordering<MBeanTreeNode> ordering = new Ordering<MBeanTreeNode>() {
            @Override
            public int compare(MBeanTreeNode left, MBeanTreeNode right) {
                return left.getNodeName().compareToIgnoreCase(right.getNodeName());
            }
        };

        private final String name;

        // not using Map here since its possible for multiple leafs with same name
        // e.g. d:type=Foo,name=Bar and d:type=Foo,nonsense=Bar
        // both translate to a leaf named Bar under d/Foo
        private final List<MBeanTreeNode> childNodes = Lists.newArrayList();

        private final Map<String, MBeanTreeInnerNode> innerNodes = Maps.newHashMap();

        public MBeanTreeInnerNode(String name) {
            this.name = name;
        }

        @Override
        @UsedByJsonSerialization
        public String getNodeName() {
            return name;
        }

        @UsedByJsonSerialization
        public List<MBeanTreeNode> getChildNodes() {
            return ordering.sortedCopy(childNodes);
        }

        public MBeanTreeInnerNode getOrCreateNode(String name) {
            MBeanTreeInnerNode innerNode = innerNodes.get(name);
            if (innerNode == null) {
                innerNode = new MBeanTreeInnerNode(name);
                innerNodes.put(name, innerNode);
                childNodes.add(innerNode);
            }
            return innerNode;
        }

        public void addLeafNode(MBeanTreeLeafNode leafNode) {
            childNodes.add(leafNode);
        }
    }

    static class MBeanTreeLeafNode implements MBeanTreeNode {

        // nodeName may not be unique
        private final String nodeName;
        private final String objectName;
        private final boolean expanded;
        private final @Nullable Map<String, /*@Nullable*/Object> attributeMap;

        public MBeanTreeLeafNode(String nodeName, String objectName, boolean expanded,
                @Nullable Map<String, /*@Nullable*/Object> attributeMap) {
            this.nodeName = nodeName;
            this.objectName = objectName;
            this.expanded = expanded;
            this.attributeMap = attributeMap;
        }

        @Override
        @UsedByJsonSerialization
        public String getNodeName() {
            return nodeName;
        }

        @UsedByJsonSerialization
        public String getObjectName() {
            return objectName;
        }

        @UsedByJsonSerialization
        public boolean isExpanded() {
            return expanded;
        }

        @UsedByJsonSerialization
        public @Nullable Map<String, /*@Nullable*/Object> getAttributeMap() {
            return attributeMap;
        }
    }

    @Value.Immutable
    @Styles.AllParameters
    interface MBeanMeta {
        boolean unmatched();
        boolean unavailable();
        List<String> attributeNames();
    }

    // TODO improve AllThreads/OneThread class names
    @Value.Immutable
    public interface AllThreads {
        List<OneThread> matchedThreads();
        List<OneThread> unmatchedThreads();
        @Nullable
        OneThread currentThread();
    }

    @Value.Immutable
    public interface OneThread {
        String name();
        String state();
        @Nullable
        String lockName();
        List<String> stackTraceElements();

        @Nullable
        String transactionType();
        @Nullable
        String transactionName();
        @Nullable
        Long transactionTotalNanos();
        @Nullable
        String traceId();
    }

    @Value.Immutable
    @Styles.AllParameters
    interface HeapFile {
        String filename();
        long size();
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
