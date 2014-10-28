/*
 * Copyright 2011-2014 the original author or authors.
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

import com.google.common.base.MoreObjects;

import org.glowroot.common.ScheduledRunnable;
import org.glowroot.markers.ThreadSafe;
import org.glowroot.transaction.model.Profile;
import org.glowroot.transaction.model.Transaction;

/**
 * Captures a stack trace for the thread executing a transaction and stores the stack trace in the
 * {@link Transaction}'s {@link Profile}.
 * 
 * @author Trask Stalnaker
 * @since 0.5
 */
@ThreadSafe
class ProfileRunnable extends ScheduledRunnable {

    private final Transaction transaction;
    private final boolean outlier;

    ProfileRunnable(Transaction transaction, boolean outlier) {
        this.transaction = transaction;
        this.outlier = outlier;
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
        transaction.captureStackTrace(outlier);
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
                .add("transaction", transaction)
                .add("outlier", outlier)
                .toString();
    }
}
