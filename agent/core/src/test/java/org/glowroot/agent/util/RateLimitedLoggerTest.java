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
package org.glowroot.agent.util;

import org.junit.Test;

import static org.assertj.core.api.Assertions.assertThat;

public class RateLimitedLoggerTest {

    @Test
    public void testEmpty() {
        // given
        RateLimitedLogger rateLimitedLogger = new RateLimitedLogger(RateLimitedLoggerTest.class);
        // when
        Object[] args = rateLimitedLogger.newArgsWithCountSinceLastWarning(new Object[] {});
        // then
        assertThat(args).containsExactly(0);
    }

    @Test
    public void testSingle() {
        // given
        RateLimitedLogger rateLimitedLogger = new RateLimitedLogger(RateLimitedLoggerTest.class);
        // when
        Object[] args = rateLimitedLogger.newArgsWithCountSinceLastWarning(new Object[] {1});
        // then
        assertThat(args).containsExactly(1, 0);
    }

    @Test
    public void testSingleException() {
        // given
        RateLimitedLogger rateLimitedLogger = new RateLimitedLogger(RateLimitedLoggerTest.class);
        Exception exception = new Exception();
        // when
        Object[] args =
                rateLimitedLogger.newArgsWithCountSinceLastWarning(new Object[] {exception});
        // then
        assertThat(args).containsExactly(0, exception);
    }

    @Test
    public void testMultiple() {
        // given
        RateLimitedLogger rateLimitedLogger = new RateLimitedLogger(RateLimitedLoggerTest.class);
        // when
        Object[] args = rateLimitedLogger.newArgsWithCountSinceLastWarning(new Object[] {1, 2});
        // then
        assertThat(args).containsExactly(1, 2, 0);
    }

    @Test
    public void testMultipleException() {
        // given
        RateLimitedLogger rateLimitedLogger = new RateLimitedLogger(RateLimitedLoggerTest.class);
        Exception exception = new Exception();
        // when
        Object[] args =
                rateLimitedLogger.newArgsWithCountSinceLastWarning(new Object[] {1, exception});
        // then
        assertThat(args).containsExactly(1, 0, exception);
    }
}
