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
package org.glowroot.common.model;

import java.util.List;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

public class ServiceCallCollectorTest {

    @Test
    public void tracksLastCaptureTime() {
        ServiceCallCollector collector = new ServiceCallCollector(10);
        collector.updateLastCaptureTime(10);
        collector.updateLastCaptureTime(30);
        assertThat(collector.getLastCaptureTime()).isEqualTo(30);
    }

    @Test
    public void mergesSameServiceCall() {
        ServiceCallCollector collector = new ServiceCallCollector(10);
        collector.mergeServiceCall("HTTP", "GET /a", 100, 1);
        collector.mergeServiceCall("HTTP", "GET /a", 50, 2);

        List<MutableServiceCall> calls = collector.getSortedAndTruncatedServiceCalls();
        assertThat(calls).hasSize(1);
        assertThat(calls.get(0).getText()).isEqualTo("GET /a");
        assertThat(calls.get(0).getTotalDurationNanos()).isEqualTo(150);
        assertThat(calls.get(0).getExecutionCount()).isEqualTo(3);
    }

    @Test
    public void sortsByTotalDurationDescending() {
        ServiceCallCollector collector = new ServiceCallCollector(10);
        collector.mergeServiceCall("HTTP", "slow", 300, 1);
        collector.mergeServiceCall("HTTP", "fast", 10, 1);
        collector.mergeServiceCall("HTTP", "mid", 100, 1);

        assertThat(collector.getSortedAndTruncatedServiceCalls())
                .extracting(MutableServiceCall::getText)
                .containsExactly("slow", "mid", "fast");
    }

    @Test
    public void truncatesIntoLimitExceededBucket() {
        ServiceCallCollector collector = new ServiceCallCollector(1);
        collector.mergeServiceCall("HTTP", "a", 300, 1);
        collector.mergeServiceCall("HTTP", "b", 200, 1);
        collector.mergeServiceCall("HTTP", "c", 100, 1);

        List<MutableServiceCall> calls = collector.getSortedAndTruncatedServiceCalls();
        assertThat(calls).hasSize(2);
        assertThat(calls.get(0).getText()).isEqualTo("a");
        assertThat(calls.get(1).getText()).isEqualTo("LIMIT EXCEEDED BUCKET");
        assertThat(calls.get(1).getTotalDurationNanos()).isEqualTo(300);
        assertThat(calls.get(1).getExecutionCount()).isEqualTo(2);
    }

    @Test
    public void mergesIncomingLimitExceededBucket() {
        ServiceCallCollector collector = new ServiceCallCollector(10);
        collector.mergeServiceCall("HTTP", "LIMIT EXCEEDED BUCKET", 40, 4);
        collector.mergeServiceCall("HTTP", "LIMIT EXCEEDED BUCKET", 10, 1);

        List<MutableServiceCall> calls = collector.getSortedAndTruncatedServiceCalls();
        assertThat(calls).hasSize(1);
        assertThat(calls.get(0).getText()).isEqualTo("LIMIT EXCEEDED BUCKET");
        assertThat(calls.get(0).getTotalDurationNanos()).isEqualTo(50);
        assertThat(calls.get(0).getExecutionCount()).isEqualTo(5);
    }
}
