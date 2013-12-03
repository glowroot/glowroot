/*
 * Copyright 2012-2013 the original author or authors.
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
package org.glowroot.local.ui;

import java.io.IOException;
import java.io.UnsupportedEncodingException;

import checkers.igj.quals.ReadOnly;
import checkers.nullness.quals.Nullable;
import com.google.common.base.Ticker;
import com.google.common.io.CharSource;

import org.glowroot.collector.Snapshot;
import org.glowroot.collector.SnapshotCreator;
import org.glowroot.collector.SnapshotWriter;
import org.glowroot.collector.TraceCollectorImpl;
import org.glowroot.common.Clock;
import org.glowroot.local.store.SnapshotDao;
import org.glowroot.markers.Singleton;
import org.glowroot.trace.TraceRegistry;
import org.glowroot.trace.model.Trace;

/**
 * @author Trask Stalnaker
 * @since 0.5
 */
@Singleton
class TraceCommonService {

    private final SnapshotDao snapshotDao;
    private final TraceRegistry traceRegistry;
    private final TraceCollectorImpl traceCollectorImpl;
    private final Clock clock;
    private final Ticker ticker;

    TraceCommonService(SnapshotDao snapshotDao, TraceRegistry traceRegistry,
            TraceCollectorImpl traceCollectorImpl, Clock clock, Ticker ticker) {
        this.snapshotDao = snapshotDao;
        this.traceRegistry = traceRegistry;
        this.traceCollectorImpl = traceCollectorImpl;
        this.clock = clock;
        this.ticker = ticker;
    }

    @ReadOnly
    @Nullable
    CharSource createCharSourceForSnapshotOrActiveTrace(String id, boolean summary)
            throws IOException {
        // check active traces first to make sure that the trace is not missed if it should complete
        // after checking stored traces but before checking active traces
        for (Trace active : traceRegistry.getTraces()) {
            if (active.getId().equals(id)) {
                return toCharSource(active, summary);
            }
        }
        // then check pending traces to make sure the trace is not missed if it is in between active
        // and stored
        for (Trace pending : traceCollectorImpl.getPendingCompleteTraces()) {
            if (pending.getId().equals(id)) {
                return toCharSource(pending, summary);
            }
        }
        Snapshot snapshot;
        if (summary) {
            snapshot = snapshotDao.readSnapshotWithoutDetail(id);
        } else {
            snapshot = snapshotDao.readSnapshot(id);
        }
        if (snapshot == null) {
            return null;
        } else {
            return SnapshotWriter.toCharSource(snapshot, false, summary);
        }
    }

    private CharSource toCharSource(Trace trace, boolean summary) throws IOException,
            UnsupportedEncodingException {
        Snapshot snapshot = SnapshotCreator.createActiveSnapshot(trace,
                clock.currentTimeMillis(), ticker.read(), summary);
        return SnapshotWriter.toCharSource(snapshot, true, summary);
    }
}
