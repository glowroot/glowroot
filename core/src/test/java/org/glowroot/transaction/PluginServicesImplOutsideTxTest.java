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
import org.glowroot.common.Clock;
import org.glowroot.common.Ticker;
import org.glowroot.config.AdvancedConfig;
import org.glowroot.config.ConfigService;
import org.glowroot.config.GeneralConfig;
import org.glowroot.config.PluginDescriptor;
import org.glowroot.jvm.ThreadAllocatedBytes;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class PluginServicesImplOutsideTxTest {

    private PluginServicesImpl pluginServices;

    @Before
    public void beforeEachTest() {
        TransactionRegistry transactionRegistry = mock(TransactionRegistry.class);
        TransactionCollector transactionCollector = mock(TransactionCollector.class);
        ConfigService configService = mock(ConfigService.class);
        GeneralConfig generalConfig = mock(GeneralConfig.class);
        AdvancedConfig advancedConfig = mock(AdvancedConfig.class);
        when(configService.getGeneralConfig()).thenReturn(generalConfig);
        when(configService.getAdvancedConfig()).thenReturn(advancedConfig);

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
    public void testStartTraceEntry() {
        MessageSupplier messageSupplier = mock(MessageSupplier.class);
        MetricName metricName = mock(MetricName.class);
        assertThat(pluginServices.startTraceEntry(messageSupplier, metricName)
                .getClass().getSimpleName()).isEqualTo("NopTraceEntry");
    }

    @Test
    public void testStartTransactionMetric() {
        MetricName metricName = mock(MetricName.class);
        assertThat(pluginServices.startTransactionMetric(metricName).getClass().getSimpleName())
                .isEqualTo("NopTransactionMetric");
    }

    @Test
    public void testAddTraceEntry() {
        pluginServices.addTraceEntry(ErrorMessage.from("z"));
    }

    @Test
    public void testSetTransactionType() {
        pluginServices.setTransactionType("tt");
    }

    @Test
    public void testSetTransactionName() {
        pluginServices.setTransactionName("tn");
    }

    @Test
    public void testSetTransactionError() {
        pluginServices.setTransactionError("te");
    }

    @Test
    public void testSetTransactionUser() {
        pluginServices.setTransactionUser("tu");
    }

    @Test
    public void testSetTransactionCustomAttribute() {
        pluginServices.setTransactionCustomAttribute("x", null);
    }

    @Test
    public void testSetTraceStoreThreshold() {
        pluginServices.setTraceStoreThreshold(1, SECONDS);
    }

    @Test
    public void testIsInTransaction() {
        assertThat(pluginServices.isInTransaction()).isEqualTo(false);
    }
}
