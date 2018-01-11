/*
 * Copyright 2017 the original author or authors.
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
package org.glowroot.central.repo;

import org.junit.Test;

import static org.assertj.core.api.Assertions.assertThat;

public class AgentRollupIdsTest {

    @Test
    public void shouldGetAgentRollupIds() {
        assertThat(AgentRollupIds.getAgentRollupIds("aaa")).containsExactly("aaa");
        assertThat(AgentRollupIds.getAgentRollupIds("aaa::bbb")).containsExactly("aaa::bbb",
                "aaa::");
        assertThat(AgentRollupIds.getAgentRollupIds("aaa::bbb::ccc"))
                .containsExactly("aaa::bbb::ccc", "aaa::bbb::", "aaa::");
        assertThat(AgentRollupIds.getAgentRollupIds("a")).containsExactly("a");
        assertThat(AgentRollupIds.getAgentRollupIds("a::b")).containsExactly("a::b", "a::");
        assertThat(AgentRollupIds.getAgentRollupIds("a::b::c")).containsExactly("a::b::c", "a::b::",
                "a::");
        assertThat(AgentRollupIds.getAgentRollupIds("a:b:c")).containsExactly("a:b:c");
    }

    @Test
    public void shouldGetAgentRollupIdsFromRollup() {
        assertThat(AgentRollupIds.getAgentRollupIds("aaa::")).containsExactly("aaa::");
        assertThat(AgentRollupIds.getAgentRollupIds("aaa::bbb::")).containsExactly("aaa::bbb::",
                "aaa::");
        assertThat(AgentRollupIds.getAgentRollupIds("aaa::bbb::ccc::"))
                .containsExactly("aaa::bbb::ccc::", "aaa::bbb::", "aaa::");
        assertThat(AgentRollupIds.getAgentRollupIds("a::")).containsExactly("a::");
        assertThat(AgentRollupIds.getAgentRollupIds("a::b::")).containsExactly("a::b::", "a::");
        assertThat(AgentRollupIds.getAgentRollupIds("a::b::c::")).containsExactly("a::b::c::",
                "a::b::", "a::");
        assertThat(AgentRollupIds.getAgentRollupIds("a:b:c::")).containsExactly("a:b:c::");
    }

    @Test
    public void shouldGetAgentRollupParent() {
        assertThat(AgentRollupIds.getParent("aaa")).isNull();
        assertThat(AgentRollupIds.getParent("aaa::")).isNull();
        assertThat(AgentRollupIds.getParent("aaa::bbb")).isEqualTo("aaa::");
        assertThat(AgentRollupIds.getParent("aaa::bbb::")).isEqualTo("aaa::");
        assertThat(AgentRollupIds.getParent("aaa::bbb::ccc")).isEqualTo("aaa::bbb::");
        assertThat(AgentRollupIds.getParent("aaa::bbb::ccc::")).isEqualTo("aaa::bbb::");
    }
}
