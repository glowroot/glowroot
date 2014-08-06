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
package org.glowroot.trace.model;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadInfo;
import java.lang.management.ThreadMXBean;

import org.checkerframework.checker.nullness.qual.MonotonicNonNull;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonGenerator;
import com.google.common.base.Objects;
import com.google.common.io.CharStreams;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.glowroot.jvm.ThreadAllocatedBytes;
import org.glowroot.markers.GuardedBy;

import static com.google.common.base.Preconditions.checkNotNull;

/**
 * @author Trask Stalnaker
 * @since 0.5
 */
public class TraceThreadInfo {

    private static final Logger logger = LoggerFactory.getLogger(TraceThreadInfo.class);
    private static final JsonFactory jsonFactory = new JsonFactory();

    private static final ThreadMXBean threadMXBean = ManagementFactory.getThreadMXBean();
    private static final boolean isThreadCpuTimeSupported = threadMXBean.isThreadCpuTimeSupported();
    private static final boolean isThreadContentionMonitoringSupported =
            threadMXBean.isThreadContentionMonitoringSupported();

    private final long threadId;
    private final long threadCpuTimeStart;
    private final long threadBlockedTimeStart;
    private final long threadWaitedTimeStart;
    private final long threadAllocatedBytesStart;

    @Nullable
    private final ThreadAllocatedBytes threadAllocatedBytes;

    @GuardedBy("lock")
    @MonotonicNonNull
    private volatile String completedJsonValue;

    private final Object lock = new Object();

    public TraceThreadInfo(@Nullable ThreadAllocatedBytes threadAllocatedBytes) {
        threadId = Thread.currentThread().getId();
        ThreadInfo threadInfo = threadMXBean.getThreadInfo(threadId, 0);
        // thread info for current thread cannot be null
        checkNotNull(threadInfo);
        if (isThreadCpuTimeSupported) {
            threadCpuTimeStart = threadMXBean.getCurrentThreadCpuTime();
        } else {
            threadCpuTimeStart = -1;
        }
        if (isThreadContentionMonitoringSupported) {
            threadBlockedTimeStart = threadInfo.getBlockedTime();
            threadWaitedTimeStart = threadInfo.getWaitedTime();
        } else {
            threadBlockedTimeStart = -1;
            threadWaitedTimeStart = -1;
        }
        if (threadAllocatedBytes != null) {
            threadAllocatedBytesStart =
                    threadAllocatedBytes.getThreadAllocatedBytesSafely(threadId);
        } else {
            threadAllocatedBytesStart = -1;
        }
        this.threadAllocatedBytes = threadAllocatedBytes;
    }

    // must be called from trace thread
    void onTraceComplete() {
        synchronized (lock) {
            completedJsonValue = writeValueAsString();
        }
    }

    // safe to be called from another thread
    String writeValueAsString() {
        synchronized (lock) {
            if (completedJsonValue == null) {
                // trace thread is still alive (and cannot terminate in the middle of this method
                // because of above lock), so safe to capture ThreadMXBean.getThreadInfo() and
                // ThreadMXBean.getThreadCpuTime() for the trace thread
                StringBuilder sb = new StringBuilder();
                try {
                    JsonGenerator jg = jsonFactory.createGenerator(CharStreams.asWriter(sb));
                    writeValue(jg);
                    jg.close();
                } catch (IOException e) {
                    logger.warn(e.getMessage(), e);
                    return "{}";
                }
                return sb.toString();
            } else {
                return completedJsonValue;
            }
        }
    }

    private void writeValue(JsonGenerator jg) throws IOException {
        ThreadInfo threadInfo = threadMXBean.getThreadInfo(threadId, 0);
        if (threadInfo == null) {
            // thread must have just recently terminated
            jg.writeStartObject();
            jg.writeFieldName("garbageCollectorInfos");
            jg.writeStartArray();
            jg.writeEndArray();
            jg.writeEndObject();
            return;
        }
        jg.writeStartObject();
        if (isThreadCpuTimeSupported) {
            // getThreadCpuTime() returns -1 if CPU time measurement is disabled (which is different
            // than whether or not it is supported)
            long threadCpuTime = threadMXBean.getThreadCpuTime(threadId);
            if (threadCpuTimeStart != -1 && threadCpuTime != -1) {
                jg.writeNumberField("threadCpuTime", threadCpuTime - threadCpuTimeStart);
            }
        }
        if (isThreadContentionMonitoringSupported) {
            // getBlockedTime() and getWaitedTime() return -1 if thread contention monitoring is
            // disabled (which is different than whether or not it is supported)
            long threadBlockedTime = threadInfo.getBlockedTime();
            long threadWaitedTime = threadInfo.getWaitedTime();
            if (threadBlockedTimeStart != -1 && threadBlockedTime != -1) {
                jg.writeNumberField("threadBlockedTime",
                        threadBlockedTime - threadBlockedTimeStart);
            }
            if (threadWaitedTimeStart != -1 && threadWaitedTime != -1) {
                jg.writeNumberField("threadWaitedTime", threadWaitedTime - threadWaitedTimeStart);
            }
        }
        if (threadAllocatedBytes != null) {
            long allocatedBytes = threadAllocatedBytes.getThreadAllocatedBytesSafely(threadId);
            if (threadAllocatedBytesStart != -1 && allocatedBytes != -1) {
                jg.writeNumberField("threadAllocatedBytes",
                        allocatedBytes - threadAllocatedBytesStart);
            }
        }
        jg.writeEndObject();
    }

    @Override
    public String toString() {
        return Objects.toStringHelper(this)
                .add("threadId", threadId)
                .add("threadCpuTimeStart", threadCpuTimeStart)
                .add("threadBlockedTimeStart", threadBlockedTimeStart)
                .add("threadWaitedTimeStart", threadWaitedTimeStart)
                .toString();
    }
}
