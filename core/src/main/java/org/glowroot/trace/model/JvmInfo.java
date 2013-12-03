/*
 * Copyright 2013 the original author or authors.
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
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadInfo;
import java.util.List;
import java.util.Set;

import checkers.igj.quals.ReadOnly;
import checkers.lock.quals.GuardedBy;
import checkers.nullness.quals.Nullable;
import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonGenerationException;
import com.fasterxml.jackson.core.JsonGenerator;
import com.google.common.base.Objects;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Sets;
import com.google.common.io.CharStreams;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.glowroot.jvm.ThreadAllocatedBytes;
import org.glowroot.jvm.ThreadContentionTime;
import org.glowroot.jvm.ThreadCpuTime;

import static org.glowroot.common.Nullness.assertNonNull;

/**
 * @author Trask Stalnaker
 * @since 0.5
 */
class JvmInfo {

    private static final Logger logger = LoggerFactory.getLogger(JvmInfo.class);

    @ReadOnly
    private static final JsonFactory jsonFactory = new JsonFactory();

    private final long threadId;
    private final long threadCpuTimeStart;
    private final long threadBlockedTimeStart;
    private final long threadWaitedTimeStart;
    private final long threadAllocatedBytesStart;

    private final ImmutableMap<String, GarbageCollectorInfo> garbageCollectorInfos;

    @GuardedBy("lock")
    @Nullable
    private volatile String completedJsonValue;

    private final Object lock = new Object();

    JvmInfo() {
        threadId = Thread.currentThread().getId();
        ThreadInfo threadInfo = ManagementFactory.getThreadMXBean().getThreadInfo(threadId, 0);
        assertNonNull(threadInfo, "Thread info for current thread is null");
        threadCpuTimeStart = ThreadCpuTime.getCurrentThreadCpuTimeSafely();
        threadBlockedTimeStart = ThreadContentionTime.getBlockedTimeSafely(threadInfo);
        threadWaitedTimeStart = ThreadContentionTime.getWaitedTimeSafely(threadInfo);
        threadAllocatedBytesStart = ThreadAllocatedBytes.getThreadAllocatedBytesSafely(threadId);

        List<GarbageCollectorMXBean> garbageCollectorBeans =
                ManagementFactory.getGarbageCollectorMXBeans();
        ImmutableMap.Builder<String, GarbageCollectorInfo> infos = ImmutableMap.builder();
        for (GarbageCollectorMXBean garbageCollectorBean : garbageCollectorBeans) {
            infos.put(garbageCollectorBean.getName(),
                    new GarbageCollectorInfo(garbageCollectorBean));
        }
        this.garbageCollectorInfos = infos.build();
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

    private void writeValue(JsonGenerator jg) throws JsonGenerationException, IOException {
        ThreadInfo threadInfo = ManagementFactory.getThreadMXBean().getThreadInfo(threadId, 0);
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
        long threadCpuTime = ThreadCpuTime.getThreadCpuTimeSafely(threadId);
        long threadBlockedTime = ThreadContentionTime.getBlockedTimeSafely(threadInfo);
        long threadWaitedTime = ThreadContentionTime.getWaitedTimeSafely(threadInfo);
        long threadAllocatedBytes = ThreadAllocatedBytes.getThreadAllocatedBytesSafely(threadId);
        if (threadCpuTimeStart != -1 && threadCpuTime != -1) {
            jg.writeNumberField("threadCpuTime", threadCpuTime - threadCpuTimeStart);
        }
        if (threadBlockedTimeStart != -1 && threadBlockedTime != -1) {
            jg.writeNumberField("threadBlockedTime", threadBlockedTime - threadBlockedTimeStart);
        }
        if (threadWaitedTimeStart != -1 && threadWaitedTime != -1) {
            jg.writeNumberField("threadWaitedTime", threadWaitedTime - threadWaitedTimeStart);
        }
        if (threadAllocatedBytesStart != -1 && threadAllocatedBytes != -1) {
            jg.writeNumberField("threadAllocatedBytes",
                    threadAllocatedBytes - threadAllocatedBytesStart);
        }
        List<GarbageCollectorMXBean> garbageCollectorBeans =
                ManagementFactory.getGarbageCollectorMXBeans();
        Set<String> unmatchedNames = Sets.newHashSet(garbageCollectorInfos.keySet());
        jg.writeFieldName("garbageCollectorInfos");
        jg.writeStartArray();
        for (GarbageCollectorMXBean garbageCollectorBean : garbageCollectorBeans) {
            String name = garbageCollectorBean.getName();
            GarbageCollectorInfo garbageCollectorInfo = garbageCollectorInfos.get(name);
            if (garbageCollectorInfo == null) {
                logger.warn("garbage collector bean {} did not exist at start of trace", name);
                continue;
            }
            unmatchedNames.remove(name);
            long collectionCountEnd = garbageCollectorBean.getCollectionCount();
            long collectionTimeEnd = garbageCollectorBean.getCollectionTime();
            if (collectionCountEnd == garbageCollectorInfo.getCollectionCountStart()) {
                // no new collections, so don't write it out
                continue;
            }
            jg.writeStartObject();
            jg.writeStringField("name", name);
            jg.writeNumberField("collectionCount",
                    collectionCountEnd - garbageCollectorInfo.getCollectionCountStart());
            jg.writeNumberField("collectionTime",
                    collectionTimeEnd - garbageCollectorInfo.getCollectionTimeStart());
            jg.writeEndObject();
        }
        for (String unmatchedName : unmatchedNames) {
            logger.warn("garbage collector bean {} did not exist at end of trace", unmatchedName);
        }
        jg.writeEndArray();
        jg.writeEndObject();
    }

    @Override
    public String toString() {
        return Objects.toStringHelper(this)
                .add("threadId", threadId)
                .add("threadCpuTimeStart", threadCpuTimeStart)
                .add("threadBlockedTimeStart", threadBlockedTimeStart)
                .add("threadWaitedTimeStart", threadWaitedTimeStart)
                .add("garbageCollectorInfos", garbageCollectorInfos)
                .toString();
    }

    private static class GarbageCollectorInfo {

        private final long collectionCountStart;
        private final long collectionTimeStart;

        private GarbageCollectorInfo(GarbageCollectorMXBean garbageCollectorBean) {
            collectionCountStart = garbageCollectorBean.getCollectionCount();
            collectionTimeStart = garbageCollectorBean.getCollectionTime();
        }

        private long getCollectionCountStart() {
            return collectionCountStart;
        }

        private long getCollectionTimeStart() {
            return collectionTimeStart;
        }

        @Override
        public String toString() {
            return Objects.toStringHelper(this)
                    .add("collectionCountStart", collectionCountStart)
                    .add("collectionTimeStart", collectionTimeStart)
                    .toString();
        }
    }
}
