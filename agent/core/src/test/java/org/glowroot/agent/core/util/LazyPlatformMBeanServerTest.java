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
package org.glowroot.agent.core.util;

import java.lang.management.ManagementFactory;

import javax.management.ObjectName;

import com.google.common.base.Stopwatch;
import com.google.common.base.Ticker;
import org.junit.Test;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.stubbing.Answer;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

public class LazyPlatformMBeanServerTest {

    static {
        // make sure java.util.logging is already initialized so that this test doesn't possibly
        // clobber other tests
        ManagementFactory.getPlatformMBeanServer();
    }

    @Test
    public void testJBossModules() throws Exception {
        String julManager = System.getProperty("java.util.logging.manager");
        System.setProperty("java.util.logging.manager", "abc");
        try {
            LazyPlatformMBeanServer lazyPlatformMBeanServer = new LazyPlatformMBeanServer(true);
            lazyPlatformMBeanServer.getMBeanInfo(ObjectName.getInstance("java.lang:type=Memory"));
        } finally {
            if (julManager == null) {
                System.clearProperty("java.util.logging.manager");
            } else {
                System.setProperty("java.util.logging.manager", julManager);
            }
        }
    }

    @Test
    public void testWaitForJBossModuleInitialization() throws Exception {
        // given
        Ticker ticker = mock(Ticker.class);
        Stopwatch stopwatch = Stopwatch.createUnstarted(ticker);
        when(ticker.read())
                .thenReturn(0L)
                .thenReturn(SECONDS.toNanos(60) - 1)
                .thenAnswer(new Answer<Long>() {
                    @Override
                    public Long answer(InvocationOnMock invocation) throws Throwable {
                        System.setProperty("java.util.logging.manager", "abc");
                        return SECONDS.toNanos(60);
                    }
                });
        // when
        String julManager = System.getProperty("java.util.logging.manager");
        try {
            LazyPlatformMBeanServer.waitForJBossModuleInitialization(stopwatch);
        } finally {
            if (julManager == null) {
                System.clearProperty("java.util.logging.manager");
            } else {
                System.setProperty("java.util.logging.manager", julManager);
            }
        }
        // then
        verify(ticker, times(3)).read();
        verifyNoMoreInteractions(ticker);
    }
}
