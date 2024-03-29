/*
 * Copyright 2013-2018 the original author or authors.
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

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadInfo;
import java.lang.management.ThreadMXBean;

import javax.annotation.concurrent.GuardedBy;

import org.checkerframework.checker.nullness.qual.MonotonicNonNull;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.checkerframework.checker.nullness.qual.RequiresNonNull;

import org.glowroot.agent.util.ThreadAllocatedBytes;

import static com.google.common.base.Preconditions.checkNotNull;

public class ThreadStatsComponent {

    private static final ThreadMXBean threadMXBean = ManagementFactory.getThreadMXBean();
    private static final boolean IS_THREAD_CPU_TIME_SUPPORTED =
            threadMXBean.isThreadCpuTimeSupported();
    private static final boolean IS_THREAD_CONTENTION_MONITORING_SUPPORTED =
            threadMXBean.isThreadContentionMonitoringSupported();

    private final long threadId;
    private final long startingCpuNanos;
    private final long startingBlockedMillis;
    private final long startingWaitedMillis;
    private final long startingAllocatedBytes;
    private final boolean isVirtualThread;

    private final @Nullable ThreadAllocatedBytes threadAllocatedBytes;

    @GuardedBy("lock")
    private volatile @MonotonicNonNull ThreadStats completedThreadStats;

    private final Object lock = new Object();

    public ThreadStatsComponent(@Nullable ThreadAllocatedBytes threadAllocatedBytes) {
        threadId = Thread.currentThread().getId();
        ThreadInfo threadInfo = threadMXBean.getThreadInfo(threadId, 0);
        // thread info for current thread cannot be null
        isVirtualThread = threadInfo == null;
        if (IS_THREAD_CPU_TIME_SUPPORTED) {
            startingCpuNanos = threadMXBean.getCurrentThreadCpuTime();
        } else {
            startingCpuNanos = -1;
        }
        if (IS_THREAD_CONTENTION_MONITORING_SUPPORTED && !isVirtualThread) {
            startingBlockedMillis = threadInfo.getBlockedTime();
            startingWaitedMillis = threadInfo.getWaitedTime();
        } else {
            startingBlockedMillis = -1;
            startingWaitedMillis = -1;
        }
        if (threadAllocatedBytes != null) {
            startingAllocatedBytes = threadAllocatedBytes.getThreadAllocatedBytesSafely(threadId);
        } else {
            startingAllocatedBytes = -1;
        }
        this.threadAllocatedBytes = threadAllocatedBytes;
    }

    // must be called from transaction thread
    public void onComplete() {
        synchronized (lock) {
            completedThreadStats = getThreadStats();
        }
    }

    // safe to be called from another thread
    public ThreadStats getThreadStats() {
        synchronized (lock) {
            if (completedThreadStats == null) {
                // transaction thread is still alive (and cannot terminate in the middle of this
                // method because of above lock), so safe to capture ThreadMXBean.getThreadInfo()
                // and ThreadMXBean.getThreadCpuTime() for the transaction thread
                return getThreadStatsInternal();
            } else {
                return completedThreadStats;
            }
        }
    }

    // safe to be called from another thread
    public long getCpuNanos() {
        synchronized (lock) {
            if (completedThreadStats == null) {
                // transaction thread is still alive (and cannot terminate in the middle of this
                // method because of above lock), so safe to capture ThreadMXBean.getThreadCpuTime()
                // for the transaction thread
                if (IS_THREAD_CPU_TIME_SUPPORTED) {
                    return getCpuNanosInternal();
                } else {
                    return -1;
                }
            } else {
                return completedThreadStats.getCpuNanos();
            }
        }
    }

    private ThreadStats getThreadStatsInternal() {
        long cpuNanos;
        if (IS_THREAD_CPU_TIME_SUPPORTED) {
            cpuNanos = getCpuNanosInternal();
        } else {
            cpuNanos = -1;
        }
        long blockedMillis;
        long waitedMillis;
        ThreadInfo threadInfo = threadMXBean.getThreadInfo(threadId, 0);
        if (threadInfo == null && !isVirtualThread) {
            // thread must have just recently terminated
            return new ThreadStats(0, 0, 0, 0);
        }
        if (IS_THREAD_CONTENTION_MONITORING_SUPPORTED && !isVirtualThread) {
            waitedMillis = getWaitedMillisInternal(threadInfo);
            blockedMillis = getBlockedMillisInternal(threadInfo);
        } else {
            blockedMillis = -1;
            waitedMillis = -1;
        }
        long allocatedBytes;
        if (threadAllocatedBytes != null) {
            allocatedBytes = getAllocatedBytesInternal();
        } else {
            allocatedBytes = -1;
        }
        return new ThreadStats(cpuNanos, blockedMillis, waitedMillis, allocatedBytes);
    }

    private long getCpuNanosInternal() {
        // getThreadCpuTime() returns -1 if CPU time measurement is disabled (which is different
        // than whether or not it is supported)
        long threadCpuNanos = threadMXBean.getThreadCpuTime(threadId);
        if (startingCpuNanos != -1 && threadCpuNanos != -1) {
            return threadCpuNanos - startingCpuNanos;
        } else {
            return -1;
        }
    }

    private long getBlockedMillisInternal(ThreadInfo threadInfo) {
        // getBlockedTime() return -1 if thread contention monitoring is disabled (which is
        // different than whether or not it is supported)
        long threadBlockedTimeMillis = threadInfo.getBlockedTime();
        if (startingBlockedMillis != -1 && threadBlockedTimeMillis != -1) {
            return threadBlockedTimeMillis - startingBlockedMillis;
        } else {
            return -1;
        }
    }

    private long getWaitedMillisInternal(ThreadInfo threadInfo) {
        // getWaitedTime() returns -1 if thread contention monitoring is disabled (which is
        // different than whether or not it is supported)
        long threadWaitedTimeMillis = threadInfo.getWaitedTime();
        if (startingWaitedMillis != -1 && threadWaitedTimeMillis != -1) {
            return threadWaitedTimeMillis - startingWaitedMillis;
        } else {
            return -1;
        }
    }

    @RequiresNonNull("threadAllocatedBytes")
    private long getAllocatedBytesInternal() {
        long allocatedBytes = threadAllocatedBytes.getThreadAllocatedBytesSafely(threadId);
        if (startingAllocatedBytes != -1 && allocatedBytes != -1) {
            return allocatedBytes - startingAllocatedBytes;
        } else {
            return -1;
        }
    }
}
