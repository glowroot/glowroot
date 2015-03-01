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
package org.glowroot.transaction.model;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadInfo;
import java.lang.management.ThreadMXBean;

import javax.annotation.Nullable;
import javax.annotation.concurrent.GuardedBy;

import org.checkerframework.checker.nullness.qual.MonotonicNonNull;
import org.checkerframework.checker.nullness.qual.RequiresNonNull;
import org.immutables.value.Value;

import org.glowroot.jvm.ThreadAllocatedBytes;

import static com.google.common.base.Preconditions.checkNotNull;
import static java.util.concurrent.TimeUnit.MILLISECONDS;

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
            builder.threadBlockedTimeMillis(threadInfo.getBlockedTime());
            builder.threadWaitedTimeMillis(threadInfo.getWaitedTime());
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
            addThreadCpuTime(builder);
        }
        if (isThreadContentionMonitoringSupported) {
            addThreadBlockedAndWaitedTime(builder, threadInfo);
        }
        if (threadAllocatedBytes != null) {
            addThreadAllocatedBytes(builder);
        }
        return builder.build();
    }

    private void addThreadCpuTime(ImmutableThreadInfoData.Builder builder) {
        // getThreadCpuTime() returns -1 if CPU time measurement is disabled (which is different
        // than whether or not it is supported)
        long threadCpuTime = threadMXBean.getThreadCpuTime(threadId);
        if (startingSnapshot.threadCpuTime() != -1 && threadCpuTime != -1) {
            builder.threadCpuTime(threadCpuTime - startingSnapshot.threadCpuTime());
        }
    }

    private void addThreadBlockedAndWaitedTime(ImmutableThreadInfoData.Builder builder,
            ThreadInfo threadInfo) {
        // getBlockedTime() and getWaitedTime() return -1 if thread contention monitoring is
        // disabled (which is different than whether or not it is supported)
        long threadBlockedTimeMillis = threadInfo.getBlockedTime();
        if (startingSnapshot.threadBlockedTimeMillis() != -1 && threadBlockedTimeMillis != -1) {
            builder.threadBlockedTime(MILLISECONDS.toNanos(threadBlockedTimeMillis
                    - startingSnapshot.threadBlockedTimeMillis()));
        }
        long threadWaitedTimeMillis = threadInfo.getWaitedTime();
        if (startingSnapshot.threadWaitedTimeMillis() != -1 && threadWaitedTimeMillis != -1) {
            builder.threadWaitedTime(MILLISECONDS.toNanos(threadWaitedTimeMillis
                    - startingSnapshot.threadWaitedTimeMillis()));
        }
    }

    @RequiresNonNull("threadAllocatedBytes")
    private void addThreadAllocatedBytes(ImmutableThreadInfoData.Builder builder) {
        long allocatedBytes = threadAllocatedBytes.getThreadAllocatedBytesSafely(threadId);
        if (startingSnapshot.threadAllocatedBytes() != -1 && allocatedBytes != -1) {
            builder.threadAllocatedBytes(
                    allocatedBytes - startingSnapshot.threadAllocatedBytes());
        }
    }

    @Value.Immutable
    abstract static class ThreadInfoSnapshot {
        @Value.Default
        long threadCpuTime() { // nanoseconds
            return -1;
        }
        @Value.Default
        long threadBlockedTimeMillis() { // milliseconds (native resolution from jvm)
            return -1;
        }
        @Value.Default
        long threadWaitedTimeMillis() { // milliseconds (native resolution from jvm)
            return -1;
        }
        @Value.Default
        long threadAllocatedBytes() {
            return -1;
        }
    }

    @Value.Immutable
    public abstract static class ThreadInfoData {
        public abstract @Nullable Long threadCpuTime(); // nanoseconds
        public abstract @Nullable Long threadBlockedTime(); // nanoseconds (for consistency)
        public abstract @Nullable Long threadWaitedTime(); // nanoseconds (for consistency)
        public abstract @Nullable Long threadAllocatedBytes();
    }
}
