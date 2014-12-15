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
    Trace getTrace(String traceId) throws Exception {
        // check active transactions first to make sure that the transaction is not missed if it
        // should complete after checking stored traces but before checking active transactions
        for (Transaction activeTransaction : transactionRegistry.getTransactions()) {
            if (activeTransaction.getId().equals(traceId)) {
                return createActiveTrace(activeTransaction);
            }
        }
        // then check pending transactions to make sure the transaction is not missed if it is in
        // between active and stored
        Collection<Transaction> pendingTransactions =
                transactionCollectorImpl.getPendingTransactions();
        for (Transaction pendingTransaction : pendingTransactions) {
            if (pendingTransaction.getId().equals(traceId)) {
                return TraceCreator.createCompletedTrace(pendingTransaction);
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
        for (Transaction pending : transactionCollectorImpl.getPendingTransactions()) {
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
        for (Transaction pending : transactionCollectorImpl.getPendingTransactions()) {
            if (pending.getId().equals(traceId)) {
                return createProfile(pending);
            }
        }
        return traceDao.readProfile(traceId);
    }

    @Nullable
    TraceExport getExport(String traceId) throws Exception {
        // check active traces first to make sure that the trace is not missed if it should complete
        // after checking stored traces but before checking active traces
        for (Transaction active : transactionRegistry.getTransactions()) {
            if (active.getId().equals(traceId)) {
                Trace trace = createActiveTrace(active);
                return new TraceExport(trace, TraceWriter.toString(trace), createEntries(active),
                        createProfile(active));
            }
        }
        // then check pending traces to make sure the trace is not missed if it is in between active
        // and stored
        for (Transaction pending : transactionCollectorImpl.getPendingTransactions()) {
            if (pending.getId().equals(traceId)) {
                Trace trace = TraceCreator.createCompletedTrace(pending);
                return new TraceExport(trace, TraceWriter.toString(trace), createEntries(pending),
                        createProfile(pending));
            }
        }
        Trace trace = traceDao.readTrace(traceId);
        if (trace == null) {
            return null;
        }
        try {
            return new TraceExport(trace, TraceWriter.toString(trace),
                    traceDao.readEntries(traceId), traceDao.readProfile(traceId));
        } catch (SQLException e) {
            throw new IOException(e);
        }
    }

    private Trace createActiveTrace(Transaction activeTransaction) throws IOException {
        return TraceCreator.createActiveTrace(activeTransaction, clock.currentTimeMillis(),
                ticker.read());
    }

    private CharSource createEntries(Transaction active) {
        return EntriesCharSourceCreator.createEntriesCharSource(active.getEntriesCopy(),
                active.getStartTick(), ticker.read());
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

        private TraceExport(Trace trace, String traceJson, @Nullable CharSource entries,
                @Nullable CharSource profile) {
            this.trace = trace;
            this.traceJson = traceJson;
            this.entries = entries;
            this.profile = profile;
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
    }

    // this method exists because tests cannot use (sometimes) shaded guava CharSource
    @OnlyUsedByTests
    public @Nullable String getEntriesString(String traceId) throws Exception {
        CharSource entries = getEntries(traceId);
        if (entries == null) {
            return null;
        }
        return entries.read();
    }

    // this method exists because tests cannot use (sometimes) shaded guava CharSource
    @OnlyUsedByTests
    public @Nullable String getProfileString(String traceId) throws Exception {
        CharSource profile = getProfile(traceId);
        if (profile == null) {
            return null;
        }
        return profile.read();
    }
}
