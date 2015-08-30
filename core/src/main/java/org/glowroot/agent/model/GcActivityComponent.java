/*
 * Copyright 2013-2015 the original author or authors.
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
package org.glowroot.agent.model;

import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.annotation.concurrent.GuardedBy;

import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;
import org.checkerframework.checker.nullness.qual.MonotonicNonNull;
import org.immutables.value.Value;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.glowroot.collector.spi.GarbageCollectorActivity;

class GcActivityComponent {

    private static final Logger logger = LoggerFactory.getLogger(GcActivityComponent.class);

    private final Map<String, GcSnapshot> startingSnapshots;

    @GuardedBy("lock")
    private volatile @MonotonicNonNull List<GarbageCollectorActivity> completedGcActivity;

    private final Object lock = new Object();

    GcActivityComponent() {
        List<GarbageCollectorMXBean> gcBeans = ManagementFactory.getGarbageCollectorMXBeans();
        startingSnapshots = Maps.newHashMap();
        for (GarbageCollectorMXBean gcBean : gcBeans) {
            GcSnapshot info = ImmutableGcSnapshot.builder()
                    .collectionCount(gcBean.getCollectionCount())
                    .collectionTime(gcBean.getCollectionTime())
                    .build();
            startingSnapshots.put(gcBean.getName(), info);
        }
    }

    // must be called from transaction thread
    void onComplete() {
        synchronized (lock) {
            completedGcActivity = getGcActivity();
        }
    }

    // safe to be called from another thread
    List<GarbageCollectorActivity> getGcActivity() {
        synchronized (lock) {
            if (completedGcActivity == null) {
                // transaction thread is still alive (and cannot terminate in the middle of this
                // method because of above lock), so safe to capture ThreadMXBean.getThreadInfo()
                // and ThreadMXBean.getThreadCpuTime() for the transaction thread
                return getGcActivityInternal();
            } else {
                return completedGcActivity;
            }
        }
    }

    private List<GarbageCollectorActivity> getGcActivityInternal() {
        Set<String> unmatchedNames = Sets.newHashSet(startingSnapshots.keySet());
        List<GarbageCollectorMXBean> gcBeans = ManagementFactory.getGarbageCollectorMXBeans();
        List<GarbageCollectorActivity> gcActivity = Lists.newArrayList();
        for (GarbageCollectorMXBean gcBean : gcBeans) {
            String name = gcBean.getName();
            GcSnapshot gcSnapshot = startingSnapshots.get(name);
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
            gcActivity.add(ImmutableXGarbageCollectionActivity.builder()
                    .collectorName(name)
                    .collectionCount(collectionCountEnd - gcSnapshot.collectionCount())
                    .collectionTimeMillis(collectionTimeEnd - gcSnapshot.collectionTime())
                    .build());
        }
        for (String unmatchedName : unmatchedNames) {
            logger.warn("garbage collector bean {} did not exist at end of trace", unmatchedName);
        }
        return gcActivity;
    }

    @Value.Immutable
    interface GcSnapshot {
        long collectionCount();
        long collectionTime();
    }

    // TODO use @Value.Include for this
    @Value.Immutable
    interface XGarbageCollectionActivity extends GarbageCollectorActivity {}
}
