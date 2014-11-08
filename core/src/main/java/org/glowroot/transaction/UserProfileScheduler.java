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

import java.util.concurrent.ScheduledExecutorService;

import org.glowroot.common.ScheduledRunnable;
import org.glowroot.config.ConfigService;
import org.glowroot.config.UserRecordingConfig;
import org.glowroot.transaction.model.Transaction;

import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.concurrent.TimeUnit.NANOSECONDS;

class UserProfileScheduler {

    private final ScheduledExecutorService scheduledExecutor;
    private final ConfigService configService;

    UserProfileScheduler(ScheduledExecutorService scheduledExecutor, ConfigService configService) {
        this.scheduledExecutor = scheduledExecutor;
        this.configService = configService;
    }

    void maybeScheduleUserProfiling(Transaction transaction, String user) {
        UserRecordingConfig userRecordingConfig = configService.getUserRecordingConfig();
        if (!userRecordingConfig.isEnabled()) {
            return;
        }
        if (!user.equalsIgnoreCase(userRecordingConfig.getUser())) {
            return;
        }
        // schedule the first stack collection for configured interval after trace start (or
        // immediately, if trace duration already exceeds configured collection interval)
        int intervalMillis = userRecordingConfig.getProfileIntervalMillis();
        ScheduledRunnable profileRunnable =
                new TransactionProfileRunnable(transaction, false, configService);
        long initialDelay =
                Math.max(0, intervalMillis - NANOSECONDS.toMillis(transaction.getDuration()));
        profileRunnable.scheduleWithFixedDelay(scheduledExecutor, initialDelay, intervalMillis,
                MILLISECONDS);
        transaction.setUserProfileRunnable(profileRunnable);
    }
}
