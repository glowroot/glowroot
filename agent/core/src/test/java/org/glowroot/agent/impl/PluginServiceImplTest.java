/*
 * Copyright 2018 the original author or authors.
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
package org.glowroot.agent.impl;

import java.util.Map;

import org.junit.jupiter.api.Test;

import org.glowroot.agent.impl.PluginServiceImpl.Beans2;

import static org.assertj.core.api.Assertions.assertThat;

public class PluginServiceImplTest {

    @Test
    public void shouldReturnAllPropertiesAsText() {
        Map<String, String> properties = Beans2.propertiesAsText(new SomeObject());
        assertThat(properties).containsOnlyKeys("one", "two");
        assertThat(properties.get("one")).isEqualTo("1");
        assertThat(properties.get("two")).isEqualTo("true");
    }

    @SuppressWarnings("unused")
    private static class SomeObject {

        private final String one = "distraction";
        private final String two = "distraction";
        private final String three = "distraction";
        private final String four = "4";

        public String get() {
            return "distraction";
        }

        public String getOne() {
            return "1";
        }

        public String getone() {
            return "distraction";
        }

        public String isOne() {
            return "distraction";
        }

        public String one() {
            return "distraction";
        }

        public boolean isTwo() {
            return true;
        }

        public boolean two() {
            return false;
        }

        public String three() {
            return "3";
        }
    }
}
