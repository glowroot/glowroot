/*
 * Copyright 2012-2014 the original author or authors.
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

import org.glowroot.common.ScheduledRunnable;
import org.glowroot.config.ConfigService;
import org.glowroot.config.ProfilingConfig;
import org.glowroot.config.UserRecordingConfig;
import org.glowroot.markers.Singleton;
import org.glowroot.transaction.model.Transaction;

import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.concurrent.TimeUnit.NANOSECONDS;

/**
 * Profiles a percentage of transactions.
 * 
 * @author Trask Stalnaker
 * @since 0.5
 */
@Singleton
class ProfileScheduler {

    private final ScheduledExecutorService scheduledExecutor;
    private final ConfigService configService;
    private final Random random;

    ProfileScheduler(ScheduledExecutorService scheduledExecutor, ConfigService configService,
            Random random) {
        this.scheduledExecutor = scheduledExecutor;
        this.configService = configService;
        this.random = random;
    }

    void maybeScheduleProfilingUsingUser(Transaction transaction, String user) {
        UserRecordingConfig userRecordingConfig = configService.getUserRecordingConfig();
        if (!userRecordingConfig.isEnabled()) {
            return;
        }
        if (user.equalsIgnoreCase(userRecordingConfig.getUser())) {
            scheduleProfiling(transaction, userRecordingConfig.getProfileIntervalMillis());
        }
    }

    void maybeScheduleProfilingUsingPercentage(Transaction transaction) {
        ProfilingConfig profilingConfig = configService.getProfilingConfig();
        if (!profilingConfig.isEnabled()) {
            return;
        }
        double transactionPercentage = profilingConfig.getTransactionPercentage();
        // just optimization to check transactionPercentage != 0
        if (transactionPercentage != 0 && random.nextFloat() * 100 < transactionPercentage) {
            scheduleRandomizedProfiling(transaction,
                    configService.getProfilingConfig().getIntervalMillis());
        }
    }

    // schedules the first stack collection for configured interval after trace start (or
    // immediately, if trace duration already exceeds configured collection interval)
    private void scheduleProfiling(Transaction transaction, int intervalMillis) {
        ScheduledRunnable profileRunnable = new ProfileRunnable(transaction, false);
        long initialDelay = Math.max(0,
                intervalMillis - NANOSECONDS.toMillis(transaction.getDuration()));
        profileRunnable.scheduleWithFixedDelay(scheduledExecutor, initialDelay, intervalMillis,
                MILLISECONDS);
        transaction.setUserProfileRunnable(profileRunnable);
    }

    private void scheduleRandomizedProfiling(Transaction transaction, int intervalMillis) {
        long delayFromIntervalStart = (long) (random.nextFloat() * intervalMillis);
        long currentDuration = NANOSECONDS.toMillis(transaction.getDuration());
        if (delayFromIntervalStart < currentDuration) {
            // this is the soonest it can happen (delayFromNow will be zero below in this case)
            delayFromIntervalStart = currentDuration;
        }
        Runnable profileRunnable = new RandomizedProfileRunnable(transaction, scheduledExecutor,
                random, intervalMillis, delayFromIntervalStart);
        long delayFromNow = delayFromIntervalStart - currentDuration;
        scheduledExecutor.schedule(profileRunnable, delayFromNow, MILLISECONDS);
    }
}
