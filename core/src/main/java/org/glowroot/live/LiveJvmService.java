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
package org.glowroot.live;

import java.io.IOException;
import java.util.List;
import java.util.Map;

import javax.annotation.Nullable;

import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.Ordering;
import org.immutables.value.Value;

import org.glowroot.common.util.Styles;
import org.glowroot.markers.UsedByJsonBinding;

import static com.google.common.base.Preconditions.checkNotNull;

public interface LiveJvmService {

    Map<String, MBeanTreeInnerNode> getMBeanTree(MBeanTreeRequest request) throws Exception;

    Map<String, /*@Nullable*/Object> getMBeanSortedAttributeMap(String objectName) throws Exception;

    List<String> getMatchingMBeanObjectNames(String partialMBeanObjectName, int limit)
            throws InterruptedException;

    MBeanMeta getMBeanMeta(String mbeanObjectName) throws Exception;

    String getHeapDumpDefaultDirectory();

    long getAvailableDiskSpace(String directory) throws IOException;

    HeapFile dumpHeap(String directory) throws Exception;

    ProcessInfo getProcessInfo();

    Map<String, String> getSystemProperties();

    Capabilities getCapabilities();

    @Value.Immutable
    public interface MBeanTreeRequest {
        List<String> expanded();
    }

    public interface MBeanTreeNode {

        static final Ordering<MBeanTreeNode> ordering = new Ordering<MBeanTreeNode>() {
            @Override
            public int compare(@Nullable MBeanTreeNode left, @Nullable MBeanTreeNode right) {
                checkNotNull(left);
                checkNotNull(right);
                return left.getNodeName().compareToIgnoreCase(right.getNodeName());
            }
        };

        String getNodeName();
    }

    @UsedByJsonBinding
    public static class MBeanTreeInnerNode implements MBeanTreeNode {

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
        public String getNodeName() {
            return name;
        }

        public List<MBeanTreeNode> getChildNodes() {
            return MBeanTreeNode.ordering.sortedCopy(childNodes);
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

    @UsedByJsonBinding
    public static class MBeanTreeLeafNode implements MBeanTreeNode {

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
        public String getNodeName() {
            return nodeName;
        }

        public String getObjectName() {
            return objectName;
        }

        public boolean isExpanded() {
            return expanded;
        }

        public @Nullable Map<String, /*@Nullable*/Object> getAttributeMap() {
            return attributeMap;
        }
    }

    @Value.Immutable
    @Styles.AllParameters
    public interface MBeanMeta {
        boolean unmatched();
        boolean unavailable();
        List<String> attributeNames();
    }

    @Value.Immutable
    @Styles.AllParameters
    public interface HeapFile {
        String filename();
        long size();
    }

    @Value.Immutable
    public interface ProcessInfo {
        long startTime();
        long uptime();
        String pid();
        String mainClass();
        List<String> mainClassArguments();
        String jvm();
        String java();
        String javaHome();
        List<String> jvmArguments();
    }

    @Value.Immutable
    public interface Capabilities {
        Availability threadCpuTime();
        Availability threadContentionTime();
        Availability threadAllocatedBytes();
        Availability heapDump();
    }

    @Value.Immutable
    @Styles.AllParameters
    public interface Availability {
        boolean isAvailable();
        // reason only needed when available is false
        String getReason();
    }
}
