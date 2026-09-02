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
package org.glowroot.agent.impl;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

public class TransactionProcessorPendingLimitsTest {

    private static final String TXN_PROP = "glowroot.internal.transactionPendingLimit";
    private static final String AGG_PROP = "glowroot.internal.aggregatePendingLimit";

    @AfterEach
    public void clearProps() {
        System.clearProperty(TXN_PROP);
        System.clearProperty(AGG_PROP);
    }

    @Test
    public void shouldDefaultPendingLimits() {
        assertThat(TransactionProcessor.transactionPendingLimit()).isEqualTo(1000);
        assertThat(TransactionProcessor.aggregatePendingLimit()).isEqualTo(5);
    }

    @Test
    public void shouldReadPendingLimitsFromSystemProperties() {
        System.setProperty(TXN_PROP, "42");
        System.setProperty(AGG_PROP, "9");
        assertThat(TransactionProcessor.transactionPendingLimit()).isEqualTo(42);
        assertThat(TransactionProcessor.aggregatePendingLimit()).isEqualTo(9);
    }
}
