/*
 * Copyright 2013-2018 the original author or authors.
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
package org.glowroot.agent.weaving;

import java.util.Collections;
import java.util.List;
import java.util.Map;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Lists;
import org.junit.Test;

import static org.assertj.core.api.Assertions.assertThat;

public class BeansTest {

    @Test
    public void shouldCallGetterMethod() throws Exception {
        String value = (String) Beans.value(new SomeObject(), ImmutableList.of("one"));
        assertThat(value).isEqualTo("1");
    }

    @Test
    public void shouldCallBooleanGetterMethod() throws Exception {
        boolean value = (Boolean) Beans.value(new SomeObject(), ImmutableList.of("two"));
        assertThat(value).isTrue();
    }

    @Test
    public void shouldCallNonGetterMethod() throws Exception {
        String value = (String) Beans.value(new SomeObject(), ImmutableList.of("three"));
        assertThat(value).isEqualTo("3");
    }

    @Test
    public void shouldGetField() throws Exception {
        String value = (String) Beans.value(new SomeObject(), ImmutableList.of("four"));
        assertThat(value).isEqualTo("4");
    }

    @Test
    public void shouldGetOnNullObject() throws Exception {
        String value = (String) Beans.value(null, ImmutableList.of("one"));
        assertThat(value).isNull();
    }

    @Test
    public void shouldCallMethodOnPackagePrivateClass() throws Exception {
        List<String> list = Lists.newArrayList();
        list = Collections.synchronizedList(list);
        int size = (Integer) Beans.value(list, ImmutableList.of("size"));
        assertThat(size).isEqualTo(0);
    }

    @Test
    public void shouldGetList() throws Exception {
        @SuppressWarnings("unchecked")
        List<String> value = (List<String>) Beans.value(new SomeObject(), ImmutableList.of("five"));
        assertThat(value).containsExactly("a", "x", "z");
    }

    @Test
    public void shouldGetListSize() throws Exception {
        Integer value = (Integer) Beans.value(new SomeObject(), ImmutableList.of("five", "size"));
        assertThat(value).isEqualTo(3);
    }

    @Test
    public void shouldGetListValues() throws Exception {
        @SuppressWarnings("unchecked")
        List<String> value =
                (List<String>) Beans.value(new SomeObject(), ImmutableList.of("six", "z"));
        assertThat(value).containsExactly("first", "second");
    }

    @Test
    public void shouldGetArray() throws Exception {
        String[] value = (String[]) Beans.value(new SomeObject(), ImmutableList.of("seven"));
        assertThat(value).containsExactly("a", "x", "z");
    }

    @Test
    public void shouldGetArrayLength() throws Exception {
        Integer value =
                (Integer) Beans.value(new SomeObject(), ImmutableList.of("seven", "length"));
        assertThat(value).isEqualTo(3);
    }

    @Test
    public void shouldGetArrayValues() throws Exception {
        Object[] value = (Object[]) Beans.value(new SomeObject(), ImmutableList.of("eight", "z"));
        assertThat(value).containsExactly("first", "second");
    }

    @Test
    public void shouldGetMap() throws Exception {
        @SuppressWarnings("unchecked")
        Map<String, String> value =
                (Map<String, String>) Beans.value(new SomeObject(), ImmutableList.of("nine"));
        assertThat(value).isEqualTo(ImmutableMap.of("a", "b", "c", "d", "e", "f"));
    }

    @Test
    public void shouldGetMapSize() throws Exception {
        Integer value = (Integer) Beans.value(new SomeObject(), ImmutableList.of("nine", "size"));
        assertThat(value).isEqualTo(3);
    }

    @Test
    public void shouldGetMapValue() throws Exception {
        String value = (String) Beans.value(new SomeObject(), ImmutableList.of("nine", "a"));
        assertThat(value).isEqualTo("b");
    }

    @Test
    public void shouldGetMapNestedValue() throws Exception {
        String value = (String) Beans.value(new SomeObject(), ImmutableList.of("ten", "a", "z"));
        assertThat(value).isEqualTo("first");
    }

    @SuppressWarnings("unused")
    private static class SomeObject {

        private final String one = "distraction";
        private final String two = "distraction";
        private final String three = "distraction";
        private final String four = "4";

        private final String z;

        private SomeObject() {
            this("");
        }

        private SomeObject(String z) {
            this.z = z;
        }

        public String getOne() {
            return "1";
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

        public List<String> five() {
            return ImmutableList.of("a", "x", "z");
        }

        public List<SomeObject> six() {
            return ImmutableList.of(new SomeObject("first"), new SomeObject("second"));
        }

        public String[] seven() {
            return new String[] {"a", "x", "z"};
        }

        public SomeObject[] eight() {
            return new SomeObject[] {new SomeObject("first"), new SomeObject("second")};
        }

        public Map<String, String> nine() {
            return ImmutableMap.of("a", "b", "c", "d", "e", "f");
        }

        public Map<String, SomeObject> ten() {
            return ImmutableMap.of("a", new SomeObject("first"), "c", new SomeObject("second"));
        }
    }
}
