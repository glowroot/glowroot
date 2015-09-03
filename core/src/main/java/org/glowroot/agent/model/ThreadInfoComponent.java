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

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadInfo;
import java.lang.management.ThreadMXBean;

import javax.annotation.Nullable;
import javax.annotation.concurrent.GuardedBy;

import org.checkerframework.checker.nullness.qual.MonotonicNonNull;
import org.checkerframework.checker.nullness.qual.RequiresNonNull;
import org.immutables.value.Value;

import org.glowroot.agent.util.ThreadAllocatedBytes;
import org.glowroot.collector.spi.Constants;
import org.glowroot.common.util.Styles;

import static com.google.common.base.Preconditions.checkNotNull;
import static java.util.concurrent.TimeUnit.MILLISECONDS;

class ThreadInfoComponent {

    private static final ThreadMXBean threadMXBean = ManagementFactory.getThreadMXBean();
    private static final boolean IS_THREAD_CPU_TIME_SUPPORTED =
            threadMXBean.isThreadCpuTimeSupported();
    private static final boolean IS_THREAD_CONTENTION_MONITORING_SUPPORTED =
            threadMXBean.isThreadContentionMonitoringSupported();

    private final long threadId;
    private final ThreadInfoSnapshot startingSnapshot;

    private final @Nullable ThreadAllocatedBytes threadAllocatedBytes;

    @GuardedBy("lock")
    private volatile @MonotonicNonNull ThreadInfoData completedThreadInfo;

    private final Object lock = new Object();

    ThreadInfoComponent(@Nullable ThreadAllocatedBytes threadAllocatedBytes) {
        threadId = Thread.currentThread().getId();
        ThreadInfo threadInfo = threadMXBean.getThreadInfo(threadId, 0);
        // thread info for current thread cannot be null
        checkNotNull(threadInfo);
        ThreadInfoSnapshotBuilder builder = new ThreadInfoSnapshotBuilder();
        if (IS_THREAD_CPU_TIME_SUPPORTED) {
            builder.threadCpuNanos(threadMXBean.getCurrentThreadCpuTime());
        }
        if (IS_THREAD_CONTENTION_MONITORING_SUPPORTED) {
            builder.threadBlockedMillis(threadInfo.getBlockedTime());
            builder.threadWaitedMillis(threadInfo.getWaitedTime());
        }
        if (threadAllocatedBytes != null) {
            builder.threadAllocatedBytes(
                    threadAllocatedBytes.getThreadAllocatedBytesSafely(threadId));
        }
        this.threadAllocatedBytes = threadAllocatedBytes;
        startingSnapshot = builder.build();
    }

    // must be called from transaction thread
    void onComplete() {
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
        ThreadInfoDataBuilder builder = new ThreadInfoDataBuilder();
        ThreadInfo threadInfo = threadMXBean.getThreadInfo(threadId, 0);
        if (threadInfo == null) {
            // thread must have just recently terminated
            return builder.build();
        }
        if (IS_THREAD_CPU_TIME_SUPPORTED) {
            addThreadCpuTime(builder);
        }
        if (IS_THREAD_CONTENTION_MONITORING_SUPPORTED) {
            addThreadBlockedAndWaitedTime(builder, threadInfo);
        }
        if (threadAllocatedBytes != null) {
            addThreadAllocatedBytes(builder);
        }
        return builder.build();
    }

    private void addThreadCpuTime(ThreadInfoDataBuilder builder) {
        // getThreadCpuTime() returns -1 if CPU time measurement is disabled (which is different
        // than whether or not it is supported)
        long threadCpuNanos = threadMXBean.getThreadCpuTime(threadId);
        if (startingSnapshot.threadCpuNanos() != -1 && threadCpuNanos != -1) {
            builder.threadCpuNanos(threadCpuNanos - startingSnapshot.threadCpuNanos());
        }
    }

    private void addThreadBlockedAndWaitedTime(ThreadInfoDataBuilder builder,
            ThreadInfo threadInfo) {
        // getBlockedTime() and getWaitedTime() return -1 if thread contention monitoring is
        // disabled (which is different than whether or not it is supported)
        long threadBlockedTimeMillis = threadInfo.getBlockedTime();
        if (startingSnapshot.threadBlockedMillis() != -1 && threadBlockedTimeMillis != -1) {
            builder.threadBlockedNanos(MILLISECONDS
                    .toNanos(threadBlockedTimeMillis - startingSnapshot.threadBlockedMillis()));
        }
        long threadWaitedTimeMillis = threadInfo.getWaitedTime();
        if (startingSnapshot.threadWaitedMillis() != -1 && threadWaitedTimeMillis != -1) {
            builder.threadWaitedNanos(MILLISECONDS
                    .toNanos(threadWaitedTimeMillis - startingSnapshot.threadWaitedMillis()));
        }
    }

    @RequiresNonNull("threadAllocatedBytes")
    private void addThreadAllocatedBytes(ThreadInfoDataBuilder builder) {
        long allocatedBytes = threadAllocatedBytes.getThreadAllocatedBytesSafely(threadId);
        if (startingSnapshot.threadAllocatedBytes() != -1 && allocatedBytes != -1) {
            builder.threadAllocatedBytes(allocatedBytes - startingSnapshot.threadAllocatedBytes());
        }
    }

    @Styles.Private
    @Value.Immutable
    abstract static class ThreadInfoSnapshot {
        @Value.Default
        long threadCpuNanos() {
            return -1;
        }
        @Value.Default
        long threadBlockedMillis() { // milliseconds (native resolution from jvm)
            return -1;
        }
        @Value.Default
        long threadWaitedMillis() { // milliseconds (native resolution from jvm)
            return -1;
        }
        @Value.Default
        long threadAllocatedBytes() {
            return -1;
        }
    }

    @Styles.Private
    @Value.Immutable
    abstract static class ThreadInfoData {

        @Value.Default
        public long threadCpuNanos() {
            return Constants.THREAD_DATA_NOT_AVAILABLE;
        }
        @Value.Default
        public long threadBlockedNanos() {
            return Constants.THREAD_DATA_NOT_AVAILABLE;
        }
        @Value.Default
        public long threadWaitedNanos() {
            return Constants.THREAD_DATA_NOT_AVAILABLE;
        }
        @Value.Default
        public long threadAllocatedBytes() {
            return Constants.THREAD_DATA_NOT_AVAILABLE;
        }
    }
}
