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
package org.glowroot;

import org.junit.Test;

import static org.assertj.core.api.Assertions.assertThat;

public class MainEntryPointTest {

    @Test
    public void testNonParseable() {
        assertThat(MainEntryPoint.isJBossModules(null)).isFalse();
        assertThat(MainEntryPoint.isJBossModules("")).isFalse();
        assertThat(MainEntryPoint.isJBossModules("org.example.Main")).isFalse();
        assertThat(MainEntryPoint.isJBossModules("org.jboss.modules.Main")).isTrue();
        assertThat(MainEntryPoint.isJBossModules("org.jboss.modules.Main 1 2")).isTrue();
        assertThat(MainEntryPoint.isJBossModules("path/to/jboss-modules.jar")).isTrue();
        assertThat(MainEntryPoint.isJBossModules("path/to/jboss-modules.jar 1 2")).isTrue();
    }
}
