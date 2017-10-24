/*
 * Copyright 2017 the original author or authors.
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
package org.glowroot.ui;

import java.util.List;

import com.google.common.collect.ImmutableList;
import org.junit.Test;

import org.glowroot.common.model.ImmutableErrorInterval;
import org.glowroot.common.model.SyntheticResult.ErrorInterval;
import org.glowroot.ui.MultiErrorIntervalCollector.MultiErrorInterval;

import static org.assertj.core.api.Assertions.assertThat;

public class MultiErrorIntervalCollectorTest {

    @Test
    public void shouldMergeOne() {
        // given
        ErrorInterval one = one();
        List<ErrorInterval> errorIntervals = ImmutableList.of(one);
        MultiErrorIntervalCollector collector = new MultiErrorIntervalCollector();

        // when
        collector.addErrorIntervals(errorIntervals);
        List<MultiErrorInterval> mergedMultiErrorIntervals =
                collector.getMergedMultiErrorIntervals();

        // then
        assertThat(mergedMultiErrorIntervals).hasSize(1);
        MultiErrorInterval mergedMultiErrorInterval = mergedMultiErrorIntervals.get(0);
        assertThat(mergedMultiErrorInterval.from()).isEqualTo(10);
        assertThat(mergedMultiErrorInterval.to()).isEqualTo(20);
        assertThat(mergedMultiErrorInterval.errorIntervals()).isEqualTo(errorIntervals);
    }

    @Test
    public void shouldMergeTwo() {
        // given
        ErrorInterval one = one();
        ErrorInterval two = two(false);
        List<ErrorInterval> errorIntervals = ImmutableList.of(one, two);
        MultiErrorIntervalCollector collector = new MultiErrorIntervalCollector();

        // when
        collector.addErrorIntervals(errorIntervals);
        List<MultiErrorInterval> mergedMultiErrorIntervals =
                collector.getMergedMultiErrorIntervals();

        // then
        assertThat(mergedMultiErrorIntervals).hasSize(1);
        MultiErrorInterval mergedMultiErrorInterval = mergedMultiErrorIntervals.get(0);
        assertThat(mergedMultiErrorInterval.from()).isEqualTo(10);
        assertThat(mergedMultiErrorInterval.to()).isEqualTo(40);
        assertThat(mergedMultiErrorInterval.errorIntervals()).isEqualTo(errorIntervals);
    }

    @Test
    public void shouldNotMergeTwo() {
        // given
        ErrorInterval one = one();
        ErrorInterval two = two(true);
        List<ErrorInterval> errorIntervals = ImmutableList.of(one, two);
        MultiErrorIntervalCollector collector = new MultiErrorIntervalCollector();

        // when
        collector.addErrorIntervals(errorIntervals);
        List<MultiErrorInterval> mergedMultiErrorIntervals =
                collector.getMergedMultiErrorIntervals();

        // then
        assertThat(mergedMultiErrorIntervals).hasSize(2);
        MultiErrorInterval mergedMultiErrorInterval = mergedMultiErrorIntervals.get(0);
        assertThat(mergedMultiErrorInterval.from()).isEqualTo(10);
        assertThat(mergedMultiErrorInterval.to()).isEqualTo(20);
        assertThat(mergedMultiErrorInterval.errorIntervals()).containsExactly(one);
        mergedMultiErrorInterval = mergedMultiErrorIntervals.get(1);
        assertThat(mergedMultiErrorInterval.from()).isEqualTo(30);
        assertThat(mergedMultiErrorInterval.to()).isEqualTo(40);
        assertThat(mergedMultiErrorInterval.errorIntervals()).containsExactly(two);
    }

    private ErrorInterval one() {
        return ImmutableErrorInterval.builder()
                .from(10)
                .to(20)
                .count(5)
                .message("a")
                .doNotMergeToTheLeft(false)
                .doNotMergeToTheRight(false)
                .build();
    }

    private ErrorInterval two(boolean doNotMergeToTheLeft) {
        return ImmutableErrorInterval.builder()
                .from(30)
                .to(40)
                .count(6)
                .message("b")
                .doNotMergeToTheLeft(doNotMergeToTheLeft)
                .doNotMergeToTheRight(false)
                .build();
    }
}
