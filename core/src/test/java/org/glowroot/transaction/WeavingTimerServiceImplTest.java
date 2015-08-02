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

import org.junit.Before;
import org.junit.Test;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.stubbing.Answer;

import org.glowroot.config.AdvancedConfig;
import org.glowroot.config.ConfigService;
import org.glowroot.plugin.api.config.ConfigListener;
import org.glowroot.transaction.model.TimerImpl;
import org.glowroot.transaction.model.Transaction;
import org.glowroot.weaving.WeavingTimerService.WeavingTimer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Matchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class WeavingTimerServiceImplTest {

    private ConfigService configService;
    private TimerNameCache timerNameCache;

    @Before
    public void beforeEachTest() {
        configService = mock(ConfigService.class);
        timerNameCache = new TimerNameCache();
        doAnswer(new Answer<Void>() {
            @Override
            public Void answer(InvocationOnMock invocation) throws Throwable {
                ConfigListener listener = invocation.getArgumentAt(0, ConfigListener.class);
                listener.onChange();
                return null;
            }
        }).when(configService).addConfigListener(any(ConfigListener.class));
        AdvancedConfig advancedConfig = AdvancedConfig.builder().weavingTimer(true).build();
        when(configService.getAdvancedConfig()).thenReturn(advancedConfig);
    }

    @Test
    public void testWithNoCurrentTransaction() {
        // given
        TransactionRegistry transactionRegistry = mock(TransactionRegistry.class);
        WeavingTimerServiceImpl weavingTimerServiceImpl =
                new WeavingTimerServiceImpl(transactionRegistry, configService, timerNameCache);
        // when
        WeavingTimer weavingTimer = weavingTimerServiceImpl.start();
        // then
        assertThat(weavingTimer.getClass().getSimpleName()).isEqualTo("NopWeavingTimer");
    }

    @Test
    public void testWithCurrentTransactionButNoCurrentTimer() {
        // given
        TransactionRegistry transactionRegistry = mock(TransactionRegistry.class);
        Transaction transaction = mock(Transaction.class);
        when(transactionRegistry.getCurrentTransaction()).thenReturn(transaction);
        WeavingTimerServiceImpl weavingTimerServiceImpl =
                new WeavingTimerServiceImpl(transactionRegistry, configService, timerNameCache);
        // when
        WeavingTimer weavingTimer = weavingTimerServiceImpl.start();
        // then
        assertThat(weavingTimer.getClass().getSimpleName()).isEqualTo("NopWeavingTimer");
    }

    @Test
    public void testWithCurrentTransactionAndCurrentTimer() {
        // given
        TransactionRegistry transactionRegistry = mock(TransactionRegistry.class);
        Transaction transaction = mock(Transaction.class);
        TimerImpl currentTimer = mock(TimerImpl.class);
        when(transactionRegistry.getCurrentTransaction()).thenReturn(transaction);
        when(transaction.getCurrentTimer()).thenReturn(currentTimer);
        WeavingTimerServiceImpl weavingTimerServiceImpl =
                new WeavingTimerServiceImpl(transactionRegistry, configService, timerNameCache);
        // when
        WeavingTimer weavingTimer = weavingTimerServiceImpl.start();
        // then
        assertThat(weavingTimer.getClass().getSimpleName()).isNotEqualTo("NopWeavingTimer");
    }
}
