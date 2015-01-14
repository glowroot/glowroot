/*
 * Copyright 2012-2015 the original author or authors.
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

import javax.annotation.Nullable;

import com.google.common.collect.Iterables;
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
        // check active traces first, then pending traces, and finally stored traces
        // to make sure that the trace is not missed if it is in transition between these states
        for (Transaction transaction : Iterables.concat(transactionRegistry.getTransactions(),
                transactionCollectorImpl.getPendingTransactions())) {
            if (transaction.getId().equals(traceId)) {
                return createTrace(transaction);
            }
        }
        return traceDao.readTrace(traceId);
    }

    // overwritten entries will return {"overwritten":true}
    // expired trace will return {"expired":true}
    @Nullable
    CharSource getEntries(String traceId) throws SQLException {
        // check active traces first, then pending traces, and finally stored traces
        // to make sure that the trace is not missed if it is in transition between these states
        for (Transaction transaction : Iterables.concat(transactionRegistry.getTransactions(),
                transactionCollectorImpl.getPendingTransactions())) {
            if (transaction.getId().equals(traceId)) {
                return createEntries(transaction);
            }
        }
        return traceDao.readEntries(traceId);
    }

    // overwritten profile will return {"overwritten":true}
    // expired trace will return {"expired":true}
    @Nullable
    CharSource getProfile(String traceId) throws Exception {
        // check active traces first, then pending traces, and finally stored traces
        // to make sure that the trace is not missed if it is in transition between these states
        for (Transaction transaction : Iterables.concat(transactionRegistry.getTransactions(),
                transactionCollectorImpl.getPendingTransactions())) {
            if (transaction.getId().equals(traceId)) {
                return createProfile(transaction);
            }
        }
        return traceDao.readProfile(traceId);
    }

    @Nullable
    TraceExport getExport(String traceId) throws Exception {
        // check active traces first, then pending traces, and finally stored traces
        // to make sure that the trace is not missed if it is in transition between these states
        for (Transaction transaction : Iterables.concat(transactionRegistry.getTransactions(),
                transactionCollectorImpl.getPendingTransactions())) {
            if (transaction.getId().equals(traceId)) {
                Trace trace = createTrace(transaction);
                return new TraceExport(trace, TraceWriter.toString(trace),
                        createEntries(transaction), createProfile(transaction));
            }
        }
        Trace trace = traceDao.readTrace(traceId);
        if (trace == null) {
            return null;
        }
        return new TraceExport(trace, TraceWriter.toString(trace), traceDao.readEntries(traceId),
                traceDao.readProfile(traceId));
    }

    private Trace createTrace(Transaction transaction) throws IOException {
        if (transaction.isCompleted()) {
            return TraceCreator.createCompletedTrace(transaction);
        } else {
            return TraceCreator.createActiveTrace(transaction, clock.currentTimeMillis(),
                    ticker.read());
        }
    }

    private CharSource createEntries(Transaction active) {
        return EntriesCharSourceCreator.createEntriesCharSource(active.getEntriesCopy(),
                active.getStartTick(), ticker.read());
    }

    private @Nullable CharSource createProfile(Transaction active) throws IOException {
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
}
