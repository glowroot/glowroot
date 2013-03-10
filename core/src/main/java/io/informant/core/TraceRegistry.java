/**
 * Copyright 2011-2013 the original author or authors.
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
package io.informant.core;

import io.informant.core.trace.Trace;
import io.informant.marker.Singleton;

import java.util.Queue;

import checkers.nullness.quals.Nullable;

import com.google.common.collect.Queues;

/**
 * Registry to hold all active traces. Also holds the current trace state for each thread via
 * ThreadLocals.
 * 
 * @author Trask Stalnaker
 * @since 0.5
 */
@Singleton
public class TraceRegistry {

    // collection of active running traces, "nearly" ordered by start time
    // ordering is not completely guaranteed since there is no synchronization block around
    // trace instantiation and placement into the registry
    private final Queue<Trace> traces = Queues.newConcurrentLinkedQueue();

    // active running trace being executed by the current thread
    private final ThreadLocal</*@Nullable*/Trace> currentTraceHolder =
            new ThreadLocal</*@Nullable*/Trace>();

    @Nullable
    public Trace getCurrentTrace() {
        return currentTraceHolder.get();
    }

    void addTrace(Trace trace) {
        currentTraceHolder.set(trace);
        traces.add(trace);
    }

    void removeTrace(Trace trace) {
        currentTraceHolder.remove();
        traces.remove(trace);
    }

    // collection of active running traces, "nearly" ordered by start time
    // ordering is not completely guaranteed since there is no synchronization block around
    // trace instantiation and placement into the registry
    public Iterable<Trace> getTraces() {
        return traces;
    }
}
