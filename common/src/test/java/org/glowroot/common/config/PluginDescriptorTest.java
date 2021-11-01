/*
 * Copyright 2015-2018 the original author or authors.
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

import java.util.List;

import com.google.common.collect.Lists;
import com.google.common.collect.Ordering;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

public class PluginDescriptorTest {

    @Test
    public void testSorting() {
        // given
        List<String> pluginNames = Lists.newArrayList();
        pluginNames.add("Zzz");
        pluginNames.add("Yyy Plugin");
        pluginNames.add("Xxx plugin");
        pluginNames.add("Aaa Plugin");
        pluginNames.add("Bbb");
        // when
        List<String> sortedPluginNames = new PluginNameOrdering().sortedCopy(pluginNames);
        // then
        assertThat(sortedPluginNames.get(0)).isEqualTo("Aaa Plugin");
        assertThat(sortedPluginNames.get(1)).isEqualTo("Bbb");
        assertThat(sortedPluginNames.get(2)).isEqualTo("Xxx plugin");
        assertThat(sortedPluginNames.get(3)).isEqualTo("Yyy Plugin");
        assertThat(sortedPluginNames.get(4)).isEqualTo("Zzz");
    }

    private static class PluginNameOrdering extends Ordering<String> {
        @Override
        public int compare(String left, String right) {
            return PluginNameComparison.compareNames(left, right);
        }
    }
}
