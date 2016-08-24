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
package org.glowroot.agent.live;

import org.junit.Test;

import static org.assertj.core.api.Assertions.assertThat;

public class LiveJvmServiceImplTest {

    @Test
    public void testNonParseable() {
        assertThat(LiveJvmServiceImpl.parseProcessId("")).isNull();
        assertThat(LiveJvmServiceImpl.parseProcessId("x")).isNull();
        assertThat(LiveJvmServiceImpl.parseProcessId("x:y")).isNull();
        assertThat(LiveJvmServiceImpl.parseProcessId("@y")).isNull();
        assertThat(LiveJvmServiceImpl.parseProcessId("x@y")).isNull();
    }

    @Test
    public void testParseable() {
        assertThat(LiveJvmServiceImpl.parseProcessId("123456@host")).isEqualTo(123456);
    }
}
