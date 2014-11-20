/*
 * Copyright 2013-2014 the original author or authors.
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
package org.glowroot.transaction.model;

import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.util.List;
import java.util.Set;

import javax.annotation.concurrent.GuardedBy;

import com.google.common.base.MoreObjects;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Lists;
import com.google.common.collect.Sets;
import org.checkerframework.checker.nullness.qual.MonotonicNonNull;
import org.immutables.value.Json;
import org.immutables.value.Value;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class GcInfoComponent {

    private static final Logger logger = LoggerFactory.getLogger(GcInfoComponent.class);

    private final ImmutableMap<String, GcSnapshot> gcSnapshots;

    @GuardedBy("lock")
    private volatile @MonotonicNonNull List<GcInfo> completedGcInfos;

    private final Object lock = new Object();

    public GcInfoComponent() {
        List<GarbageCollectorMXBean> gcBeans = ManagementFactory.getGarbageCollectorMXBeans();
        ImmutableMap.Builder<String, GcSnapshot> infos = ImmutableMap.builder();
        for (GarbageCollectorMXBean gcBean : gcBeans) {
            GcSnapshot info = ImmutableGcSnapshot.builder()
                    .collectionCount(gcBean.getCollectionCount())
                    .collectionTime(gcBean.getCollectionTime())
                    .build();
            infos.put(gcBean.getName(), info);
        }
        this.gcSnapshots = infos.build();
    }

    // must be called from transaction thread
    void onTraceComplete() {
        synchronized (lock) {
            completedGcInfos = getGcInfos();
        }
    }

    // safe to be called from another thread
    List<GcInfo> getGcInfos() {
        synchronized (lock) {
            if (completedGcInfos == null) {
                // transaction thread is still alive (and cannot terminate in the middle of this
                // method because of above lock), so safe to capture ThreadMXBean.getThreadInfo()
                // and ThreadMXBean.getThreadCpuTime() for the transaction thread
                return getGcInfosInternal();
            } else {
                return completedGcInfos;
            }
        }
    }

    private List<GcInfo> getGcInfosInternal() {
        Set<String> unmatchedNames = Sets.newHashSet(gcSnapshots.keySet());
        List<GarbageCollectorMXBean> gcBeans = ManagementFactory.getGarbageCollectorMXBeans();
        List<GcInfo> gcInfos = Lists.newArrayList();
        for (GarbageCollectorMXBean gcBean : gcBeans) {
            String name = gcBean.getName();
            GcSnapshot gcSnapshot = gcSnapshots.get(name);
            if (gcSnapshot == null) {
                logger.warn("garbage collector bean {} did not exist at start of trace", name);
                continue;
            }
            unmatchedNames.remove(name);
            long collectionCountEnd = gcBean.getCollectionCount();
            long collectionTimeEnd = gcBean.getCollectionTime();
            if (collectionCountEnd == gcSnapshot.collectionCount()) {
                // no new collections, so don't write it out
                continue;
            }
            gcInfos.add(ImmutableGcInfo.builder()
                    .name(name)
                    .collectionCount(collectionCountEnd - gcSnapshot.collectionCount())
                    .collectionTime(collectionTimeEnd - gcSnapshot.collectionTime())
                    .build());
        }
        for (String unmatchedName : unmatchedNames) {
            logger.warn("garbage collector bean {} did not exist at end of trace", unmatchedName);
        }
        return gcInfos;
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
                .add("gcSnapshots", gcSnapshots)
                .toString();
    }

    @Value.Immutable
    abstract static class GcSnapshot {
        abstract long collectionCount();
        abstract long collectionTime();
    }

    @Value.Immutable
    @Json.Marshaled
    public abstract static class GcInfo {
        abstract String name();
        abstract long collectionCount();
        abstract long collectionTime();
    }
}
