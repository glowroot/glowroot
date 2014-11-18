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
package org.glowroot.container.trace;

import javax.annotation.Nullable;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.google.common.base.MoreObjects;

public class TraceThreadInfo {

    private final @Nullable Long threadCpuTime;
    private final @Nullable Long threadBlockedTime;
    private final @Nullable Long threadWaitedTime;
    private final @Nullable Long threadAllocatedBytes;

    private TraceThreadInfo(@Nullable Long threadCpuTime, @Nullable Long threadBlockedTime,
            @Nullable Long threadWaitedTime, @Nullable Long threadAllocatedBytes) {
        this.threadCpuTime = threadCpuTime;
        this.threadBlockedTime = threadBlockedTime;
        this.threadWaitedTime = threadWaitedTime;
        this.threadAllocatedBytes = threadAllocatedBytes;
    }

    public @Nullable Long getThreadCpuTime() {
        return threadCpuTime;
    }

    public @Nullable Long getThreadBlockedTime() {
        return threadBlockedTime;
    }

    public @Nullable Long getThreadWaitedTime() {
        return threadWaitedTime;
    }

    public @Nullable Long getThreadAllocatedBytes() {
        return threadAllocatedBytes;
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
                .add("threadCpuTime", threadCpuTime)
                .add("threadBlockedTime", threadBlockedTime)
                .add("threadWaitedTime", threadWaitedTime)
                .add("threadAllocatedBytes", threadAllocatedBytes)
                .toString();
    }

    @JsonCreator
    static TraceThreadInfo readValue(
            @JsonProperty("threadCpuTime") @Nullable Long threadCpuTime,
            @JsonProperty("threadBlockedTime") @Nullable Long threadBlockedTime,
            @JsonProperty("threadWaitedTime") @Nullable Long threadWaitedTime,
            @JsonProperty("threadAllocatedBytes") @Nullable Long threadAllocatedBytes)
            throws JsonMappingException {
        return new TraceThreadInfo(threadCpuTime, threadBlockedTime, threadWaitedTime,
                threadAllocatedBytes);
    }
}
