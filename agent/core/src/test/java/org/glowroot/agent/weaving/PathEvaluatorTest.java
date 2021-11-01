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

import com.google.common.collect.Lists;
import org.junit.jupiter.api.Test;

import org.glowroot.agent.weaving.MessageTemplateImpl.PartType;
import org.glowroot.agent.weaving.MessageTemplateImpl.PathEvaluator;
import org.glowroot.agent.weaving.MessageTemplateImpl.ValuePathPart;

import static com.google.common.base.Charsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

public class PathEvaluatorTest {

    @Test
    public void shouldCallGetterMethod() throws Exception {
        // given
        PathEvaluator pathEvaluator = PathEvaluator.create(SomeObject.class, "one");
        // when
        String value = (String) pathEvaluator.evaluateOnBase(new SomeObject());
        // then
        assertThat(value).isEqualTo("1");
    }

    @Test
    public void shouldCallBooleanGetterMethod() throws Exception {
        // given
        PathEvaluator pathEvaluator = PathEvaluator.create(SomeObject.class, "two");
        // when
        boolean value = (Boolean) pathEvaluator.evaluateOnBase(new SomeObject());
        // then
        assertThat(value).isTrue();
    }

    @Test
    public void shouldCallNonGetterMethod() throws Exception {
        // given
        PathEvaluator pathEvaluator = PathEvaluator.create(SomeObject.class, "three");
        // when
        String value = (String) pathEvaluator.evaluateOnBase(new SomeObject());
        // then
        assertThat(value).isEqualTo("3");
    }

    @Test
    public void shouldGetField() throws Exception {
        // given
        PathEvaluator pathEvaluator = PathEvaluator.create(SomeObject.class, "four");
        // when
        String value = (String) pathEvaluator.evaluateOnBase(new SomeObject());
        // then
        assertThat(value).isEqualTo("4");
    }

    @Test
    public void shouldCallMethodOnPackagePrivateClass() throws Exception {
        // given
        PathEvaluator pathEvaluator = PathEvaluator.create(List.class, "size");
        List<String> list = Lists.newArrayList();
        list = Collections.synchronizedList(list);
        // when
        int value = (Integer) pathEvaluator.evaluateOnBase(list);
        // then
        assertThat(value).isEqualTo(0);
    }

    @Test
    public void shouldTestNestedPath() throws Exception {
        // given
        PathEvaluator pathEvaluator = PathEvaluator.create(A.class, "b.str");
        // when
        String value = (String) pathEvaluator.evaluateOnBase(new A());
        // then
        assertThat(value).isEqualTo("abc");
    }

    @Test
    public void shouldTestNestedPathWithNull() throws Exception {
        // given
        PathEvaluator pathEvaluator = PathEvaluator.create(A.class, "b.nil.str");
        // when
        String value = (String) pathEvaluator.evaluateOnBase(new A());
        // then
        assertThat(value).isNull();
    }

    @Test
    public void shouldTestNestedArrayPathNavigation() throws Exception {
        // given
        PathEvaluator pathEvaluator = PathEvaluator.create(A.class, "b.c.d.str");
        // when
        Object[] value = (Object[]) pathEvaluator.evaluateOnBase(new A());
        // then
        assertThat(value).containsExactly("xyz", "xyz");
    }

    @Test
    public void shouldTestRemainingPath() throws Exception {
        // given
        PathEvaluator pathEvaluator = PathEvaluator.create(A.class, "b.eee");
        A a = new A();
        a.b = new E();
        // when
        String value = (String) pathEvaluator.evaluateOnBase(a);
        // then
        assertThat(value).isEqualTo("eeeeee");
    }

    @Test
    public void shouldFormatByteArrayAsHex() throws Exception {
        // given
        ValuePathPart valuePathPart =
                new ValuePathPart(PartType.THIS_PATH, SomeObject.class, "bytes");
        // when
        String value = valuePathPart.evaluatePart(new SomeObject());
        // then
        assertThat(value).isEqualTo("0x78797a");
    }

    @Test
    public void shouldFormatByteArrayUsingCharset() throws Exception {
        // given
        ValuePathPart valuePathPart =
                new ValuePathPart(PartType.THIS_PATH, SomeObject.class, "bytes|charset:UTF-8");
        // when
        String value = valuePathPart.evaluatePart(new SomeObject());
        // then
        assertThat(value).isEqualTo("xyz");
    }

    @Test
    public void shouldFormatByteArrayUsingDefaultCharset() throws Exception {
        // given
        ValuePathPart valuePathPart =
                new ValuePathPart(PartType.THIS_PATH, SomeObject.class, "bytes|charset:default");
        // when
        String value = valuePathPart.evaluatePart(new SomeObject());
        // then
        assertThat(value).isEqualTo("xyz");
    }

    @SuppressWarnings("unused")
    private static class SomeObject {

        private final String one = "distraction";
        private final String two = "distraction";
        private final String three = "distraction";
        private final String four = "4";

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

        public byte[] bytes() {
            return "xyz".getBytes(UTF_8);
        }
    }

    @SuppressWarnings("unused")
    private static class A {
        private B b = new B();
    }

    @SuppressWarnings("unused")
    private static class B {
        private final C[] c = new C[] {new C(), new C()};
        private final String str = "abc";
        private final String nil = null;
    }

    @SuppressWarnings("unused")
    private static class C {
        private final D d = new D();
    }

    @SuppressWarnings("unused")
    private static class D {
        private final String str = "xyz";
    }

    @SuppressWarnings("unused")
    private static class E extends B {
        private final String eee = "eeeeee";
    }
}
