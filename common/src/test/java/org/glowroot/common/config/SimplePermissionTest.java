/*
 * Copyright 2016-2018 the original author or authors.
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
package org.glowroot.common.config;

import org.junit.Test;

import org.glowroot.common.config.RoleConfig.SimplePermission;

import static org.assertj.core.api.Assertions.assertThat;

public class SimplePermissionTest {

    @Test
    public void test() throws Exception {
        assertThat(SimplePermission.create("a:b:c").implies(SimplePermission.create("a:b:c")))
                .isTrue();
        assertThat(SimplePermission.create("a:b").implies(SimplePermission.create("a:b:c")))
                .isTrue();
        assertThat(SimplePermission.create("a:b:c").implies(SimplePermission.create("a:b")))
                .isFalse();

        assertThat(SimplePermission.create("a:b:*").implies(SimplePermission.create("a:b:c")))
                .isTrue();
        assertThat(SimplePermission.create("a:b:c").implies(SimplePermission.create("a:b:*")))
                .isFalse();

        assertThat(SimplePermission.create("a:*").implies(SimplePermission.create("a:b:c")))
                .isTrue();
        assertThat(SimplePermission.create("a:b:c").implies(SimplePermission.create("a:*")))
                .isFalse();
    }

    @Test
    public void testAgentPermissions() throws Exception {
        assertThat(SimplePermission.create("agent:abc,xyz:c")
                .implies(SimplePermission.create("agent:abc:c"))).isTrue();
        assertThat(SimplePermission.create("agent:abc,xyz")
                .implies(SimplePermission.create("agent:abc:c"))).isTrue();
        assertThat(SimplePermission.create("agent:abc,xyz:c")
                .implies(SimplePermission.create("agent:abc"))).isFalse();

        assertThat(SimplePermission.create("agent:abc,xyz:*")
                .implies(SimplePermission.create("agent:abc:c"))).isTrue();
        assertThat(SimplePermission.create("agent:abc,xyz:c")
                .implies(SimplePermission.create("agent:abc:*"))).isFalse();

        assertThat(
                SimplePermission.create("agent:*").implies(SimplePermission.create("agent:abc:c")))
                        .isTrue();
        assertThat(SimplePermission.create("agent:abc,xyz:c")
                .implies(SimplePermission.create("agent:*"))).isFalse();

        assertThat(SimplePermission.create("agent:xyz:c")
                .implies(SimplePermission.create("agent:abc:c"))).isFalse();
        assertThat(SimplePermission.create("agent:xyz")
                .implies(SimplePermission.create("agent:abc:c"))).isFalse();
        assertThat(SimplePermission.create("agent:xyz:*")
                .implies(SimplePermission.create("agent:abc:c"))).isFalse();
    }

    @Test
    public void testAgentRollupPermissions() throws Exception {
        assertThat(SimplePermission.create("agent:\"abc::\",\"xyz::\":c")
                .implies(SimplePermission.create("agent:\"abc::ddd\":c"))).isTrue();
        assertThat(SimplePermission.create("agent:\"abc::\",\"xyz::\"")
                .implies(SimplePermission.create("agent:\"abc::ddd\":c"))).isTrue();

        assertThat(SimplePermission.create("agent:\"abc::\",\"xyz::\":c")
                .implies(SimplePermission.create("agent:\"abcd::eee\":c"))).isFalse();
        assertThat(SimplePermission.create("agent:\"abc::\",\"xyz::\"")
                .implies(SimplePermission.create("agent:\"abcd::eee\":c"))).isFalse();

        assertThat(SimplePermission.create("agent:\"abc::ddd\",\"xyz::\":c")
                .implies(SimplePermission.create("agent:\"abc::\":c"))).isFalse();
        assertThat(SimplePermission.create("agent:\"abc::ddd\",\"xyz::\"")
                .implies(SimplePermission.create("agent:\"abc::\":c"))).isFalse();
    }
}
