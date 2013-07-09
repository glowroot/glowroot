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
package io.informant.testkit;

import java.util.List;

import checkers.igj.quals.Immutable;
import checkers.igj.quals.ReadOnly;
import checkers.nullness.quals.Nullable;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.google.common.base.Objects;
import com.google.common.collect.ImmutableList;

import static io.informant.container.common.ObjectMappers.checkRequiredProperty;

/**
 * @author Trask Stalnaker
 * @since 0.5
 */
@Immutable
public class JvmInfo {

    private final long threadCpuTime;
    private final long threadBlockedTime;
    private final long threadWaitedTime;
    private final ImmutableList<GarbageCollectorInfo> garbageCollectorInfos;

    private JvmInfo(long threadCpuTime, long threadBlockedTime, long threadWaitedTime,
            @ReadOnly List<GarbageCollectorInfo> garbageCollectorInfos) {
        this.threadCpuTime = threadCpuTime;
        this.threadBlockedTime = threadBlockedTime;
        this.threadWaitedTime = threadWaitedTime;
        this.garbageCollectorInfos = ImmutableList.copyOf(garbageCollectorInfos);
    }

    public long getThreadCpuTime() {
        return threadCpuTime;
    }

    public long getThreadBlockedTime() {
        return threadBlockedTime;
    }

    public long getThreadWaitedTime() {
        return threadWaitedTime;
    }

    public ImmutableList<GarbageCollectorInfo> getGarbageCollectorInfos() {
        return garbageCollectorInfos;
    }

    @Override
    public String toString() {
        return Objects.toStringHelper(this)
                .add("threadCpuTime", threadCpuTime)
                .add("threadBlockedTime", threadBlockedTime)
                .add("threadWaitedTime", threadWaitedTime)
                .add("garbageCollectorInfos", garbageCollectorInfos)
                .toString();
    }

    @JsonCreator
    static JvmInfo readValue(
            @JsonProperty("threadCpuTime") @Nullable Long threadCpuTime,
            @JsonProperty("threadBlockedTime") @Nullable Long threadBlockedTime,
            @JsonProperty("threadWaitedTime") @Nullable Long threadWaitedTime,
            @JsonProperty("garbageCollectorInfos") @Nullable List<GarbageCollectorInfo> infos)
            throws JsonMappingException {
        checkRequiredProperty(threadCpuTime, "threadCpuTime");
        checkRequiredProperty(threadBlockedTime, "threadBlockedTime");
        checkRequiredProperty(threadWaitedTime, "threadWaitedTime");
        checkRequiredProperty(infos, "garbageCollectorInfos");
        return new JvmInfo(threadCpuTime, threadBlockedTime, threadWaitedTime, infos);
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
