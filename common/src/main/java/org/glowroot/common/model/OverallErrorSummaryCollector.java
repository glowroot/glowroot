/*
 * Copyright 2016 the original author or authors.
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
package org.glowroot.common.model;

import org.immutables.value.Value;

public class OverallErrorSummaryCollector {

    private long errorCount;
    private long transactionCount;

    private long lastCaptureTime;

    public long getLastCaptureTime() {
        return lastCaptureTime;
    }

    public OverallErrorSummary getOverallErrorSummary() {
        return ImmutableOverallErrorSummary.builder()
                .errorCount(errorCount)
                .transactionCount(transactionCount)
                .build();
    }

    public void mergeErrorSummary(long errorCount, long transactionCount, long captureTime) {
        this.errorCount += errorCount;
        this.transactionCount += transactionCount;
        lastCaptureTime = Math.max(lastCaptureTime, captureTime);
    }

    @Value.Immutable
    public interface OverallErrorSummary {
        long errorCount();
        long transactionCount();
    }
}
