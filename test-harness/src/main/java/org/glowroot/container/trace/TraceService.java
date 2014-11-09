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
import java.util.List;
import java.util.concurrent.TimeUnit;

import javax.annotation.Nullable;

import com.google.common.base.Stopwatch;

public abstract class TraceService {

    public abstract int getNumPendingCompleteTransactions() throws Exception;

    public abstract long getNumTraces() throws Exception;

    public abstract InputStream getTraceExport(String string) throws Exception;

    @Nullable
    public abstract Trace getLastTrace() throws Exception;

    @Nullable
    protected abstract Trace getActiveTrace() throws Exception;

    // this method blocks for an active trace to be available because
    // sometimes need to give container enough time to start up and for the trace to hit its store
    // threshold
    @Nullable
    public Trace getActiveTrace(int timeout, TimeUnit unit) throws Exception {
        Stopwatch stopwatch = Stopwatch.createStarted();
        Trace trace = null;
        // try at least once (e.g. in case timeoutMillis == 0)
        boolean first = true;
        while (first || stopwatch.elapsed(unit) < timeout) {
            trace = getActiveTrace();
            if (trace != null) {
                break;
            }
            Thread.sleep(20);
            first = false;
        }
        return trace;
    }

    @Nullable
    public abstract List<TraceEntry> getEntries(String traceId) throws Exception;

    @Nullable
    public abstract ProfileNode getProfile(String traceId) throws Exception;

    @Nullable
    public abstract ProfileNode getOutlierProfile(String traceId) throws Exception;

    public abstract void deleteAll() throws Exception;
}
