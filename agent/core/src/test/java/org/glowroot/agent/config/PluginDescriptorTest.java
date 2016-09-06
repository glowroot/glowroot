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

import java.util.List;
import java.util.Locale;

import com.google.common.collect.Lists;
import org.junit.Test;

import org.glowroot.agent.config.PluginCache.PluginDescriptorOrdering;

import static org.assertj.core.api.Assertions.assertThat;

public class PluginDescriptorTest {

    @Test
    public void testSorting() {
        // given
        List<PluginDescriptor> pluginDescriptors = Lists.newArrayList();
        pluginDescriptors.add(pluginDescriptorWithName("Zzz"));
        pluginDescriptors.add(pluginDescriptorWithName("Yyy Plugin"));
        pluginDescriptors.add(pluginDescriptorWithName("Xxx plugin"));
        pluginDescriptors.add(pluginDescriptorWithName("Aaa Plugin"));
        pluginDescriptors.add(pluginDescriptorWithName("Bbb"));
        // when
        List<PluginDescriptor> sortedPluginDescriptors =
                new PluginDescriptorOrdering().sortedCopy(pluginDescriptors);
        // then
        assertThat(sortedPluginDescriptors.get(0).name()).isEqualTo("Aaa Plugin");
        assertThat(sortedPluginDescriptors.get(1).name()).isEqualTo("Bbb");
        assertThat(sortedPluginDescriptors.get(2).name()).isEqualTo("Xxx plugin");
        assertThat(sortedPluginDescriptors.get(3).name()).isEqualTo("Yyy Plugin");
        assertThat(sortedPluginDescriptors.get(4).name()).isEqualTo("Zzz");
    }

    private static PluginDescriptor pluginDescriptorWithName(String name) {
        return ImmutablePluginDescriptor.builder()
                .name(name)
                .id(name.toLowerCase(Locale.ENGLISH).replace(' ', '-'))
                .build();
    }
}
