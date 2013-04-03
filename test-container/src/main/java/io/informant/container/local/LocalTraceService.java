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
package io.informant.container.local;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.concurrent.TimeUnit;

import checkers.igj.quals.ReadOnly;
import checkers.nullness.quals.Nullable;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.base.Stopwatch;
import com.google.common.base.Ticker;
import com.google.common.collect.Iterables;
import com.google.common.collect.Lists;

import io.informant.InformantModule;
import io.informant.container.common.ObjectMappers;
import io.informant.container.trace.Trace;
import io.informant.container.trace.TraceService;
import io.informant.local.store.SnapshotDao;
import io.informant.local.ui.TraceExportHttpService;
import io.informant.markers.ThreadSafe;
import io.informant.snapshot.Snapshot;
import io.informant.snapshot.SnapshotCreator;
import io.informant.snapshot.SnapshotTraceSink;
import io.informant.snapshot.SnapshotWriter;
import io.informant.trace.TraceRegistry;

import static java.util.concurrent.TimeUnit.SECONDS;

/**
 * @author Trask Stalnaker
 * @since 0.5
 */
// even though this is thread safe, it is not useful for running tests in parallel since
// getLastTrace() and others are not scoped to a particular test
@ThreadSafe
class LocalTraceService implements TraceService {

    @ReadOnly
    private static final ObjectMapper mapper = ObjectMappers.create();

    private final SnapshotDao snapshotDao;
    private final TraceExportHttpService traceExportHttpService;
    private final SnapshotTraceSink traceSink;
    private final TraceRegistry traceRegistry;
    private final Ticker ticker;

    LocalTraceService(InformantModule informantModule) {
        snapshotDao = informantModule.getStorageModule().getSnapshotDao();
        traceExportHttpService = informantModule.getUiModule().getTraceExportHttpService();
        traceSink = informantModule.getSnapshotModule().getSnapshotTraceSink();
        traceRegistry = informantModule.getTraceModule().getTraceRegistry();
        // can't use ticker from Informant since it is shaded when run in mvn and unshaded in ide
        ticker = Ticker.systemTicker();
    }

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

    public int getNumPendingCompleteTraces() {
        return traceSink.getPendingCompleteTraces().size();
    }

    public long getNumStoredSnapshots() {
        return snapshotDao.count();
    }

    public InputStream getTraceExport(String id) throws Exception {
        return new ByteArrayInputStream(traceExportHttpService.getExportBytes(id));
    }

    void assertNoActiveTraces() throws Exception {
        Stopwatch stopwatch = new Stopwatch().start();
        // if interruptAppUnderTest() was used to terminate an active trace, it may take a few
        // milliseconds to interrupt the thread and end the active trace
        while (stopwatch.elapsed(SECONDS) < 2) {
            int numActiveTraces = Iterables.size(traceRegistry.getTraces());
            if (numActiveTraces == 0) {
                return;
            }
        }
        throw new AssertionError("There are still active traces");
    }

    void deleteAllSnapshots() {
        snapshotDao.deleteAllSnapshots();
    }

    @Nullable
    private Trace getLastTrace(boolean summary) throws Exception {
        Snapshot snapshot = snapshotDao.getLastSnapshot(summary);
        if (snapshot == null) {
            return null;
        }
        Trace trace = ObjectMappers.readRequiredValue(mapper,
                SnapshotWriter.toString(snapshot, false), Trace.class);
        trace.setSummary(summary);
        return trace;
    }

    @Nullable
    private Trace getActiveTrace(int timeout, TimeUnit unit, boolean summary) throws Exception {
        Stopwatch stopwatch = new Stopwatch().start();
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

    @Nullable
    private Trace getActiveTrace(boolean summary) throws IOException {
        List<io.informant.trace.model.Trace> traces = Lists.newArrayList(traceRegistry.getTraces());
        if (traces.isEmpty()) {
            return null;
        } else if (traces.size() > 1) {
            throw new IllegalStateException("Unexpected number of active traces");
        } else {
            Snapshot snapshot =
                    SnapshotCreator.createSnapshot(traces.get(0), ticker.read(), summary);
            Trace trace = ObjectMappers.readRequiredValue(mapper,
                    SnapshotWriter.toString(snapshot, true), Trace.class);
            trace.setSummary(summary);
            return trace;
        }
    }
}
