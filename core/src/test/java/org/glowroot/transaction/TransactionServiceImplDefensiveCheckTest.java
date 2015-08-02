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

import java.util.List;

import com.google.common.base.Ticker;
import com.google.common.collect.ImmutableList;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import org.glowroot.common.Clock;
import org.glowroot.config.AdvancedConfig;
import org.glowroot.config.ConfigService;
import org.glowroot.config.TransactionConfig;
import org.glowroot.jvm.ThreadAllocatedBytes;
import org.glowroot.plugin.api.config.ConfigListener;
import org.glowroot.plugin.api.transaction.MessageSupplier;
import org.glowroot.plugin.api.transaction.TimerName;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Matchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

public class TransactionServiceImplDefensiveCheckTest {

    private TransactionServiceImpl transactionService;
    private List<Object> mocks;
    private ConfigService mockConfigService;

    @Before
    public void beforeEachTest() {
        TransactionRegistry transactionRegistry = mock(TransactionRegistry.class);
        TransactionCollector transactionCollector = mock(TransactionCollector.class);
        ConfigService configService = mock(ConfigService.class);
        TransactionConfig transactionConfig = TransactionConfig.builder().build();
        AdvancedConfig advancedConfig = AdvancedConfig.builder().build();
        when(configService.getTransactionConfig()).thenReturn(transactionConfig);
        when(configService.getAdvancedConfig()).thenReturn(advancedConfig);

        TimerNameCache timerNameCache = mock(TimerNameCache.class);
        ThreadAllocatedBytes threadAllocatedBytes = mock(ThreadAllocatedBytes.class);
        UserProfileScheduler userProfileScheduler = mock(UserProfileScheduler.class);
        Ticker ticker = mock(Ticker.class);
        Clock clock = mock(Clock.class);
        transactionService = TransactionServiceImpl.create(transactionRegistry,
                transactionCollector, configService, timerNameCache, threadAllocatedBytes,
                userProfileScheduler, ticker, clock);
        mocks = ImmutableList.of(transactionRegistry, transactionCollector, configService,
                timerNameCache, threadAllocatedBytes, userProfileScheduler, ticker, clock);
        mockConfigService = configService;
    }

    @After
    public void afterEachTest() {
        verify(mockConfigService).addConfigListener(any(ConfigListener.class));
        verifyNoMoreInteractions(mocks.toArray());
    }

    @Test
    public void testStartTransaction() {
        MessageSupplier messageSupplier = mock(MessageSupplier.class);
        TimerName timerName = mock(TimerName.class);
        assertThat(transactionService.startTransaction("test", "test", messageSupplier, null)
                .getClass().getSimpleName()).isEqualTo("NopTraceEntry");
        assertThat(transactionService.startTransaction("test", "test", null, timerName)
                .getClass().getSimpleName()).isEqualTo("NopTraceEntry");
        assertThat(transactionService.startTransaction("test", null, messageSupplier, timerName)
                .getClass().getSimpleName()).isEqualTo("NopTraceEntry");
        assertThat(transactionService.startTransaction(null, "test", messageSupplier, timerName)
                .getClass().getSimpleName()).isEqualTo("NopTraceEntry");
    }

    @Test
    public void testStartTraceEntry() {
        MessageSupplier messageSupplier = mock(MessageSupplier.class);
        TimerName timerName = mock(TimerName.class);
        assertThat(transactionService.startTraceEntry(messageSupplier, null)
                .getClass().getSimpleName()).isEqualTo("NopTraceEntry");
        assertThat(transactionService.startTraceEntry(null, timerName)
                .getClass().getSimpleName()).isEqualTo("NopTraceEntry");
    }

    @Test
    public void testStartTimer() {
        assertThat(transactionService.startTimer(null).getClass().getSimpleName())
                .isEqualTo("NopTimer");
    }

    @Test
    public void testAddTraceEntry() {
        transactionService.addTraceEntry(null);
    }

    @Test
    public void testAddTransactionCustomAttribute() {
        transactionService.addTransactionCustomAttribute(null, null);
    }

    @Test
    public void testSetTraceStoreThreshold() {
        transactionService.setSlowTraceThreshold(-1, SECONDS);
        transactionService.setSlowTraceThreshold(1, null);
    }
}
