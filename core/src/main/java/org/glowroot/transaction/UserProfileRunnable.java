/*
 * Copyright 2011-2015 the original author or authors.
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

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadInfo;
import java.lang.management.ThreadMXBean;

import com.google.common.base.MoreObjects;

import org.glowroot.common.ScheduledRunnable;
import org.glowroot.config.ConfigService;
import org.glowroot.transaction.model.Transaction;

class UserProfileRunnable extends ScheduledRunnable {

    private final Transaction transaction;
    private final ConfigService configService;

    UserProfileRunnable(Transaction transaction, ConfigService configService) {
        this.transaction = transaction;
        this.configService = configService;
    }

    @Override
    public void runInternal() {
        if (transaction.isCompleted()) {
            // there is a small window between trace completion and cancellation of this command,
            // plus, should a stop-the-world gc occur in this small window, even two command
            // executions can fire one right after the other in the small window (assuming the first
            // didn't throw an exception which it does now), since this command is scheduled using
            // ScheduledExecutorService.scheduleWithFixedDelay()
            throw new TerminateSubsequentExecutionsException();
        }
        ThreadMXBean threadBean = ManagementFactory.getThreadMXBean();
        ThreadInfo threadInfo =
                threadBean.getThreadInfo(transaction.getThreadId(), Integer.MAX_VALUE);
        transaction.captureStackTrace(threadInfo,
                configService.getAdvancedConfig().maxStackTraceSamplesPerTransaction());
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
                .add("transaction", transaction)
                .toString();
    }
}
