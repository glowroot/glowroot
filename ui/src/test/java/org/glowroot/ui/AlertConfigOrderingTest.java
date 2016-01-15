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

import org.glowroot.storage.config.AlertConfig;
import org.glowroot.storage.config.ImmutableAlertConfig;

import static org.assertj.core.api.Assertions.assertThat;

public class AlertConfigOrderingTest {

    private final AlertConfig left = ImmutableAlertConfig.builder()
            .transactionType("tt")
            .percentile(50)
            .timePeriodMinutes(1)
            .thresholdMillis(500)
            .minTransactionCount(5)
            .build();

    private final AlertConfig right = ImmutableAlertConfig.builder()
            .transactionType("uu")
            .percentile(50)
            .timePeriodMinutes(1)
            .thresholdMillis(500)
            .minTransactionCount(5)
            .build();

    @Test
    public void shouldCompare() {
        // given
        // when
        int compare = AlertConfigJsonService.orderingByName.compare(left, right);
        // then
        assertThat(compare).isNegative();
    }
}
