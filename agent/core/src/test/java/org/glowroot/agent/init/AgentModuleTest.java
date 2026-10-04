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
package org.glowroot.agent.init;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

public class AgentModuleTest {

    @Test
    public void shouldCheckSystemPkgsIncludesGlowroot() {
        assertThat(AgentModule.systemPkgsIncludesGlowroot(null)).isFalse();
        assertThat(AgentModule.systemPkgsIncludesGlowroot("")).isFalse();
        assertThat(AgentModule.systemPkgsIncludesGlowroot("   ")).isFalse();
        assertThat(AgentModule.systemPkgsIncludesGlowroot("org.jboss.byteman")).isFalse();
        assertThat(AgentModule.systemPkgsIncludesGlowroot("org.glowroot-other")).isFalse();

        assertThat(AgentModule.systemPkgsIncludesGlowroot("org.glowroot")).isTrue();
        assertThat(AgentModule.systemPkgsIncludesGlowroot("org.glowroot.agent")).isTrue();
        assertThat(AgentModule.systemPkgsIncludesGlowroot("org.jboss.byteman,org.glowroot")).isTrue();
        assertThat(AgentModule.systemPkgsIncludesGlowroot("org.jboss.byteman, org.glowroot ")).isTrue();
        assertThat(AgentModule.systemPkgsIncludesGlowroot("org.jboss.byteman,org.glowroot.agent")).isTrue();
    }
}
