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
package org.glowroot.testkit;

import java.util.List;

import checkers.igj.quals.Immutable;
import checkers.igj.quals.ReadOnly;
import checkers.nullness.quals.Nullable;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.google.common.base.Objects;
import com.google.common.collect.ImmutableList;
import dataflow.quals.Pure;

import static org.glowroot.container.common.ObjectMappers.checkRequiredProperty;

/**
 * @author Trask Stalnaker
 * @since 0.5
 */
@Immutable
public class JvmInfo {

    @Nullable
    private final Long threadCpuTime;
    @Nullable
    private final Long threadBlockedTime;
    @Nullable
    private final Long threadWaitedTime;
    @Nullable
    private final Long threadAllocatedBytes;
    private final ImmutableList<GarbageCollectorInfo> garbageCollectorInfos;

    private JvmInfo(@Nullable Long threadCpuTime, @Nullable Long threadBlockedTime,
            @Nullable Long threadWaitedTime, @Nullable Long threadAllocatedBytes,
            @ReadOnly List<GarbageCollectorInfo> garbageCollectorInfos) {
        this.threadCpuTime = threadCpuTime;
        this.threadBlockedTime = threadBlockedTime;
        this.threadWaitedTime = threadWaitedTime;
        this.threadAllocatedBytes = threadAllocatedBytes;
        this.garbageCollectorInfos = ImmutableList.copyOf(garbageCollectorInfos);
    }

    @Nullable
    public Long getThreadCpuTime() {
        return threadCpuTime;
    }

    @Nullable
    public Long getThreadBlockedTime() {
        return threadBlockedTime;
    }

    @Nullable
    public Long getThreadWaitedTime() {
        return threadWaitedTime;
    }

    @Nullable
    public Long getThreadAllocatedBytes() {
        return threadAllocatedBytes;
    }

    public ImmutableList<GarbageCollectorInfo> getGarbageCollectorInfos() {
        return garbageCollectorInfos;
    }

    @Override
    @Pure
    public String toString() {
        return Objects.toStringHelper(this)
                .add("threadCpuTime", threadCpuTime)
                .add("threadBlockedTime", threadBlockedTime)
                .add("threadWaitedTime", threadWaitedTime)
                .add("threadAllocatedBytes", threadAllocatedBytes)
                .add("garbageCollectorInfos", garbageCollectorInfos)
                .toString();
    }

    @JsonCreator
    static JvmInfo readValue(
            @JsonProperty("threadCpuTime") @Nullable Long threadCpuTime,
            @JsonProperty("threadBlockedTime") @Nullable Long threadBlockedTime,
            @JsonProperty("threadWaitedTime") @Nullable Long threadWaitedTime,
            @JsonProperty("threadAllocatedBytes") @Nullable Long threadAllocatedBytes,
            @JsonProperty("garbageCollectorInfos") @Nullable List<GarbageCollectorInfo> infos)
            throws JsonMappingException {
        checkRequiredProperty(infos, "garbageCollectorInfos");
        return new JvmInfo(threadCpuTime, threadBlockedTime, threadWaitedTime,
                threadAllocatedBytes, infos);
    }

    @Immutable
    public static class GarbageCollectorInfo {

        private final String name;
        private final long collectionCount;
        private final long collectionTime;

        private GarbageCollectorInfo(String name, long collectionCount, long collectionTime) {
            this.name = name;
            this.collectionCount = collectionCount;
            this.collectionTime = collectionTime;
        }

        public String getName() {
            return name;
        }

        public long getCollectionCount() {
            return collectionCount;
        }

        public long getCollectionTime() {
            return collectionTime;
        }

        @Override
        @Pure
        public String toString() {
            return Objects.toStringHelper(this)
                    .add("name", name)
                    .add("collectionCount", collectionCount)
                    .add("collectionTime", collectionTime)
                    .toString();
        }

        @JsonCreator
        static GarbageCollectorInfo readValue(
                @JsonProperty("name") @Nullable String name,
                @JsonProperty("collectionCount") @Nullable Long collectionCount,
                @JsonProperty("collectionTime") @Nullable Long collectionTime)
                throws JsonMappingException {
            checkRequiredProperty(name, "name");
            checkRequiredProperty(collectionCount, "collectionCount");
            checkRequiredProperty(collectionTime, "collectionTime");
            return new GarbageCollectorInfo(name, collectionCount, collectionTime);
        }
    }
}
