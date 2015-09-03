/*
 * Copyright 2015 the original author or authors.
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

import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.google.common.base.Optional;
import org.immutables.value.Value;

public interface Trace {

    @Value.Immutable
    @JsonSerialize
    public interface Header {
        String id();
        Optional<Boolean> active();
        Optional<Boolean> partial();
        Optional<Boolean> slow();
        long startTime();
        long captureTime();
        long durationNanos();
        String transactionType();
        String transactionName();
        String headline();
        String user();
        Map<String, List<String>> attributes();
        Map<String, /*@Nullable*/Object> detail();
        Optional<Error> error();
        Timer rootTimer();
        Optional<Long> threadCpuNanos();
        Optional<Long> threadBlockedNanos();
        Optional<Long> threadWaitedNanos();
        Optional<Long> threadAllocatedBytes();
        List<GarbageCollectionActivity> gcActivities();
        int entryCount();
        Optional<Boolean> entryLimitExceeded();
        long profileSampleCount();
        Optional<Boolean> profileSampleLimitExceeded();
        Existence entriesExistence();
        Existence profileExistence();
    }

    public enum Existence {
        YES, NO, EXPIRED;
    }

    @Value.Immutable
    @JsonSerialize
    public interface Error {
        String message();
        Optional<Throwable> exception();
    }

    @Value.Immutable
    @JsonSerialize
    public interface Throwable {
        String display();
        List<String> stackTraceElements();
        Optional<Integer> framesInCommonWithEnclosing();
        Optional<Throwable> cause();
    }

    @Value.Immutable
    @JsonSerialize
    public interface Timer {
        String name();
        Optional<Boolean> extended();
        long totalNanos();
        long count();
        Optional<Boolean> active();
        List<Timer> childTimers();
    }

    @Value.Immutable
    @JsonSerialize
    public interface GarbageCollectionActivity {
        String collectorName();
        long totalMillis();
        long count();
    }

    @Value.Immutable
    @JsonSerialize
    public interface Entry {
        long startOffsetNanos();
        long durationNanos();
        Optional<Boolean> active();
        String message();
        Map<String, /*@Nullable*/Object> detail();
        List<String> locationStackTraceElements();
        Optional<Error> error();
        List<Entry> childEntries();
    }
}
