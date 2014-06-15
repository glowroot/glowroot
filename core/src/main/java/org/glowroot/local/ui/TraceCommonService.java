/*
 * Copyright 2012-2014 the original author or authors.
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
import java.sql.SQLException;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.io.CharSource;
import org.checkerframework.checker.nullness.qual.Nullable;

import org.glowroot.collector.ProfileCharSourceCreator;
import org.glowroot.collector.Snapshot;
import org.glowroot.collector.SnapshotCreator;
import org.glowroot.collector.SnapshotWriter;
import org.glowroot.collector.SpansCharSourceCreator;
import org.glowroot.collector.TraceCollectorImpl;
import org.glowroot.common.Clock;
import org.glowroot.common.Ticker;
import org.glowroot.local.store.SnapshotDao;
import org.glowroot.markers.OnlyUsedByTests;
import org.glowroot.markers.Singleton;
import org.glowroot.trace.TraceRegistry;
import org.glowroot.trace.model.Trace;

/**
 * @author Trask Stalnaker
 * @since 0.5
 */
@Singleton
@VisibleForTesting
public class TraceCommonService {

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

    @VisibleForTesting
    @Nullable
    public Snapshot getSnapshot(String traceId) throws IOException {
        // check active traces first to make sure that the trace is not missed if it should complete
        // after checking stored traces but before checking active traces
        for (Trace active : traceRegistry.getTraces()) {
            if (active.getId().equals(traceId)) {
                return createActiveSnapshot(active);
            }
        }
        // then check pending traces to make sure the trace is not missed if it is in between active
        // and stored
        for (Trace pending : traceCollectorImpl.getPendingCompleteTraces()) {
            if (pending.getId().equals(traceId)) {
                return createPendingSnapshot(pending);
            }
        }
        return snapshotDao.readSnapshot(traceId);
    }

    // overwritten spans will return {"overwritten":true}
    // expired trace will return {"expired":true}
    @Nullable
    CharSource getSpans(String traceId) throws SQLException {
        // check active traces first to make sure that the trace is not missed if it should complete
        // after checking stored traces but before checking active traces
        for (Trace active : traceRegistry.getTraces()) {
            if (active.getId().equals(traceId)) {
                return createTraceSpans(active);
            }
        }
        // then check pending traces to make sure the trace is not missed if it is in between active
        // and stored
        for (Trace pending : traceCollectorImpl.getPendingCompleteTraces()) {
            if (pending.getId().equals(traceId)) {
                return createTraceSpans(pending);
            }
        }
        return snapshotDao.readSpans(traceId);
    }

    // overwritten profile will return {"overwritten":true}
    // expired trace will return {"expired":true}
    @Nullable
    CharSource getCoarseProfile(String traceId) throws SQLException {
        // check active traces first to make sure that the trace is not missed if it should complete
        // after checking stored traces but before checking active traces
        for (Trace active : traceRegistry.getTraces()) {
            if (active.getId().equals(traceId)) {
                return createCoarseProfile(active);
            }
        }
        // then check pending traces to make sure the trace is not missed if it is in between active
        // and stored
        for (Trace pending : traceCollectorImpl.getPendingCompleteTraces()) {
            if (pending.getId().equals(traceId)) {
                return createCoarseProfile(pending);
            }
        }
        return snapshotDao.readCoarseProfile(traceId);
    }

    // overwritten profile will return {"overwritten":true}
    // expired trace will return {"expired":true}
    @Nullable
    CharSource getFineProfile(String traceId) throws SQLException {
        // check active traces first to make sure that the trace is not missed if it should complete
        // after checking stored traces but before checking active traces
        for (Trace active : traceRegistry.getTraces()) {
            if (active.getId().equals(traceId)) {
                return createFineProfile(active);
            }
        }
        // then check pending traces to make sure the trace is not missed if it is in between active
        // and stored
        for (Trace pending : traceCollectorImpl.getPendingCompleteTraces()) {
            if (pending.getId().equals(traceId)) {
                return createFineProfile(pending);
            }
        }
        return snapshotDao.readFineProfile(traceId);
    }

    @Nullable
    TraceExport getExport(String traceId) throws IOException {
        // check active traces first to make sure that the trace is not missed if it should complete
        // after checking stored traces but before checking active traces
        for (Trace active : traceRegistry.getTraces()) {
            if (active.getId().equals(traceId)) {
                Snapshot snapshot = createActiveSnapshot(active);
                return new TraceExport(snapshot, SnapshotWriter.toString(snapshot),
                        createTraceSpans(active), createCoarseProfile(active),
                        createFineProfile(active));
            }
        }
        // then check pending traces to make sure the trace is not missed if it is in between active
        // and stored
        for (Trace pending : traceCollectorImpl.getPendingCompleteTraces()) {
            if (pending.getId().equals(traceId)) {
                Snapshot snapshot = createPendingSnapshot(pending);
                return new TraceExport(snapshot, SnapshotWriter.toString(snapshot),
                        createTraceSpans(pending), createCoarseProfile(pending),
                        createFineProfile(pending));
            }
        }
        Snapshot snapshot = snapshotDao.readSnapshot(traceId);
        if (snapshot == null) {
            return null;
        }
        try {
            return new TraceExport(snapshot, SnapshotWriter.toString(snapshot),
                    snapshotDao.readSpans(traceId), snapshotDao.readCoarseProfile(traceId),
                    snapshotDao.readFineProfile(traceId));
        } catch (SQLException e) {
            throw new IOException(e);
        }
    }

    private Snapshot createPendingSnapshot(Trace pending) throws IOException {
        return SnapshotCreator.createPendingSnapshot(pending, clock.currentTimeMillis(),
                ticker.read());
    }

    private Snapshot createActiveSnapshot(Trace active) throws IOException {
        return SnapshotCreator.createActiveSnapshot(active, clock.currentTimeMillis(),
                ticker.read());
    }

    private CharSource createTraceSpans(Trace active) {
        return SpansCharSourceCreator.createSpansCharSource(active.getSpansCopy(),
                active.getStartTick(), ticker.read());
    }

    @Nullable
    private CharSource createCoarseProfile(Trace active) {
        return ProfileCharSourceCreator.createProfileCharSource(
                active.getCoarseProfile());
    }

    @Nullable
    private CharSource createFineProfile(Trace active) {
        return ProfileCharSourceCreator.createProfileCharSource(
                active.getFineProfile());
    }

    static class TraceExport {
        private final Snapshot snapshot;
        private final String snapshotJson;
        @Nullable
        private final CharSource spans;
        @Nullable
        private final CharSource coarseProfile;
        @Nullable
        private final CharSource fineProfile;

        private TraceExport(Snapshot snapshot, String snapshotJson, @Nullable CharSource spans,
                @Nullable CharSource coarseProfile, @Nullable CharSource fineProfile) {
            this.snapshot = snapshot;
            this.snapshotJson = snapshotJson;
            this.spans = spans;
            this.coarseProfile = coarseProfile;
            this.fineProfile = fineProfile;
        }

        Snapshot getSnapshot() {
            return snapshot;
        }

        String getSnapshotJson() {
            return snapshotJson;
        }

        @Nullable
        CharSource getSpans() {
            return spans;
        }

        @Nullable
        CharSource getCoarseProfile() {
            return coarseProfile;
        }

        @Nullable
        CharSource getFineProfile() {
            return fineProfile;
        }
    }

    // this method exists because tests cannot use (sometimes) shaded guava CharSource
    @OnlyUsedByTests
    @Nullable
    public String getSpansString(String traceId) throws SQLException, IOException {
        CharSource spans = getSpans(traceId);
        if (spans == null) {
            return null;
        }
        return spans.read();
    }

    // this method exists because tests cannot use (sometimes) shaded guava CharSource
    @OnlyUsedByTests
    @Nullable
    public String getCoarseProfileString(String traceId) throws SQLException, IOException {
        CharSource profile = getCoarseProfile(traceId);
        if (profile == null) {
            return null;
        }
        return profile.read();
    }

    // this method exists because tests cannot use (sometimes) shaded guava CharSource
    @OnlyUsedByTests
    @Nullable
    public String getFineProfileString(String traceId) throws SQLException, IOException {
        CharSource profile = getFineProfile(traceId);
        if (profile == null) {
            return null;
        }
        return profile.read();
    }
}
