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

import org.junit.Test;

import org.glowroot.wire.api.model.AgentConfigOuterClass.AgentConfig.AlertConfig;
import org.glowroot.wire.api.model.AgentConfigOuterClass.AgentConfig.AlertConfig.AlertKind;
import org.glowroot.wire.api.model.Proto.OptionalDouble;
import org.glowroot.wire.api.model.Proto.OptionalInt32;

import static org.assertj.core.api.Assertions.assertThat;

public class AlertConfigOrderingTest {

    private final AlertConfig left = AlertConfig.newBuilder()
            .setKind(AlertKind.TRANSACTION)
            .setTransactionType("tt")
            .setTransactionPercentile(OptionalDouble.newBuilder()
                    .setValue(50.0))
            .setTransactionThresholdMillis(OptionalInt32.newBuilder()
                    .setValue(500))
            .setTimePeriodSeconds(60)
            .setMinTransactionCount(OptionalInt32.newBuilder()
                    .setValue(5))
            .setGaugeName("")
            .build();

    private final AlertConfig right = AlertConfig.newBuilder()
            .setKind(AlertKind.TRANSACTION)
            .setTransactionType("uu")
            .setTransactionPercentile(OptionalDouble.newBuilder()
                    .setValue(50.0))
            .setTransactionThresholdMillis(OptionalInt32.newBuilder()
                    .setValue(500))
            .setTimePeriodSeconds(60)
            .setMinTransactionCount(OptionalInt32.newBuilder()
                    .setValue(5))
            .setGaugeName("")
            .build();

    @Test
    public void shouldCompare() {
        // when
        int compare = AlertConfigJsonService.orderingByName.compare(left, right);
        // then
        assertThat(compare).isNegative();
    }
}
