/*
 * Copyright 2026 the original author or authors.
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
package org.glowroot.agent.embedded.repo;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import com.google.common.collect.ImmutableList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.glowroot.common2.repo.ImmutableReaperStatus;
import org.glowroot.common2.repo.RepoAdmin.ReaperStatus;

/**
 * Tracks embedded retention-reaper failures across scheduled cycles. Shared by
 * {@link ReaperRunnable} (writer) and {@link RepoAdminImpl} / JMX (readers).
 */
class ReaperFailureTracker {

    private static final Logger logger = LoggerFactory.getLogger(ReaperFailureTracker.class);

    // Reaper runs every 5 minutes; log a reminder about once per hour while failing.
    static final int WARN_EVERY_N_FAILED_CYCLES = 12;

    private final AtomicInteger consecutiveFailedCycles = new AtomicInteger();
    private volatile ImmutableList<String> lastFailedSteps = ImmutableList.of();
    private volatile String lastErrorMessage = "";
    private volatile long lastFailureTimeMillis;

    void recordCycleSuccess() {
        int previous = consecutiveFailedCycles.getAndSet(0);
        if (previous > 0) {
            logger.info("embedded retention reaper recovered after {} consecutive failed cycle(s)",
                    previous);
        }
        lastFailedSteps = ImmutableList.of();
        lastErrorMessage = "";
    }

    void recordCycleFailure(List<String> failedSteps, Throwable firstThrowable,
            long failureTimeMillis) {
        int streak = consecutiveFailedCycles.incrementAndGet();
        lastFailedSteps = ImmutableList.copyOf(failedSteps);
        lastErrorMessage = firstThrowable.toString();
        lastFailureTimeMillis = failureTimeMillis;
        if (streak == 1) {
            logger.error("embedded retention reaper failed for step(s) {}: {}"
                    + " (other steps continue; see Admin > Storage or JMX H2Database"
                    + " reaperConsecutiveFailedCycles)",
                    failedSteps, firstThrowable.toString(), firstThrowable);
        } else if (streak % WARN_EVERY_N_FAILED_CYCLES == 0) {
            logger.warn("embedded retention reaper still failing after {} consecutive cycle(s);"
                    + " last step(s) {}: {}"
                    + " — corrupt H2 often needs wipe of data/ or move to Central;"
                    + " see docs/embedded-h2-upgrade.md",
                    streak, failedSteps, firstThrowable.toString());
        }
    }

    int getConsecutiveFailedCycles() {
        return consecutiveFailedCycles.get();
    }

    ReaperStatus snapshot() {
        return ImmutableReaperStatus.builder()
                .consecutiveFailedCycles(consecutiveFailedCycles.get())
                .addAllLastFailedSteps(lastFailedSteps)
                .lastErrorMessage(lastErrorMessage)
                .lastFailureTimeMillis(lastFailureTimeMillis)
                .build();
    }
}
