/*
 * Copyright 2014 the original author or authors.
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

import java.util.Random;
import java.util.concurrent.ScheduledExecutorService;

import com.google.common.base.MoreObjects;

import org.glowroot.markers.ThreadSafe;
import org.glowroot.transaction.model.Transaction;

import static java.util.concurrent.TimeUnit.MILLISECONDS;

/**
 * @author Trask Stalnaker
 * @since 0.5
 */
// uniform randomized timings creates better aggregates
@ThreadSafe
class RandomizedProfileRunnable implements Runnable {

    private final Transaction transaction;
    private final ScheduledExecutorService scheduledExecutor;
    private final Random random;
    private final long intervalMillis;
    private volatile long currentDelayFromIntervalStart;

    RandomizedProfileRunnable(Transaction transaction, ScheduledExecutorService scheduledExecutor,
            Random random, long intervalMillis, long initialDelayFromIntervalStart) {
        this.transaction = transaction;
        this.scheduledExecutor = scheduledExecutor;
        this.random = random;
        this.intervalMillis = intervalMillis;
        this.currentDelayFromIntervalStart = initialDelayFromIntervalStart;
    }

    @Override
    public void run() {
        if (transaction.isCompleted()) {
            // there is a small window between trace completion and cancellation of this command
            return;
        }
        transaction.captureStackTrace(false);
        long nextDelayFromIntervalStart = (long) (random.nextFloat() * intervalMillis);
        // finish up current interval (intervalMillis - currentDelayFromIntervalStart)
        // and add on nextDelayFromIntervalStart before capturing next sample
        scheduledExecutor.schedule(this, intervalMillis - currentDelayFromIntervalStart
                + nextDelayFromIntervalStart, MILLISECONDS);
        currentDelayFromIntervalStart = nextDelayFromIntervalStart;
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
                .add("transaction", transaction)
                .add("intervalMillis", intervalMillis)
                .add("currentDelayFromIntervalStart", currentDelayFromIntervalStart)
                .toString();
    }
}
