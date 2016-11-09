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
package org.glowroot.central.repo;

import java.util.Set;

import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Ordering;
import org.junit.Test;

import static org.assertj.core.api.Assertions.assertThat;

public class SchemaUpgradeTest {

    @Test
    public void shouldUpgradeWildcardPermissions() throws Exception {
        // given
        Set<String> permissions = ImmutableSet.of("agent:*:view");
        // when
        permissions = SchemaUpgrade.upgradePermissions(permissions);
        // then
        assertThat(Ordering.natural().sortedCopy(permissions)).containsExactly(
                "agent:*:error", "agent:*:jvm:environment", "agent:*:jvm:gauges",
                "agent:*:transaction");
    }

    @Test
    public void shouldUpgradeSingleAgentPermissions() throws Exception {
        // given
        Set<String> permissions = ImmutableSet.of("agent:abc:view");
        // when
        permissions = SchemaUpgrade.upgradePermissions(permissions);
        // then
        assertThat(Ordering.natural().sortedCopy(permissions)).containsExactly("agent:abc:error",
                "agent:abc:jvm:environment", "agent:abc:jvm:gauges", "agent:abc:transaction");
    }

    @Test
    public void shouldUpgradeMultiAgentPermissions() throws Exception {
        // given
        Set<String> permissions = ImmutableSet.of("agent:abc,mno,xyz:view");
        // when
        permissions = SchemaUpgrade.upgradePermissions(permissions);
        // then
        assertThat(Ordering.natural().sortedCopy(permissions)).containsExactly(
                "agent:abc,mno,xyz:error", "agent:abc,mno,xyz:jvm:environment",
                "agent:abc,mno,xyz:jvm:gauges", "agent:abc,mno,xyz:transaction");
    }
}
