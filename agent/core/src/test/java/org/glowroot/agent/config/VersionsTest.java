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
package org.glowroot.agent.config;

import org.junit.jupiter.api.Test;

import org.glowroot.common.util.Versions;

import static org.assertj.core.api.Assertions.assertThat;

public class VersionsTest {

    @Test
    public void shouldFallbackToZerosOnError() {
        // when
        String version = Versions.getJsonVersion(new A());
        // then
        assertThat(version).isEqualTo("0000000000000000000000000000000000000000");
    }

    public static class A {

        public String getName() {
            throw new RuntimeException("Test");
        }
    }
}
