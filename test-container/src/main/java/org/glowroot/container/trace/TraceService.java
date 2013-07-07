/*
 * Copyright 2011-2014 the original author or authors.
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

import java.io.InputStream;
import java.util.concurrent.TimeUnit;

import checkers.nullness.quals.Nullable;
import com.google.common.base.Stopwatch;

/**
 * @author Trask Stalnaker
 * @since 0.5
 */
public abstract class TraceService {

    @Nullable
    public Trace getLastTrace() throws Exception {
        return getLastTrace(false);
    }

    @Nullable
    public Trace getLastTraceSummary() throws Exception {
        return getLastTrace(true);
    }

    // this method blocks for an active trace to be available because
    // sometimes need to give container enough time to start up and for the trace to get stuck
    @Nullable
    public Trace getActiveTrace(int timeout, TimeUnit unit) throws Exception {
        return getActiveTrace(timeout, unit, false);
    }

    // this method blocks for an active trace to be available because
    // sometimes need to give container enough time to start up and for the trace to get stuck
    @Nullable
    public Trace getActiveTraceSummary(int timeout, TimeUnit unit) throws Exception {
        return getActiveTrace(timeout, unit, true);
    }

    public abstract int getNumPendingCompleteTraces() throws Exception;

    public abstract long getNumStoredSnapshots() throws Exception;

    public abstract InputStream getTraceExport(String string) throws Exception;

    @Nullable
    protected abstract Trace getLastTrace(boolean summary) throws Exception;

    @Nullable
    protected abstract Trace getActiveTrace(boolean summary) throws Exception;

    @Nullable
    private Trace getActiveTrace(int timeout, TimeUnit unit, boolean summary) throws Exception {
        Stopwatch stopwatch = Stopwatch.createStarted();
        Trace trace = null;
        // try at least once (e.g. in case timeoutMillis == 0)
        boolean first = true;
        while (first || stopwatch.elapsed(unit) < timeout) {
            trace = getActiveTrace(summary);
            if (trace != null) {
                break;
            }
            Thread.sleep(20);
            first = false;
        }
        return trace;
    }
}
