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
import java.util.Collection;

import javax.annotation.Nullable;

import com.google.common.io.CharSource;

import org.glowroot.collector.EntriesCharSourceCreator;
import org.glowroot.collector.ProfileCharSourceCreator;
import org.glowroot.collector.Trace;
import org.glowroot.collector.TraceCreator;
import org.glowroot.collector.TraceWriter;
import org.glowroot.collector.TransactionCollectorImpl;
import org.glowroot.common.Clock;
import org.glowroot.common.Ticker;
import org.glowroot.local.store.TraceDao;
import org.glowroot.markers.OnlyUsedByTests;
import org.glowroot.transaction.TransactionRegistry;
import org.glowroot.transaction.model.Transaction;

class TraceCommonService {

    private final TraceDao traceDao;
    private final TransactionRegistry transactionRegistry;
    private final TransactionCollectorImpl transactionCollectorImpl;
    private final Clock clock;
    private final Ticker ticker;

    TraceCommonService(TraceDao traceDao, TransactionRegistry transactionRegistry,
            TransactionCollectorImpl transactionCollectorImpl, Clock clock, Ticker ticker) {
        this.traceDao = traceDao;
        this.transactionRegistry = transactionRegistry;
        this.transactionCollectorImpl = transactionCollectorImpl;
        this.clock = clock;
        this.ticker = ticker;
    }

    @Nullable
    Trace getTrace(String traceId) throws IOException, SQLException {
        // check active traces first to make sure that the trace is not missed if it should complete
        // after checking stored traces but before checking active traces
        for (Transaction active : transactionRegistry.getTransactions()) {
            if (active.getId().equals(traceId)) {
                return createActiveTrace(active);
            }
        }
        // then check pending transactions to make sure the trace is not missed if it is in between
        // active and stored
        Collection<Transaction> pendingCompleteTransactions =
                transactionCollectorImpl.getPendingCompleteTransactions();
        for (Transaction pendingComplete : pendingCompleteTransactions) {
            if (pendingComplete.getId().equals(traceId)) {
                return createPendingCompleteTrace(pendingComplete);
            }
        }
        return traceDao.readTrace(traceId);
    }

    // overwritten entries will return {"overwritten":true}
    // expired trace will return {"expired":true}
    @Nullable
    CharSource getEntries(String traceId) throws SQLException {
        // check active traces first to make sure that the trace is not missed if it should complete
        // after checking stored traces but before checking active traces
        for (Transaction active : transactionRegistry.getTransactions()) {
            if (active.getId().equals(traceId)) {
                return createEntries(active);
            }
        }
        // then check pending traces to make sure the trace is not missed if it is in between active
        // and stored
        for (Transaction pending : transactionCollectorImpl.getPendingCompleteTransactions()) {
            if (pending.getId().equals(traceId)) {
                return createEntries(pending);
            }
        }
        return traceDao.readEntries(traceId);
    }

    // overwritten profile will return {"overwritten":true}
    // expired trace will return {"expired":true}
    @Nullable
    CharSource getProfile(String traceId) throws SQLException {
        // check active traces first to make sure that the trace is not missed if it should complete
        // after checking stored traces but before checking active traces
        for (Transaction active : transactionRegistry.getTransactions()) {
            if (active.getId().equals(traceId)) {
                return createProfile(active);
            }
        }
        // then check pending traces to make sure the trace is not missed if it is in between active
        // and stored
        for (Transaction pending : transactionCollectorImpl.getPendingCompleteTransactions()) {
            if (pending.getId().equals(traceId)) {
                return createProfile(pending);
            }
        }
        return traceDao.readProfile(traceId);
    }

    // overwritten profile will return {"overwritten":true}
    // expired trace will return {"expired":true}
    @Nullable
    CharSource getOutlierProfile(String traceId) throws SQLException {
        // check active traces first to make sure that the trace is not missed if it should complete
        // after checking stored traces but before checking active traces
        for (Transaction active : transactionRegistry.getTransactions()) {
            if (active.getId().equals(traceId)) {
                return createOutlierProfile(active);
            }
        }
        // then check pending traces to make sure the trace is not missed if it is in between active
        // and stored
        for (Transaction pending : transactionCollectorImpl.getPendingCompleteTransactions()) {
            if (pending.getId().equals(traceId)) {
                return createOutlierProfile(pending);
            }
        }
        return traceDao.readOutlierProfile(traceId);
    }

    @Nullable
    TraceExport getExport(String traceId) throws IOException, SQLException {
        // check active traces first to make sure that the trace is not missed if it should complete
        // after checking stored traces but before checking active traces
        for (Transaction active : transactionRegistry.getTransactions()) {
            if (active.getId().equals(traceId)) {
                Trace trace = createActiveTrace(active);
                return new TraceExport(trace, TraceWriter.toString(trace), createEntries(active),
                        createProfile(active), createOutlierProfile(active));
            }
        }
        // then check pending traces to make sure the trace is not missed if it is in between active
        // and stored
        for (Transaction pending : transactionCollectorImpl.getPendingCompleteTransactions()) {
            if (pending.getId().equals(traceId)) {
                Trace trace = createPendingCompleteTrace(pending);
                return new TraceExport(trace, TraceWriter.toString(trace), createEntries(pending),
                        createProfile(pending), createOutlierProfile(pending));
            }
        }
        Trace trace = traceDao.readTrace(traceId);
        if (trace == null) {
            return null;
        }
        try {
            return new TraceExport(trace, TraceWriter.toString(trace),
                    traceDao.readEntries(traceId), traceDao.readProfile(traceId),
                    traceDao.readOutlierProfile(traceId));
        } catch (SQLException e) {
            throw new IOException(e);
        }
    }

    private Trace createPendingCompleteTrace(Transaction pending) throws IOException {
        return TraceCreator.createPendingTrace(pending, clock.currentTimeMillis(),
                ticker.read());
    }

    private Trace createActiveTrace(Transaction active) throws IOException {
        return TraceCreator.createActiveTrace(active, clock.currentTimeMillis(),
                ticker.read());
    }

    private CharSource createEntries(Transaction active) {
        return EntriesCharSourceCreator.createEntriesCharSource(active.getEntriesCopy(),
                active.getStartTick(), ticker.read());
    }

    private @Nullable CharSource createOutlierProfile(Transaction active) {
        return ProfileCharSourceCreator.createProfileCharSource(
                active.getOutlierProfile());
    }

    private @Nullable CharSource createProfile(Transaction active) {
        return ProfileCharSourceCreator.createProfileCharSource(
                active.getProfile());
    }

    static class TraceExport {

        private final Trace trace;
        private final String traceJson;
        private @Nullable final CharSource entries;
        private @Nullable final CharSource profile;
        private @Nullable final CharSource outlierProfile;

        private TraceExport(Trace trace, String traceJson, @Nullable CharSource entries,
                @Nullable CharSource profile, @Nullable CharSource outlierProfile) {
            this.trace = trace;
            this.traceJson = traceJson;
            this.entries = entries;
            this.profile = profile;
            this.outlierProfile = outlierProfile;
        }

        Trace getTrace() {
            return trace;
        }

        String getTraceJson() {
            return traceJson;
        }

        @Nullable
        CharSource getEntries() {
            return entries;
        }

        @Nullable
        CharSource getProfile() {
            return profile;
        }

        @Nullable
        CharSource getOutlierProfile() {
            return outlierProfile;
        }
    }

    // this method exists because tests cannot use (sometimes) shaded guava CharSource
    @OnlyUsedByTests
    public @Nullable String getEntriesString(String traceId) throws SQLException, IOException {
        CharSource entries = getEntries(traceId);
        if (entries == null) {
            return null;
        }
        return entries.read();
    }

    // this method exists because tests cannot use (sometimes) shaded guava CharSource
    @OnlyUsedByTests
    public @Nullable String getProfileString(String traceId) throws SQLException, IOException {
        CharSource profile = getProfile(traceId);
        if (profile == null) {
            return null;
        }
        return profile.read();
    }

    // this method exists because tests cannot use (sometimes) shaded guava CharSource
    @OnlyUsedByTests
    public @Nullable String getOutlierProfileString(String traceId) throws SQLException,
            IOException {
        CharSource profile = getOutlierProfile(traceId);
        if (profile == null) {
            return null;
        }
        return profile.read();
    }
}
