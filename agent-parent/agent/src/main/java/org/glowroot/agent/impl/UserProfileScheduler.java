/*
 * Copyright 2012-2015 the original author or authors.
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
package org.glowroot.agent.impl;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadInfo;
import java.lang.management.ThreadMXBean;
import java.util.Random;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableList;
import org.checkerframework.checker.nullness.qual.MonotonicNonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.glowroot.agent.config.ConfigService;
import org.glowroot.agent.model.Transaction;
import org.glowroot.agent.model.Transaction.OverrideSource;
import org.glowroot.common.config.UserRecordingConfig;
import org.glowroot.common.util.Cancellable;

import static java.util.concurrent.TimeUnit.MILLISECONDS;

public class UserProfileScheduler {

    private static final Logger logger = LoggerFactory.getLogger(UserProfileRunnable.class);

    private final ScheduledExecutorService scheduledExecutor;
    private final ConfigService configService;
    private final Random random;

    public UserProfileScheduler(ScheduledExecutorService scheduledExecutor,
            ConfigService configService, Random random) {
        this.scheduledExecutor = scheduledExecutor;
        this.configService = configService;
        this.random = random;
    }

    void maybeScheduleUserProfiling(Transaction transaction, String user) {
        UserRecordingConfig userRecordingConfig = configService.getUserRecordingConfig();
        ImmutableList<String> users = userRecordingConfig.users();
        if (users.isEmpty()) {
            return;
        }
        if (!TransactionCollector.containsIgnoreCase(users, user)) {
            return;
        }
        // for now lumping user recording into slow traces tab
        transaction.setSlowThresholdMillis(0, OverrideSource.USER_RECORDING);

        // schedule the first stack collection for configured interval after transaction start (or
        // immediately, if the transaction's total time already exceeds configured collection
        // interval)
        Integer intervalMillis = userRecordingConfig.profilingIntervalMillis();
        if (intervalMillis == null || intervalMillis <= 0) {
            return;
        }
        UserProfileRunnable userProfileRunnable =
                new UserProfileRunnable(transaction, intervalMillis);
        userProfileRunnable.scheduleFirst();
        transaction.setUserProfileRunnable(userProfileRunnable);
    }

    @VisibleForTesting
    class UserProfileRunnable implements Runnable, Cancellable {

        private final Transaction transaction;
        private final int intervalMillis;

        private volatile @MonotonicNonNull ScheduledFuture<?> currentFuture;
        private volatile long remainingInInterval;

        @VisibleForTesting
        UserProfileRunnable(Transaction transaction, int intervalMillis) {
            this.transaction = transaction;
            this.intervalMillis = intervalMillis;
        }

        @Override
        public void run() {
            if (transaction.isCompleted()) {
                // there is a small window between trace completion and cancellation of this command
                return;
            }
            try {
                runInternal();
            } catch (Throwable t) {
                logger.error(t.getMessage(), t);
            }
            try {
                scheduleNext();
            } catch (Throwable t) {
                // this is not good, no further stack trace will be captured for this transaction
                logger.error(t.getMessage(), t);
            }
        }

        @Override
        public void cancel() {
            if (currentFuture != null) {
                currentFuture.cancel(false);
            }
        }

        private void scheduleFirst() {
            long randomDelayFromIntervalStart = (long) (random.nextFloat() * intervalMillis);
            currentFuture =
                    scheduledExecutor.schedule(this, randomDelayFromIntervalStart, MILLISECONDS);
            remainingInInterval = intervalMillis - randomDelayFromIntervalStart;
        }

        private void scheduleNext() {
            long randomDelayFromIntervalStart = (long) (random.nextFloat() * intervalMillis);
            scheduledExecutor.schedule(this, remainingInInterval + randomDelayFromIntervalStart,
                    MILLISECONDS);
            remainingInInterval = intervalMillis - randomDelayFromIntervalStart;
        }

        private void runInternal() {
            ThreadMXBean threadBean = ManagementFactory.getThreadMXBean();
            ThreadInfo threadInfo =
                    threadBean.getThreadInfo(transaction.getThreadId(), Integer.MAX_VALUE);
            transaction.captureStackTrace(threadInfo,
                    configService.getAdvancedConfig().maxStackTraceSamplesPerTransaction(),
                    configService.getAdvancedConfig().timerWrapperMethods());
        }
    }
}
