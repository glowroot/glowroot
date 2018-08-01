/*
 * Copyright 2017-2018 the original author or authors.
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
package org.glowroot.agent.central;

import org.junit.Test;

import static org.assertj.core.api.Assertions.assertThat;

public class CentralCollectorTest {

    @Test
    public void shouldEscape() {
        assertThat(CentralCollector.escapeHostName("")).isEqualTo("");
        assertThat(CentralCollector.escapeHostName("abc")).isEqualTo("abc");
        assertThat(CentralCollector.escapeHostName("a:b:c")).isEqualTo("a:b:c");
        assertThat(CentralCollector.escapeHostName(":a:b:c:")).isEqualTo("\\:a:b:c:");
        assertThat(CentralCollector.escapeHostName("::a::b::c::"))
                .isEqualTo("\\:\\:a:\\:b:\\:c:\\:");
        assertThat(CentralCollector.escapeHostName(":::a:::b:::c:::"))
                .isEqualTo("\\:\\:\\:a:\\:\\:b:\\:\\:c:\\:\\:");
        assertThat(CentralCollector.escapeHostName("::::")).isEqualTo("\\:\\:\\:\\:");

        assertThat(CentralCollector.escapeHostName("a\\b\\c")).isEqualTo("a\\\\b\\\\c");
    }

    @Test
    public void shouldCheckAgentVersionAgainstCentralVersion() {
        assertThat(CentralCollector.isAgentVersionGreaterThanCentralVersion("1.10.2", "1.10.1"))
                .isTrue();
        assertThat(CentralCollector.isAgentVersionGreaterThanCentralVersion("1.10.2", "1.10.2"))
                .isFalse();
        assertThat(CentralCollector.isAgentVersionGreaterThanCentralVersion("1.10.2", "1.10.3"))
                .isFalse();

        assertThat(CentralCollector.isAgentVersionGreaterThanCentralVersion("1.10.2", "1.9.2"))
                .isTrue();
        assertThat(CentralCollector.isAgentVersionGreaterThanCentralVersion("1.10.2", "1.10.2"))
                .isFalse();
        assertThat(CentralCollector.isAgentVersionGreaterThanCentralVersion("1.10.2", "1.11.2"))
                .isFalse();

        assertThat(CentralCollector.isAgentVersionGreaterThanCentralVersion("1.10.2", "0.10.2"))
                .isTrue();
        assertThat(CentralCollector.isAgentVersionGreaterThanCentralVersion("1.10.2", "1.10.2"))
                .isFalse();
        assertThat(CentralCollector.isAgentVersionGreaterThanCentralVersion("1.10.2", "2.10.2"))
                .isFalse();
    }
}
