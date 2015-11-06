/*
 * Copyright 2015 the original author or authors.
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

import com.google.common.collect.Ordering;
import org.junit.Test;

import org.glowroot.common.config.ImmutableInstrumentationConfig;
import org.glowroot.common.config.InstrumentationConfig;
import org.glowroot.common.config.InstrumentationConfig.CaptureKind;
import org.glowroot.ui.InstrumentationConfigJsonService.InstrumentationConfigOrdering;

import static org.assertj.core.api.Assertions.assertThat;

public class InstrumentationConfigOrderingTest {

    private final InstrumentationConfig left = ImmutableInstrumentationConfig.builder()
            .className("a")
            .methodName("n")
            .addMethodParameterTypes("java.lang.String")
            .methodReturnType("")
            .captureKind(CaptureKind.TIMER)
            .timerName("t")
            .traceEntryMessageTemplate("")
            .traceEntryCaptureSelfNested(false)
            .transactionType("")
            .transactionNameTemplate("")
            .transactionUserTemplate("")
            .enabledProperty("")
            .traceEntryEnabledProperty("")
            .build();

    private final InstrumentationConfig right = ImmutableInstrumentationConfig.builder()
            .className("b")
            .methodName("m")
            .methodReturnType("")
            .captureKind(CaptureKind.TIMER)
            .timerName("t")
            .traceEntryMessageTemplate("")
            .traceEntryCaptureSelfNested(false)
            .transactionType("")
            .transactionNameTemplate("")
            .transactionUserTemplate("")
            .enabledProperty("")
            .traceEntryEnabledProperty("")
            .build();

    @Test
    public void testDifferentClassNames() {
        // given
        Ordering<InstrumentationConfig> ordering = new InstrumentationConfigOrdering();
        // when
        int compare = ordering.compare(left, right);
        // then
        assertThat(compare).isNegative();
    }

    @Test
    public void testSameClassNames() {
        // given
        Ordering<InstrumentationConfig> ordering = new InstrumentationConfigOrdering();
        // when
        int compare = ordering.compare(left,
                ImmutableInstrumentationConfig.builder().copyFrom(right).className("a").build());
        // then
        assertThat(compare).isPositive();
    }

    @Test
    public void testSameClassAndMethodNames() {
        // given
        Ordering<InstrumentationConfig> ordering = new InstrumentationConfigOrdering();
        // when
        int compare = ordering.compare(
                ImmutableInstrumentationConfig.builder().copyFrom(left).methodName("m").build(),
                ImmutableInstrumentationConfig.builder().copyFrom(right).className("a").build());
        // then
        assertThat(compare).isPositive();
    }

    @Test
    public void testSameClassAndMethodNamesAndParamCount() {
        // given
        Ordering<InstrumentationConfig> ordering = new InstrumentationConfigOrdering();
        // when
        int compare = ordering.compare(
                ImmutableInstrumentationConfig.builder().copyFrom(left).methodName("m").build(),
                ImmutableInstrumentationConfig.builder().copyFrom(right).className("a")
                        .addMethodParameterTypes("java.lang.Throwable").build());
        // then
        assertThat(compare).isNegative();
    }

    @Test
    public void testSameEverything() {
        // given
        Ordering<InstrumentationConfig> ordering = new InstrumentationConfigOrdering();
        // when
        int compare = ordering.compare(
                ImmutableInstrumentationConfig.builder().copyFrom(left).methodName("m").build(),
                ImmutableInstrumentationConfig.builder().copyFrom(right).className("a")
                        .addMethodParameterTypes("java.lang.String").build());
        // then
        assertThat(compare).isZero();
    }
}
