/*
 * Copyright 2013-2014 the original author or authors.
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
package org.glowroot.common;

import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * @author Trask Stalnaker
 * @since 0.5
 */
public abstract class ScheduledRunnable implements Runnable {

    private static final Logger logger = LoggerFactory.getLogger(ScheduledRunnable.class);

    /*@MonotonicNonNull*/
    private volatile ScheduledFuture<?> future;

    public void schedule(ScheduledExecutorService scheduledExecutor, long delay, TimeUnit unit) {
        if (future != null) {
            logger.error("command has already been scheduled: {}", this);
            return;
        }
        future = scheduledExecutor.schedule(this, delay, unit);
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
        } catch (Exception e) {
            // log and terminate successfully
            logger.error(e.getMessage(), e);
        } catch (Throwable t) {
            // log and throw marker exception to terminate subsequent scheduled executions
            // (see ScheduledExecutorService.scheduleAtFixedRate())
            logger.error(t.getMessage(), t);
            throw new TerminateSubsequentExecutionsException();
        }
    }

    public void cancel() {
        if (future != null) {
            future.cancel(false);
        }
    }

    protected abstract void runInternal();

    @SuppressWarnings("serial")
    public static class TerminateSubsequentExecutionsException extends RuntimeException {}
}
