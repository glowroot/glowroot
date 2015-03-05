/*
 * Copyright 2015 the original author or authors.
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
package org.glowroot.transaction;

import com.google.common.collect.ImmutableList;
import org.junit.Before;
import org.junit.Test;

import org.glowroot.api.ErrorMessage;
import org.glowroot.api.MessageSupplier;
import org.glowroot.api.MetricName;
import org.glowroot.api.TraceEntry;
import org.glowroot.common.Clock;
import org.glowroot.common.Ticker;
import org.glowroot.config.AdvancedConfig;
import org.glowroot.config.ConfigService;
import org.glowroot.config.GeneralConfig;
import org.glowroot.config.PluginDescriptor;
import org.glowroot.jvm.ThreadAllocatedBytes;
import org.glowroot.transaction.model.Transaction;

import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class PluginServicesImplMoreDefensiveCheckTest {

    private PluginServicesImpl pluginServices;
    private Transaction mockTransaction;

    @Before
    public void beforeEachTest() {
        TransactionRegistry transactionRegistry = mock(TransactionRegistry.class);
        TransactionCollector transactionCollector = mock(TransactionCollector.class);
        mockTransaction = mock(Transaction.class);
        ConfigService configService = mock(ConfigService.class);
        GeneralConfig generalConfig = mock(GeneralConfig.class);
        AdvancedConfig advancedConfig = mock(AdvancedConfig.class);
        when(transactionRegistry.getCurrentTransaction()).thenReturn(mockTransaction);
        when(configService.getGeneralConfig()).thenReturn(generalConfig);
        when(configService.getAdvancedConfig()).thenReturn(advancedConfig);
        when(advancedConfig.maxTraceEntriesPerTransaction()).thenReturn(100);

        MetricNameCache metricNameCache = mock(MetricNameCache.class);
        ThreadAllocatedBytes threadAllocatedBytes = mock(ThreadAllocatedBytes.class);
        UserProfileScheduler userProfileScheduler = mock(UserProfileScheduler.class);
        Ticker ticker = mock(Ticker.class);
        Clock clock = mock(Clock.class);
        pluginServices = PluginServicesImpl.create(transactionRegistry, transactionCollector,
                configService, metricNameCache, threadAllocatedBytes, userProfileScheduler, ticker,
                clock, ImmutableList.<PluginDescriptor>of(), null);
    }

    @Test
    public void testEndWithStackTrace() {
        MessageSupplier messageSupplier = mock(MessageSupplier.class);
        MetricName metricName = mock(MetricName.class);
        TraceEntry traceEntry = pluginServices.startTraceEntry(messageSupplier, metricName);
        traceEntry.endWithStackTrace(-1, MILLISECONDS);
    }

    @Test
    public void testEndWithError() {
        MessageSupplier messageSupplier = mock(MessageSupplier.class);
        MetricName metricName = mock(MetricName.class);
        TraceEntry traceEntry = pluginServices.startTraceEntry(messageSupplier, metricName);
        traceEntry.endWithError(null);
    }

    @Test
    public void testEndDummyWithStackTrace() {
        when(mockTransaction.getEntryCount()).thenReturn(100);
        MessageSupplier messageSupplier = mock(MessageSupplier.class);
        MetricName metricName = mock(MetricName.class);
        TraceEntry traceEntry = pluginServices.startTraceEntry(messageSupplier, metricName);
        traceEntry.endWithStackTrace(-1, MILLISECONDS);
    }

    @Test
    public void testEndDummyWithError() {
        when(mockTransaction.getEntryCount()).thenReturn(100);
        MessageSupplier messageSupplier = mock(MessageSupplier.class);
        MetricName metricName = mock(MetricName.class);
        TraceEntry traceEntry = pluginServices.startTraceEntry(messageSupplier, metricName);
        traceEntry.endWithError(null);
    }

    @Test
    public void testEndDummyWithStackTraceGood() {
        when(mockTransaction.getEntryCount()).thenReturn(100);
        MessageSupplier messageSupplier = mock(MessageSupplier.class);
        MetricName metricName = mock(MetricName.class);
        TraceEntry traceEntry = pluginServices.startTraceEntry(messageSupplier, metricName);
        traceEntry.endWithStackTrace(1, MILLISECONDS);
    }

    @Test
    public void testEndDummyWithErrorGood() {
        when(mockTransaction.getEntryCount()).thenReturn(100);
        MessageSupplier messageSupplier = mock(MessageSupplier.class);
        MetricName metricName = mock(MetricName.class);
        TraceEntry traceEntry = pluginServices.startTraceEntry(messageSupplier, metricName);
        traceEntry.endWithError(mock(ErrorMessage.class));
    }
}
