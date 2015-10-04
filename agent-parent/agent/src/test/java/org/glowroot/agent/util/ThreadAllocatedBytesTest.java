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
package org.glowroot.agent.util;

import org.junit.Test;

import static org.assertj.core.api.Assertions.assertThat;

public class ThreadAllocatedBytesTest {

    @Test
    public void testIsSupportedReturnsNull() throws Exception {
        // given
        Class<?> sunThreadMXBeanClass = Class.forName("com.sun.management.ThreadMXBean");
        // when
        OptionalService<ThreadAllocatedBytes> optionalService =
                ThreadAllocatedBytes.createInternal(null, sunThreadMXBeanClass);
        // then
        assertThat(optionalService.getAvailability().isAvailable()).isFalse();
        assertThat(optionalService.getAvailability().getReason()).isEqualTo(
                "ThreadMXBean.isThreadAllocatedMemorySupported() unexpectedly returned null");
    }

    @Test
    public void testIsSupportedReturnsFalse() throws Exception {
        // given
        Class<?> sunThreadMXBeanClass = Class.forName("com.sun.management.ThreadMXBean");
        // when
        OptionalService<ThreadAllocatedBytes> optionalService =
                ThreadAllocatedBytes.createInternal(false, sunThreadMXBeanClass);
        // then
        assertThat(optionalService.getAvailability().isAvailable()).isFalse();
        assertThat(optionalService.getAvailability().getReason()).isEqualTo(
                "Method com.sun.management.ThreadMXBean.isThreadAllocatedMemorySupported()"
                        + " returned false");
    }
}
