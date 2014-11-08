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
import java.sql.SQLException;
import java.util.List;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.base.Stopwatch;
import com.google.common.collect.Lists;
import org.checkerframework.checker.nullness.qual.Nullable;

import org.glowroot.GlowrootModule;
import org.glowroot.collector.TraceCreator;
import org.glowroot.collector.TraceWriter;
import org.glowroot.collector.TransactionCollectorImpl;
import org.glowroot.common.Clock;
import org.glowroot.common.Ticker;
import org.glowroot.container.common.ObjectMappers;
import org.glowroot.container.trace.ProfileNode;
import org.glowroot.container.trace.Trace;
import org.glowroot.container.trace.TraceEntry;
import org.glowroot.container.trace.TraceService;
import org.glowroot.local.store.AggregateDao;
import org.glowroot.local.store.GaugePointDao;
import org.glowroot.local.store.TraceDao;
import org.glowroot.local.ui.TraceCommonService;
import org.glowroot.local.ui.TraceExportHttpService;
import org.glowroot.transaction.TransactionRegistry;

import static java.util.concurrent.TimeUnit.SECONDS;

// even though this is thread safe, it is not useful for running tests in parallel since
// getLastTrace() and others are not scoped to a particular test
class LocalTraceService extends TraceService {

    private static final ObjectMapper mapper = ObjectMappers.create();

    private final AggregateDao aggregateDao;
    private final TraceDao traceDao;
    private final GaugePointDao gaugePointDao;
    private final TraceCommonService traceCommonService;
    private final TraceExportHttpService traceExportHttpService;
    private final TransactionCollectorImpl transactionCollector;
    private final TransactionRegistry transactionRegistry;
    private final Clock clock;
    private final Ticker ticker;

    LocalTraceService(GlowrootModule glowrootModule) {
        aggregateDao = glowrootModule.getStorageModule().getAggregateDao();
        traceDao = glowrootModule.getStorageModule().getTraceDao();
        gaugePointDao = glowrootModule.getStorageModule().getGaugePointDao();
        traceCommonService = glowrootModule.getUiModule().getTraceCommonService();
        traceExportHttpService = glowrootModule.getUiModule().getTraceExportHttpService();
        transactionCollector = glowrootModule.getCollectorModule().getTransactionCollector();
        transactionRegistry = glowrootModule.getTransactionModule().getTransactionRegistry();
        clock = glowrootModule.getClock();
        ticker = glowrootModule.getTicker();
    }

    @Override
    public int getNumPendingCompleteTransactions() {
        return transactionCollector.getPendingCompleteTransactions().size();
    }

    @Override
    public long getNumTraces() throws SQLException {
        return traceDao.count();
    }

    @Override
    public InputStream getTraceExport(String id) throws Exception {
        return new ByteArrayInputStream(traceExportHttpService.getExportBytes(id));
    }

    @Override
    @Nullable
    public Trace getLastTrace() throws Exception {
        // check pending traces first
        List<org.glowroot.transaction.model.Transaction> pendingTransactions =
                Lists.newArrayList(transactionCollector.getPendingCompleteTransactions());
        if (pendingTransactions.size() > 1) {
            throw new AssertionError("Unexpected multiple pending traces during test");
        }
        org.glowroot.collector.Trace trace = null;
        if (pendingTransactions.size() == 1) {
            trace = traceCommonService.getTrace(pendingTransactions.get(0).getId());
        } else {
            // no pending traces, so check stored traces
            trace = traceDao.getLastTrace();
        }
        if (trace == null) {
            return null;
        }
        return ObjectMappers.readRequiredValue(mapper, TraceWriter.toString(trace),
                Trace.class);
    }

    @Override
    @Nullable
    public List<TraceEntry> getEntries(String traceId) throws Exception {
        String entries = traceCommonService.getEntriesString(traceId);
        if (entries == null) {
            return null;
        }
        return mapper.readValue(entries, new TypeReference<List<TraceEntry>>() {});
    }

    @Override
    @Nullable
    public ProfileNode getProfile(String traceId) throws Exception {
        String profile = traceCommonService.getProfileString(traceId);
        if (profile == null) {
            return null;
        }
        return mapper.readValue(profile, ProfileNode.class);
    }

    @Override
    @Nullable
    public ProfileNode getOutlierProfile(String traceId) throws Exception {
        String profile = traceCommonService.getOutlierProfileString(traceId);
        if (profile == null) {
            return null;
        }
        return mapper.readValue(profile, ProfileNode.class);
    }

    @Override
    public void deleteAll() {
        aggregateDao.deleteAll();
        traceDao.deleteAll();
        gaugePointDao.deleteAll();
    }

    @Override
    @Nullable
    protected Trace getActiveTrace() throws Exception {
        List<org.glowroot.transaction.model.Transaction> traces =
                Lists.newArrayList(transactionRegistry.getTransactions());
        if (traces.isEmpty()) {
            return null;
        } else if (traces.size() > 1) {
            throw new IllegalStateException("Unexpected number of active traces");
        } else {
            org.glowroot.collector.Trace trace = TraceCreator.createActiveTrace(traces.get(0),
                    clock.currentTimeMillis(), ticker.read());
            return ObjectMappers.readRequiredValue(mapper, TraceWriter.toString(trace),
                    Trace.class);
        }
    }

    void assertNoActiveTransactions() throws Exception {
        Stopwatch stopwatch = Stopwatch.createStarted();
        // if interruptAppUnderTest() was used to terminate an active transaction, it may take a few
        // milliseconds to interrupt the thread and end the active transaction
        while (stopwatch.elapsed(SECONDS) < 2) {
            if (transactionRegistry.getTransactions().isEmpty()) {
                return;
            }
        }
        throw new AssertionError("There are still active transactions");
    }
}
