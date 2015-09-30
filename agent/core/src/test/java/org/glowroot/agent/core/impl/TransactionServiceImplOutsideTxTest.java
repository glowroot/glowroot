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
package org.glowroot.agent.core.impl;

import com.google.common.base.Ticker;
import org.junit.Before;
import org.junit.Test;

import org.glowroot.agent.core.config.ConfigService;
import org.glowroot.agent.core.util.ThreadAllocatedBytes;
import org.glowroot.agent.plugin.api.transaction.MessageSupplier;
import org.glowroot.agent.plugin.api.transaction.TimerName;
import org.glowroot.common.config.AdvancedConfig;
import org.glowroot.common.config.ImmutableAdvancedConfig;
import org.glowroot.common.config.ImmutableTransactionConfig;
import org.glowroot.common.config.TransactionConfig;
import org.glowroot.common.util.Clock;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class TransactionServiceImplOutsideTxTest {

    private TransactionServiceImpl transactionService;

    @Before
    public void beforeEachTest() {
        TransactionRegistry transactionRegistry = mock(TransactionRegistry.class);
        TransactionCollector transactionCollector = mock(TransactionCollector.class);
        ConfigService configService = mock(ConfigService.class);
        TransactionConfig transactionConfig = ImmutableTransactionConfig.builder().build();
        AdvancedConfig advancedConfig = ImmutableAdvancedConfig.builder().build();
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
    }

    @Test
    public void testStartTraceEntry() {
        MessageSupplier messageSupplier = mock(MessageSupplier.class);
        TimerName timerName = mock(TimerName.class);
        assertThat(transactionService.startTraceEntry(messageSupplier, timerName).getClass()
                .getSimpleName()).isEqualTo("NopTraceEntry");
    }

    @Test
    public void testStartTimer() {
        TimerName timerName = mock(TimerName.class);
        assertThat(transactionService.startTimer(timerName).getClass().getSimpleName())
                .isEqualTo("NopTimer");
    }

    @Test
    public void testAddErrorEntry() {
        transactionService.addErrorEntry("z");
    }

    @Test
    public void testSetTransactionType() {
        transactionService.setTransactionType("tt");
    }

    @Test
    public void testSetTransactionName() {
        transactionService.setTransactionName("tn");
    }

    @Test
    public void testSetTransactionError() {
        transactionService.setTransactionError("te");
    }

    @Test
    public void testSetTransactionUser() {
        transactionService.setTransactionUser("tu");
    }

    @Test
    public void testAddTransactionAttribute() {
        transactionService.addTransactionAttribute("x", null);
    }

    @Test
    public void testSetTraceStoreThreshold() {
        transactionService.setTransactionSlowThreshold(1, SECONDS);
    }

    @Test
    public void testIsInTransaction() {
        assertThat(transactionService.isInTransaction()).isEqualTo(false);
    }
}
