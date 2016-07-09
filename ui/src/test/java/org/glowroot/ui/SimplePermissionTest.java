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
package org.glowroot.ui;

import org.junit.Test;

import org.glowroot.ui.HttpSessionManager.SimplePermission;

import static org.assertj.core.api.Assertions.assertThat;

public class SimplePermissionTest {

    @Test
    public void test() throws Exception {
        assertThat(new SimplePermission("a:b:c").implies(new SimplePermission("a:b:c"))).isTrue();
        assertThat(new SimplePermission("a:b").implies(new SimplePermission("a:b:c"))).isTrue();
        assertThat(new SimplePermission("a:b:c").implies(new SimplePermission("a:b"))).isFalse();

        assertThat(new SimplePermission("a:b:*").implies(new SimplePermission("a:b:c"))).isTrue();
        assertThat(new SimplePermission("a:b:c").implies(new SimplePermission("a:b:*"))).isFalse();

        assertThat(new SimplePermission("a:*").implies(new SimplePermission("a:b:c"))).isTrue();
        assertThat(new SimplePermission("a:b:c").implies(new SimplePermission("a:*"))).isFalse();
    }
}
