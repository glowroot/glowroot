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
package org.glowroot.agent.impl;

import org.junit.Test;

import static org.assertj.core.api.Assertions.assertThat;

public class TransactionTest {

    @Test
    public void traceIdShouldBeThirtyTwoCharactersAndLowerCase() {
        // when
        String traceId = Transaction.buildTraceId(System.currentTimeMillis());
        // then
        assertThat(traceId).hasSize(32);
        assertThat(traceId.toLowerCase()).isEqualTo(traceId);
    }

    @Test
    public void shouldLowerSixBytesWithPadding() {
        // given
        long startTime = 123456;
        // when
        String lowerSixBytes = Transaction.lowerSixBytesHex(startTime);
        // then
        assertThat(lowerSixBytes).isEqualTo("00000001e240");
    }

    @Test
    public void shouldLowerSixBytesWithNoPaddingOrTruncation() {
        // given
        long startTime = 123456123456789L;
        // when
        String lowerSixBytes = Transaction.lowerSixBytesHex(startTime);
        // then
        assertThat(lowerSixBytes).isEqualTo("70485e624d15");
    }

    @Test
    public void shouldLowerSixBytesWithTruncation() {
        // given
        long startTime = 123456123456789123L;
        // when
        String lowerSixBytes = Transaction.lowerSixBytesHex(startTime);
        // then
        assertThat(lowerSixBytes).isEqualTo("9ab0affd1a83");
    }
}
