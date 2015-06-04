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

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.base.Ticker;
import com.google.common.collect.Iterables;
import com.google.common.io.CharSource;
import org.immutables.value.Value;

import org.glowroot.collector.EntriesChunkSourceCreator;
import org.glowroot.collector.ProfileChunkSourceCreator;
import org.glowroot.collector.Trace;
import org.glowroot.collector.TraceCreator;
import org.glowroot.common.ChunkSource;
import org.glowroot.common.Clock;
import org.glowroot.common.ObjectMappers;
import org.glowroot.local.store.TraceDao;
import org.glowroot.transaction.TransactionCollector;
import org.glowroot.transaction.TransactionRegistry;
import org.glowroot.transaction.model.Transaction;

class TraceCommonService {

    private static final ObjectMapper mapper = ObjectMappers.create();

    private final TraceDao traceDao;
    private final TransactionRegistry transactionRegistry;
    private final TransactionCollector transactionCollector;
    private final Clock clock;
    private final Ticker ticker;

    TraceCommonService(TraceDao traceDao, TransactionRegistry transactionRegistry,
            TransactionCollector transactionCollectorImpl, Clock clock, Ticker ticker) {
        this.traceDao = traceDao;
        this.transactionRegistry = transactionRegistry;
        this.transactionCollector = transactionCollectorImpl;
        this.clock = clock;
        this.ticker = ticker;
    }

    @Nullable
    Trace getTrace(String traceId) throws Exception {
        // check active traces first, then pending traces, and finally stored traces
        // to make sure that the trace is not missed if it is in transition between these states
        for (Transaction transaction : Iterables.concat(transactionRegistry.getTransactions(),
                transactionCollector.getPendingTransactions())) {
            if (transaction.getId().equals(traceId)) {
                return createTrace(transaction);
            }
        }
        return traceDao.readTrace(traceId);
    }

    // overwritten entries will return {"overwritten":true}
    // expired (not found) trace will return {"expired":true}
    @Nullable
    ChunkSource getEntries(String traceId) throws SQLException {
        // check active traces first, then pending traces, and finally stored traces
        // to make sure that the trace is not missed if it is in transition between these states
        for (Transaction transaction : Iterables.concat(transactionRegistry.getTransactions(),
                transactionCollector.getPendingTransactions())) {
            if (transaction.getId().equals(traceId)) {
                return createEntries(transaction);
            }
        }
        return toNullableChunkSource(traceDao.readEntries(traceId));
    }

    // overwritten profile will return {"overwritten":true}
    // expired (not found) trace will return {"expired":true}
    @Nullable
    ChunkSource getProfile(String traceId) throws Exception {
        // check active traces first, then pending traces, and finally stored traces
        // to make sure that the trace is not missed if it is in transition between these states
        for (Transaction transaction : Iterables.concat(transactionRegistry.getTransactions(),
                transactionCollector.getPendingTransactions())) {
            if (transaction.getId().equals(traceId)) {
                return createProfile(transaction);
            }
        }
        return toNullableChunkSource(traceDao.readProfile(traceId));
    }

    @Nullable
    TraceExport getExport(String traceId) throws Exception {
        // check active traces first, then pending traces, and finally stored traces
        // to make sure that the trace is not missed if it is in transition between these states
        for (Transaction transaction : Iterables.concat(transactionRegistry.getTransactions(),
                transactionCollector.getPendingTransactions())) {
            if (transaction.getId().equals(traceId)) {
                Trace trace = createTrace(transaction);
                return TraceExport.builder()
                        .trace(trace)
                        .traceJson(mapper.writeValueAsString(trace))
                        .entries(createEntries(transaction))
                        .profile(createProfile(transaction))
                        .build();
            }
        }
        Trace trace = traceDao.readTrace(traceId);
        if (trace == null) {
            return null;
        }
        return TraceExport.builder()
                .trace(trace)
                .traceJson(mapper.writeValueAsString(trace))
                .entries(toNullableChunkSource(traceDao.readEntries(traceId)))
                .profile(toNullableChunkSource(traceDao.readProfile(traceId)))
                .build();
    }

    private Trace createTrace(Transaction transaction) throws IOException {
        if (transaction.isCompleted()) {
            return TraceCreator.createCompletedTrace(transaction);
        } else {
            return TraceCreator.createActiveTrace(transaction, clock.currentTimeMillis(),
                    ticker.read());
        }
    }

    private @Nullable ChunkSource createEntries(Transaction active) {
        return EntriesChunkSourceCreator.createEntriesChunkSource(active.getEntries(),
                active.getStartTick(), ticker.read());
    }

    private @Nullable ChunkSource createProfile(Transaction active) throws IOException {
        return ProfileChunkSourceCreator.createProfileChunkSource(active.getProfile());
    }

    private @Nullable ChunkSource toNullableChunkSource(@Nullable CharSource charSource) {
        if (charSource == null) {
            return null;
        }
        return ChunkSource.from(charSource);
    }

    @Value.Immutable
    static abstract class TraceExportBase {

        abstract Trace trace();
        abstract String traceJson();
        abstract @Nullable ChunkSource entries();
        abstract @Nullable ChunkSource profile();
    }
}
