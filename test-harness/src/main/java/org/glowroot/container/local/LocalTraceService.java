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
package org.glowroot.container.local;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.List;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.base.Stopwatch;
import com.google.common.base.Ticker;
import com.google.common.collect.Lists;
import org.checkerframework.checker.nullness.qual.Nullable;

import org.glowroot.GlowrootModule;
import org.glowroot.collector.Snapshot;
import org.glowroot.collector.SnapshotCreator;
import org.glowroot.collector.SnapshotWriter;
import org.glowroot.collector.TraceCollectorImpl;
import org.glowroot.container.common.ObjectMappers;
import org.glowroot.container.trace.ProfileNode;
import org.glowroot.container.trace.Span;
import org.glowroot.container.trace.Trace;
import org.glowroot.container.trace.TraceService;
import org.glowroot.local.store.SnapshotDao;
import org.glowroot.local.store.TransactionPointDao;
import org.glowroot.local.ui.TraceCommonService;
import org.glowroot.local.ui.TraceExportHttpService;
import org.glowroot.trace.TraceRegistry;

import static java.util.concurrent.TimeUnit.SECONDS;

/**
 * @author Trask Stalnaker
 * @since 0.5
 */
// even though this is thread safe, it is not useful for running tests in parallel since
// getLastTrace() and others are not scoped to a particular test
class LocalTraceService extends TraceService {

    private static final ObjectMapper mapper = ObjectMappers.create();

    private final TransactionPointDao transactionPointDao;
    private final SnapshotDao snapshotDao;
    private final TraceCommonService traceCommonService;
    private final TraceExportHttpService traceExportHttpService;
    private final TraceCollectorImpl traceCollector;
    private final TraceRegistry traceRegistry;
    private final Ticker ticker;

    LocalTraceService(GlowrootModule glowrootModule) {
        transactionPointDao = glowrootModule.getStorageModule().getTransactionPointDao();
        snapshotDao = glowrootModule.getStorageModule().getSnapshotDao();
        traceCommonService = glowrootModule.getUiModule().getTraceCommonService();
        traceExportHttpService = glowrootModule.getUiModule().getTraceExportHttpService();
        traceCollector = glowrootModule.getCollectorModule().getTraceCollector();
        traceRegistry = glowrootModule.getTraceModule().getTraceRegistry();
        // can't use ticker from Glowroot since it is shaded when run in mvn and unshaded in ide
        ticker = Ticker.systemTicker();
    }

    @Override
    public int getNumPendingCompleteTraces() {
        return traceCollector.getPendingCompleteTraces().size();
    }

    @Override
    public long getNumStoredSnapshots() {
        return snapshotDao.count();
    }

    @Override
    public InputStream getTraceExport(String id) throws Exception {
        return new ByteArrayInputStream(traceExportHttpService.getExportBytes(id));
    }

    @Override
    @Nullable
    public Trace getLastTrace() throws Exception {
        // check pending traces first
        List<org.glowroot.trace.model.Trace> pendingTraces =
                Lists.newArrayList(traceCollector.getPendingCompleteTraces());
        if (pendingTraces.size() > 1) {
            throw new AssertionError("Unexpected multiple pending traces during test");
        }
        Snapshot snapshot = null;
        if (pendingTraces.size() == 1) {
            snapshot = traceCommonService.getSnapshot(pendingTraces.get(0).getId());
        } else {
            // no pending traces, so check stored snapshots
            snapshot = snapshotDao.getLastSnapshot();
        }
        if (snapshot == null) {
            return null;
        }
        return ObjectMappers.readRequiredValue(mapper, SnapshotWriter.toString(snapshot),
                Trace.class);
    }

    @Override
    @Nullable
    public List<Span> getSpans(String traceId) throws Exception {
        String spans = traceCommonService.getSpansString(traceId);
        if (spans == null) {
            return null;
        }
        return mapper.readValue(spans, new TypeReference<List<Span>>() {});
    }

    @Override
    @Nullable
    public ProfileNode getCoarseProfile(String traceId) throws Exception {
        String profile = traceCommonService.getCoarseProfileString(traceId);
        if (profile == null) {
            return null;
        }
        return mapper.readValue(profile, ProfileNode.class);
    }

    @Override
    @Nullable
    public ProfileNode getFineProfile(String traceId) throws Exception {
        String profile = traceCommonService.getFineProfileString(traceId);
        if (profile == null) {
            return null;
        }
        return mapper.readValue(profile, ProfileNode.class);
    }

    @Override
    public void deleteAll() {
        transactionPointDao.deleteAll();
        snapshotDao.deleteAll();
    }

    @Override
    @Nullable
    protected Trace getActiveTrace() throws Exception {
        List<org.glowroot.trace.model.Trace> traces = Lists.newArrayList(traceRegistry.getTraces());
        if (traces.isEmpty()) {
            return null;
        } else if (traces.size() > 1) {
            throw new IllegalStateException("Unexpected number of active traces");
        } else {
            Snapshot snapshot = SnapshotCreator.createActiveSnapshot(traces.get(0),
                    traces.get(0).getEndTick(), ticker.read());
            return ObjectMappers.readRequiredValue(mapper, SnapshotWriter.toString(snapshot),
                    Trace.class);
        }
    }

    void assertNoActiveTraces() throws Exception {
        Stopwatch stopwatch = Stopwatch.createStarted();
        // if interruptAppUnderTest() was used to terminate an active trace, it may take a few
        // milliseconds to interrupt the thread and end the active trace
        while (stopwatch.elapsed(SECONDS) < 2) {
            if (traceRegistry.getTraces().isEmpty()) {
                return;
            }
        }
        throw new AssertionError("There are still active traces");
    }
}
