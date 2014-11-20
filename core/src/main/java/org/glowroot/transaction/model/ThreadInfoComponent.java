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

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadInfo;
import java.lang.management.ThreadMXBean;

import javax.annotation.Nullable;
import javax.annotation.concurrent.GuardedBy;

import com.google.common.base.MoreObjects;
import org.checkerframework.checker.nullness.qual.MonotonicNonNull;
import org.immutables.value.Json;
import org.immutables.value.Value;

import org.glowroot.jvm.ThreadAllocatedBytes;

import static com.google.common.base.Preconditions.checkNotNull;

public class ThreadInfoComponent {

    private static final ThreadMXBean threadMXBean = ManagementFactory.getThreadMXBean();
    private static final boolean isThreadCpuTimeSupported = threadMXBean.isThreadCpuTimeSupported();
    private static final boolean isThreadContentionMonitoringSupported =
            threadMXBean.isThreadContentionMonitoringSupported();

    private final long threadId;
    private final ThreadInfoSnapshot startingSnapshot;

    private final @Nullable ThreadAllocatedBytes threadAllocatedBytes;

    @GuardedBy("lock")
    private volatile @MonotonicNonNull ThreadInfoData completedThreadInfo;

    private final Object lock = new Object();

    public ThreadInfoComponent(@Nullable ThreadAllocatedBytes threadAllocatedBytes) {
        threadId = Thread.currentThread().getId();
        ThreadInfo threadInfo = threadMXBean.getThreadInfo(threadId, 0);
        // thread info for current thread cannot be null
        checkNotNull(threadInfo);
        ImmutableThreadInfoSnapshot.Builder builder = ImmutableThreadInfoSnapshot.builder();
        if (isThreadCpuTimeSupported) {
            builder.threadCpuTime(threadMXBean.getCurrentThreadCpuTime());
        }
        if (isThreadContentionMonitoringSupported) {
            builder.threadBlockedTime(threadInfo.getBlockedTime());
            builder.threadWaitedTime(threadInfo.getWaitedTime());
        }
        if (threadAllocatedBytes != null) {
            builder.threadAllocatedBytes(
                    threadAllocatedBytes.getThreadAllocatedBytesSafely(threadId));
        }
        this.threadAllocatedBytes = threadAllocatedBytes;
        startingSnapshot = builder.build();
    }

    // must be called from transaction thread
    void onTraceComplete() {
        synchronized (lock) {
            completedThreadInfo = getThreadInfo();
        }
    }

    // safe to be called from another thread
    ThreadInfoData getThreadInfo() {
        synchronized (lock) {
            if (completedThreadInfo == null) {
                // transaction thread is still alive (and cannot terminate in the middle of this
                // method because of above lock), so safe to capture ThreadMXBean.getThreadInfo()
                // and ThreadMXBean.getThreadCpuTime() for the transaction thread
                return getThreadInfoInternal();
            } else {
                return completedThreadInfo;
            }
        }
    }

    private ThreadInfoData getThreadInfoInternal() {
        ImmutableThreadInfoData.Builder builder = ImmutableThreadInfoData.builder();
        ThreadInfo threadInfo = threadMXBean.getThreadInfo(threadId, 0);
        if (threadInfo == null) {
            // thread must have just recently terminated
            return builder.build();
        }
        if (isThreadCpuTimeSupported) {
            // getThreadCpuTime() returns -1 if CPU time measurement is disabled (which is different
            // than whether or not it is supported)
            long threadCpuTime = threadMXBean.getThreadCpuTime(threadId);
            if (startingSnapshot.threadCpuTime() != -1 && threadCpuTime != -1) {
                builder.threadCpuTime(threadCpuTime - startingSnapshot.threadCpuTime());
            }
        }
        if (isThreadContentionMonitoringSupported) {
            // getBlockedTime() and getWaitedTime() return -1 if thread contention monitoring is
            // disabled (which is different than whether or not it is supported)
            long threadBlockedTime = threadInfo.getBlockedTime();
            long threadWaitedTime = threadInfo.getWaitedTime();
            if (startingSnapshot.threadBlockedTime() != -1 && threadBlockedTime != -1) {
                builder.threadBlockedTime(threadBlockedTime - startingSnapshot.threadBlockedTime());
            }
            if (startingSnapshot.threadWaitedTime() != -1 && threadWaitedTime != -1) {
                builder.threadWaitedTime(threadWaitedTime - startingSnapshot.threadWaitedTime());
            }
        }
        if (threadAllocatedBytes != null) {
            long allocatedBytes = threadAllocatedBytes.getThreadAllocatedBytesSafely(threadId);
            if (startingSnapshot.threadAllocatedBytes() != -1 && allocatedBytes != -1) {
                builder.threadAllocatedBytes(
                        allocatedBytes - startingSnapshot.threadAllocatedBytes());
            }
        }
        return builder.build();
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
                .add("threadId", threadId)
                .add("startingSnapshot", startingSnapshot)
                .toString();
    }

    @Value.Immutable
    @Json.Marshaled
    abstract static class ThreadInfoSnapshot {
        @Value.Default
        long threadCpuTime() {
            return -1;
        }
        @Value.Default
        long threadBlockedTime() {
            return -1;
        }
        @Value.Default
        long threadWaitedTime() {
            return -1;
        }
        @Value.Default
        long threadAllocatedBytes() {
            return -1;
        }
    }

    @Value.Immutable
    @Json.Marshaled
    public abstract static class ThreadInfoData {
        abstract @Nullable Long threadCpuTime();
        abstract @Nullable Long threadBlockedTime();
        abstract @Nullable Long threadWaitedTime();
        abstract @Nullable Long threadAllocatedBytes();
    }
}
