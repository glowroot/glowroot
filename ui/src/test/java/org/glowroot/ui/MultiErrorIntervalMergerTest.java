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
import org.glowroot.ui.MultiErrorIntervalMerger.GroupedMultiErrorInterval;

import static org.assertj.core.api.Assertions.assertThat;

public class MultiErrorIntervalMergerTest {

    @Test
    public void shouldMergeOne() {
        // given
        MultiErrorIntervalMerger merger = new MultiErrorIntervalMerger();

        // when
        merger.addMultiErrorIntervals("syn1", ImmutableList.of(one(), two()));
        List<GroupedMultiErrorInterval> groupedMultiErrorIntervals =
                merger.getGroupedMultiErrorIntervals();

        // then
        assertThat(groupedMultiErrorIntervals).hasSize(2);
        GroupedMultiErrorInterval groupedMultiErrorInterval = groupedMultiErrorIntervals.get(0);
        assertThat(groupedMultiErrorInterval.from()).isEqualTo(10);
        assertThat(groupedMultiErrorInterval.to()).isEqualTo(40);
        assertThat(groupedMultiErrorInterval.errorIntervals()).hasSize(1);
        assertThat(groupedMultiErrorInterval.errorIntervals().get("syn1")).containsExactly(one1(),
                one2());
        groupedMultiErrorInterval = groupedMultiErrorIntervals.get(1);
        assertThat(groupedMultiErrorInterval.from()).isEqualTo(50);
        assertThat(groupedMultiErrorInterval.to()).isEqualTo(60);
        assertThat(groupedMultiErrorInterval.errorIntervals()).hasSize(1);
        assertThat(groupedMultiErrorInterval.errorIntervals().get("syn1")).containsExactly(two1());
    }

    @Test
    public void shouldMergeTwoWithOverlap() {
        // given
        MultiErrorIntervalMerger merger = new MultiErrorIntervalMerger();

        // when
        merger.addMultiErrorIntervals("syn1", ImmutableList.of(one(), two()));
        merger.addMultiErrorIntervals("syn2", ImmutableList.of(threeWithOverlap()));
        List<GroupedMultiErrorInterval> groupedMultiErrorIntervals =
                merger.getGroupedMultiErrorIntervals();

        // then
        assertThat(groupedMultiErrorIntervals).hasSize(1);
        GroupedMultiErrorInterval groupedMultiErrorInterval = groupedMultiErrorIntervals.get(0);
        assertThat(groupedMultiErrorInterval.from()).isEqualTo(10);
        assertThat(groupedMultiErrorInterval.to()).isEqualTo(60);
        assertThat(groupedMultiErrorInterval.errorIntervals()).hasSize(2);
        assertThat(groupedMultiErrorInterval.errorIntervals().get("syn1")).containsExactly(one1(),
                one2(), two1());
        assertThat(groupedMultiErrorInterval.errorIntervals().get("syn2"))
                .containsExactly(threeWithOverlap1());
    }

    @Test
    public void shouldMergeTwoWithNoOverlap() {
        // given
        MultiErrorIntervalMerger merger = new MultiErrorIntervalMerger();

        // when
        merger.addMultiErrorIntervals("syn1", ImmutableList.of(one(), two()));
        merger.addMultiErrorIntervals("syn2", ImmutableList.of(threeWithNoOverlap()));
        List<GroupedMultiErrorInterval> groupedMultiErrorIntervals =
                merger.getGroupedMultiErrorIntervals();

        // then
        assertThat(groupedMultiErrorIntervals).hasSize(3);
        GroupedMultiErrorInterval groupedMultiErrorInterval = groupedMultiErrorIntervals.get(0);
        assertThat(groupedMultiErrorInterval.from()).isEqualTo(10);
        assertThat(groupedMultiErrorInterval.to()).isEqualTo(40);
        assertThat(groupedMultiErrorInterval.errorIntervals()).hasSize(1);
        assertThat(groupedMultiErrorInterval.errorIntervals().get("syn1")).containsExactly(one1(),
                one2());
        groupedMultiErrorInterval = groupedMultiErrorIntervals.get(1);
        assertThat(groupedMultiErrorInterval.from()).isEqualTo(41);
        assertThat(groupedMultiErrorInterval.to()).isEqualTo(49);
        assertThat(groupedMultiErrorInterval.errorIntervals()).hasSize(1);
        assertThat(groupedMultiErrorInterval.errorIntervals().get("syn2"))
                .containsExactly(threeWithNoOverlap1());
        groupedMultiErrorInterval = groupedMultiErrorIntervals.get(2);
        assertThat(groupedMultiErrorInterval.from()).isEqualTo(50);
        assertThat(groupedMultiErrorInterval.to()).isEqualTo(60);
        assertThat(groupedMultiErrorInterval.errorIntervals()).hasSize(1);
        assertThat(groupedMultiErrorInterval.errorIntervals().get("syn1")).containsExactly(two1());
    }

    private MultiErrorInterval one() {
        return ImmutableMultiErrorInterval.builder()
                .from(10)
                .to(40)
                .addErrorIntervals(one1())
                .addErrorIntervals(one2())
                .build();
    }

    private ErrorInterval one1() {
        return ImmutableErrorInterval.builder()
                .from(10)
                .to(20)
                .count(6)
                .message("a")
                .doNotMergeToTheLeft(false)
                .doNotMergeToTheRight(false)
                .build();
    }

    private ErrorInterval one2() {
        return ImmutableErrorInterval.builder()
                .from(30)
                .to(40)
                .count(4)
                .message("b")
                .doNotMergeToTheLeft(false)
                .doNotMergeToTheRight(false)
                .build();
    }

    private MultiErrorInterval two() {
        return ImmutableMultiErrorInterval.builder()
                .from(50)
                .to(60)
                .addErrorIntervals(two1())
                .build();
    }

    private ErrorInterval two1() {
        return ImmutableErrorInterval.builder()
                .from(50)
                .to(60)
                .count(5)
                .message("c")
                .doNotMergeToTheLeft(false)
                .doNotMergeToTheRight(false)
                .build();
    }

    private MultiErrorInterval threeWithOverlap() {
        return ImmutableMultiErrorInterval.builder()
                .from(40)
                .to(50)
                .addErrorIntervals(threeWithOverlap1())
                .build();
    }

    private ErrorInterval threeWithOverlap1() {
        return ImmutableErrorInterval.builder()
                .from(40)
                .to(50)
                .count(3)
                .message("d")
                .doNotMergeToTheLeft(false)
                .doNotMergeToTheRight(false)
                .build();
    }

    private MultiErrorInterval threeWithNoOverlap() {
        return ImmutableMultiErrorInterval.builder()
                .from(41)
                .to(49)
                .addErrorIntervals(threeWithNoOverlap1())
                .build();
    }

    private ErrorInterval threeWithNoOverlap1() {
        return ImmutableErrorInterval.builder()
                .from(41)
                .to(49)
                .count(3)
                .message("d")
                .doNotMergeToTheLeft(false)
                .doNotMergeToTheRight(false)
                .build();
    }

}
