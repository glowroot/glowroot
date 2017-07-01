/*
 * Copyright 2015-2017 the original author or authors.
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
package org.glowroot.common.util;

import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import org.junit.Before;
import org.junit.Test;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

public class ScheduledRunnableTest {

    private ScheduledExecutorService scheduledExecutorService;

    @Before
    @SuppressWarnings("unchecked")
    public void beforeEachTest() {
        scheduledExecutorService = mock(ScheduledExecutorService.class);
        when(scheduledExecutorService.scheduleWithFixedDelay(any(Runnable.class), anyLong(),
                anyLong(), any(TimeUnit.class))).thenReturn(mock(ScheduledFuture.class));
        when(scheduledExecutorService.scheduleAtFixedRate(any(Runnable.class), anyLong(), anyLong(),
                any(TimeUnit.class))).thenReturn(mock(ScheduledFuture.class));
    }

    @Test
    public void testScheduleWithFixedDelay() {
        // given
        TestScheduledRunnable testScheduledRunnable = new TestScheduledRunnable();
        // when
        testScheduledRunnable.scheduleWithFixedDelay(scheduledExecutorService, 1, SECONDS);
        // then
        verify(scheduledExecutorService).scheduleWithFixedDelay(testScheduledRunnable, 0, 1,
                SECONDS);
        verifyNoMoreInteractions(scheduledExecutorService);
    }

    @Test
    public void testScheduleWithFixedDelayCalledTwice() {
        // given
        TestScheduledRunnable testScheduledRunnable = new TestScheduledRunnable();
        // when
        testScheduledRunnable.scheduleWithFixedDelay(scheduledExecutorService, 1, SECONDS);
        testScheduledRunnable.scheduleWithFixedDelay(scheduledExecutorService, 1, SECONDS);
        // then
        verify(scheduledExecutorService).scheduleWithFixedDelay(testScheduledRunnable, 0, 1,
                SECONDS);
        verifyNoMoreInteractions(scheduledExecutorService);
    }

    @Test
    public void testCancelWithNoFuture() {
        // given
        TestScheduledRunnable testScheduledRunnable = new TestScheduledRunnable();
        // when
        testScheduledRunnable.cancel();
        // then
        // do nothing
    }

    @Test
    public void testExceptionalScheduledRunnable() {
        // given
        ExceptionalScheduledRunnable testScheduledRunnable = new ExceptionalScheduledRunnable();
        // when
        testScheduledRunnable.run();
        // then
        // do not throw exception
    }

    private static class TestScheduledRunnable extends ScheduledRunnable {

        @Override
        protected void runInternal() {}
    }

    private static class ExceptionalScheduledRunnable extends ScheduledRunnable {

        @Override
        protected void runInternal() {
            throw new RuntimeException("This is a test");
        }
    }
}
