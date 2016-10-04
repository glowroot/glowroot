/*
 * Copyright 2015-2016 the original author or authors.
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

import org.glowroot.ui.InstrumentationConfigJsonService.InstrumentationConfigOrdering;
import org.glowroot.wire.api.model.AgentConfigOuterClass.AgentConfig.InstrumentationConfig;
import org.glowroot.wire.api.model.AgentConfigOuterClass.AgentConfig.InstrumentationConfig.CaptureKind;

import static org.assertj.core.api.Assertions.assertThat;

public class InstrumentationConfigOrderingTest {

    private final InstrumentationConfig left = InstrumentationConfig.newBuilder()
            .setClassName("a")
            .setMethodName("n")
            .addMethodParameterType("java.lang.String")
            .setMethodReturnType("")
            .setCaptureKind(CaptureKind.TIMER)
            .setTimerName("t")
            .setTraceEntryMessageTemplate("")
            .setTraceEntryCaptureSelfNested(false)
            .setTransactionType("")
            .setTransactionNameTemplate("")
            .setTransactionUserTemplate("")
            .setEnabledProperty("")
            .setTraceEntryEnabledProperty("")
            .build();

    private final InstrumentationConfig right = InstrumentationConfig.newBuilder()
            .setClassName("b")
            .setMethodName("m")
            .setMethodReturnType("")
            .setCaptureKind(CaptureKind.TIMER)
            .setTimerName("t")
            .setTraceEntryMessageTemplate("")
            .setTraceEntryCaptureSelfNested(false)
            .setTransactionType("")
            .setTransactionNameTemplate("")
            .setTransactionUserTemplate("")
            .setEnabledProperty("")
            .setTraceEntryEnabledProperty("")
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
        int compare = ordering.compare(left, right.toBuilder().setClassName("a").build());
        // then
        assertThat(compare).isPositive();
    }

    @Test
    public void testSameClassAndMethodNames() {
        // given
        Ordering<InstrumentationConfig> ordering = new InstrumentationConfigOrdering();
        // when
        int compare = ordering.compare(left.toBuilder().setMethodName("m").build(),
                right.toBuilder().setClassName("a").build());
        // then
        assertThat(compare).isPositive();
    }

    @Test
    public void testSameClassAndMethodNamesAndParamCount() {
        // given
        Ordering<InstrumentationConfig> ordering = new InstrumentationConfigOrdering();
        // when
        int compare =
                ordering.compare(left.toBuilder().setMethodName("m").build(), right.toBuilder()
                        .setClassName("a").addMethodParameterType("java.lang.Throwable").build());
        // then
        assertThat(compare).isNegative();
    }

    @Test
    public void testSameEverything() {
        // given
        Ordering<InstrumentationConfig> ordering = new InstrumentationConfigOrdering();
        // when
        int compare = ordering.compare(left.toBuilder().setMethodName("m").build(), right
                .toBuilder().setClassName("a").addMethodParameterType("java.lang.String").build());
        // then
        assertThat(compare).isZero();
    }
}
