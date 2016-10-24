/*
 * Copyright 2013-2016 the original author or authors.
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

import org.checkerframework.checker.nullness.qual.MonotonicNonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public abstract class ScheduledRunnable implements Runnable, Cancellable {

    private static final Logger logger = LoggerFactory.getLogger(ScheduledRunnable.class);

    private volatile @MonotonicNonNull ScheduledFuture<?> future;

    public void scheduleWithFixedDelay(ScheduledExecutorService scheduledExecutor, long period,
            TimeUnit unit) {
        scheduleWithFixedDelay(scheduledExecutor, 0, period, unit);
    }

    public void scheduleWithFixedDelay(ScheduledExecutorService scheduledExecutor,
            long initialDelay, long period, TimeUnit unit) {
        if (future != null) {
            logger.error("command has already been scheduled: {}", this);
            return;
        }
        future = scheduledExecutor.scheduleWithFixedDelay(this, initialDelay, period, unit);
    }

    @Override
    public void run() {
        try {
            runInternal();
        } catch (TerminateSubsequentExecutionsException e) {
            // cancel this scheduled runnable
            // log exception at debug level
            logger.debug(e.getMessage(), e);
            throw e;
        } catch (Throwable t) {
            // log and return successfully so it will continue to run
            logger.error(t.getMessage(), t);
        }
    }

    @Override
    public void cancel() {
        if (future != null) {
            future.cancel(false);
        }
    }

    protected abstract void runInternal() throws Exception;

    // marker exception used to terminate subsequent scheduled executions
    // (see ScheduledExecutorService.scheduleWithFixedDelay())
    @SuppressWarnings("serial")
    public static class TerminateSubsequentExecutionsException extends RuntimeException {}
}
