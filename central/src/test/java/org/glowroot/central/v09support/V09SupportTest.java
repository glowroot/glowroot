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
package org.glowroot.central.v09support;

import org.junit.Test;

import static org.assertj.core.api.Assertions.assertThat;

public class V09SupportTest {

    @Test
    public void shouldGetAgentRollupIdsV09() {
        assertThat(V09Support.getAgentRollupIdsV09("aaa")).containsExactly("aaa");
        assertThat(V09Support.getAgentRollupIdsV09("aaa::bbb")).containsExactly("bbb", "aaa");
        assertThat(V09Support.getAgentRollupIdsV09("aaa::bbb::ccc")).containsExactly("ccc",
                "aaa/bbb", "aaa");
        assertThat(V09Support.getAgentRollupIdsV09("a")).containsExactly("a");
        assertThat(V09Support.getAgentRollupIdsV09("a::b")).containsExactly("b", "a");
        assertThat(V09Support.getAgentRollupIdsV09("a::b::c")).containsExactly("c", "a/b", "a");
        assertThat(V09Support.getAgentRollupIdsV09("a:b:c")).containsExactly("a:b:c");
    }

    @Test
    public void shouldGetParentV09() {
        assertThat(V09Support.getParentV09("aaa")).isNull();
        assertThat(V09Support.getParentV09("aaa::")).isNull();
        assertThat(V09Support.getParentV09("aaa::bbb")).isEqualTo("aaa");
        assertThat(V09Support.getParentV09("aaa::bbb::")).isEqualTo("aaa");
        assertThat(V09Support.getParentV09("aaa::bbb::ccc")).isEqualTo("aaa/bbb");
        assertThat(V09Support.getParentV09("aaa::bbb::ccc::")).isEqualTo("aaa/bbb");
        assertThat(V09Support.getParentV09("a")).isNull();
        assertThat(V09Support.getParentV09("a::")).isNull();
        assertThat(V09Support.getParentV09("a::b")).isEqualTo("a");
        assertThat(V09Support.getParentV09("a::b::")).isEqualTo("a");
        assertThat(V09Support.getParentV09("a::b::c")).isEqualTo("a/b");
        assertThat(V09Support.getParentV09("a::b::c::")).isEqualTo("a/b");
    }

    @Test
    public void shouldConvertToV09() {
        assertThat(V09Support.convertToV09("aaa")).isEqualTo("aaa");
        assertThat(V09Support.convertToV09("aaa::")).isEqualTo("aaa");
        assertThat(V09Support.convertToV09("aaa::bbb")).isEqualTo("bbb");
        assertThat(V09Support.convertToV09("aaa::bbb::")).isEqualTo("aaa/bbb");
        assertThat(V09Support.convertToV09("aaa::bbb::ccc")).isEqualTo("ccc");
        assertThat(V09Support.convertToV09("aaa::bbb::ccc::")).isEqualTo("aaa/bbb/ccc");
        assertThat(V09Support.convertToV09("a")).isEqualTo("a");
        assertThat(V09Support.convertToV09("a::")).isEqualTo("a");
        assertThat(V09Support.convertToV09("a::b")).isEqualTo("b");
        assertThat(V09Support.convertToV09("a::b::")).isEqualTo("a/b");
        assertThat(V09Support.convertToV09("a::b::c")).isEqualTo("c");
        assertThat(V09Support.convertToV09("a::b::c::")).isEqualTo("a/b/c");
    }
}
