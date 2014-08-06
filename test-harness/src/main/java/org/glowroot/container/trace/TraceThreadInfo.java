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

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.google.common.base.Objects;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * @author Trask Stalnaker
 * @since 0.5
 */
public class TraceThreadInfo {

    @Nullable
    private final Long threadCpuTime;
    @Nullable
    private final Long threadBlockedTime;
    @Nullable
    private final Long threadWaitedTime;
    @Nullable
    private final Long threadAllocatedBytes;

    private TraceThreadInfo(@Nullable Long threadCpuTime, @Nullable Long threadBlockedTime,
            @Nullable Long threadWaitedTime, @Nullable Long threadAllocatedBytes) {
        this.threadCpuTime = threadCpuTime;
        this.threadBlockedTime = threadBlockedTime;
        this.threadWaitedTime = threadWaitedTime;
        this.threadAllocatedBytes = threadAllocatedBytes;
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

    @Override
    public String toString() {
        return Objects.toStringHelper(this)
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
