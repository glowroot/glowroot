/*
 * Copyright 2016-2017 the original author or authors.
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
package org.glowroot.agent.util;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

public class RateLimitedLoggerTest {

    @Test
    public void testEmpty() {
        // given
        // when
        Object[] args = RateLimitedLogger.newArgsWithCountSinceLastWarning(new Object[] {}, 0);
        // then
        assertThat(args).containsExactly(0);
    }

    @Test
    public void testSingle() {
        // given
        // when
        Object[] args = RateLimitedLogger.newArgsWithCountSinceLastWarning(new Object[] {1}, 0);
        // then
        assertThat(args).containsExactly(1, 0);
    }

    @Test
    public void testSingleException() {
        // given
        Exception exception = new Exception();
        // when
        Object[] args =
                RateLimitedLogger.newArgsWithCountSinceLastWarning(new Object[] {exception}, 0);
        // then
        assertThat(args).containsExactly(0, exception);
    }

    @Test
    public void testMultiple() {
        // given
        // when
        Object[] args = RateLimitedLogger.newArgsWithCountSinceLastWarning(new Object[] {1, 2}, 0);
        // then
        assertThat(args).containsExactly(1, 2, 0);
    }

    @Test
    public void testMultipleException() {
        // given
        Exception exception = new Exception();
        // when
        Object[] args =
                RateLimitedLogger.newArgsWithCountSinceLastWarning(new Object[] {1, exception}, 0);
        // then
        assertThat(args).containsExactly(1, 0, exception);
    }
}
