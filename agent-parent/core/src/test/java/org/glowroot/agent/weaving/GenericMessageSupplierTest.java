/*
 * Copyright 2013-2016 the original author or authors.
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

import org.junit.Test;

import org.glowroot.agent.plugin.api.Message;
import org.glowroot.agent.plugin.api.internal.ReadableMessage;

import static org.assertj.core.api.Assertions.assertThat;

public class GenericMessageSupplierTest {

    @Test
    public void shouldRenderConstant() throws Exception {
        MessageTemplate template = MessageTemplate.create("abc",
                TestReceiver.class.getMethod("execute", HasName.class));
        Message message = GenericMessageSupplier
                .create(template, new HasName(), "execute", new HasName()).get();
        String text = ((ReadableMessage) message).getText();
        assertThat(text).isEqualTo("abc");
    }

    @Test
    public void shouldRenderNormal() throws Exception {
        MessageTemplate template =
                MessageTemplate.create("{{this.class.name}}.{{methodName}}(): {{0.name}} => {{_}}",
                        TestReceiver.class.getMethod("execute", HasName.class));
        Message message = GenericMessageSupplier
                .create(template, new TestReceiver(), "execute", new HasName()).get();
        String text = ((ReadableMessage) message).getText();
        assertThat(text).isEqualTo(TestReceiver.class.getName() + ".execute(): the name => ");
    }

    @Test
    public void shouldRenderNullPart() throws Exception {
        MessageTemplate template =
                MessageTemplate.create("{{this.class.name}}.{{methodName}}(): {{0.name}} => {{_}}",
                        TestReceiver.class.getMethod("execute", HasName.class));
        Message message =
                GenericMessageSupplier.create(template, null, "execute", new HasName()).get();
        String text = ((ReadableMessage) message).getText();
        assertThat(text).isEqualTo("null.execute(): the name => ");
    }

    @Test
    public void shouldRenderRequestedArgOutOfBounds() throws Exception {
        MessageTemplate template = MessageTemplate.create(
                "{{this.class.name}}.{{methodName}}(): {{0.name}}, {{1.oops}} => {{_}}",
                TestReceiver.class.getMethod("execute", HasName.class));
        Message message = GenericMessageSupplier
                .create(template, new TestReceiver(), "execute", new HasName()).get();
        String text = ((ReadableMessage) message).getText();
        assertThat(text).isEqualTo(TestReceiver.class.getName()
                + ".execute(): the name, <requested arg index out of bounds: 1> => ");
    }

    @Test
    public void shouldRenderTrailingText() throws Exception {
        MessageTemplate template =
                MessageTemplate.create("{{this.class.name}}.{{methodName}}(): {{0.name}} trailing",
                        TestReceiver.class.getMethod("execute", HasName.class));
        Message message = GenericMessageSupplier
                .create(template, new TestReceiver(), "execute", new HasName()).get();
        String text = ((ReadableMessage) message).getText();
        assertThat(text).isEqualTo(TestReceiver.class.getName() + ".execute(): the name trailing");
    }

    @Test
    public void shouldRenderBadTemplate() throws Exception {
        MessageTemplate template =
                MessageTemplate.create("{{this.class.name}}.{{methodName}}(): {{1.name}} trailing",
                        TestReceiver.class.getMethod("execute", HasName.class));
        Message message = GenericMessageSupplier
                .create(template, new TestReceiver(), "execute", new HasName()).get();
        String text = ((ReadableMessage) message).getText();
        assertThat(text).isEqualTo(TestReceiver.class.getName()
                + ".execute(): <requested arg index out of bounds: 1> trailing");
    }

    @Test
    public void shouldRenderBadTemplate2() throws Exception {
        MessageTemplate template =
                MessageTemplate.create("{{this.class.name}}.{{methodName}}(): {{x.name}} trailing",
                        TestReceiver.class.getMethod("execute", HasName.class));
        Message message =
                GenericMessageSupplier.create(template, new TestReceiver(), "execute").get();
        String text = ((ReadableMessage) message).getText();
        assertThat(text)
                .isEqualTo(TestReceiver.class.getName() + ".execute(): {{x.name}} trailing");
    }

    @Test
    public void shouldRenderBadMessage() throws Exception {
        MessageTemplate template =
                MessageTemplate.create("{{this.class.name}}.{{methodName}}(): {{0.name}} trailing",
                        TestReceiver.class.getMethod("execute", HasName.class));
        Message message =
                GenericMessageSupplier.create(template, new TestReceiver(), "execute").get();
        String text = ((ReadableMessage) message).getText();
        assertThat(text).isEqualTo(TestReceiver.class.getName()
                + ".execute(): <requested arg index out of bounds: 0> trailing");
    }

    @Test
    public void shouldRenderMessageWithThrowingPart() throws Exception {
        MessageTemplate template = MessageTemplate.create(
                "{{this.class.name}}.{{methodName}}(): {{0.throwingName}} trailing",
                TestReceiver.class.getMethod("execute", HasName.class));
        Message message = GenericMessageSupplier
                .create(template, new TestReceiver(), "execute", new HasName()).get();
        String text = ((ReadableMessage) message).getText();
        assertThat(text).isEqualTo(TestReceiver.class.getName()
                + ".execute(): <error evaluating: java.lang.RuntimeException: Abc Xyz> trailing");
    }

    @Test
    public void shouldRenderArray() throws Exception {
        MessageTemplate template =
                MessageTemplate.create("{{this.class.name}}.{{methodName}}(): {{0.names}}",
                        TestArrayReceiver.class.getMethod("execute", HasArray.class));
        Message message = GenericMessageSupplier
                .create(template, new TestReceiver(), "execute", new HasArray()).get();
        String text = ((ReadableMessage) message).getText();
        assertThat(text).isEqualTo(TestReceiver.class.getName() + ".execute(): [the name, two]");
    }

    @Test
    public void shouldRenderArray1() throws Exception {
        MessageTemplate template =
                MessageTemplate.create("{{this.class.name}}.{{methodName}}(): {{0.names.name}}",
                        TestArrayReceiver1.class.getMethod("execute", HasArray1.class));
        Message message = GenericMessageSupplier
                .create(template, new TestReceiver(), "execute", new HasArray1()).get();
        String text = ((ReadableMessage) message).getText();
        assertThat(text)
                .isEqualTo(TestReceiver.class.getName() + ".execute(): [the name, the name]");
    }

    @Test
    public void shouldRenderArray2() throws Exception {
        MessageTemplate template =
                MessageTemplate.create("{{this.class.name}}.{{methodName}}(): {{0.names.name}}",
                        TestArrayReceiver2.class.getMethod("execute", HasArray2.class));
        Message message = GenericMessageSupplier
                .create(template, new TestReceiver(), "execute", new HasArray2()).get();
        String text = ((ReadableMessage) message).getText();
        assertThat(text)
                .isEqualTo(TestReceiver.class.getName() + ".execute(): [[the name, the name]]");
    }

    @Test
    public void shouldRenderArrayLength() throws Exception {
        MessageTemplate template =
                MessageTemplate.create("{{this.class.name}}.{{methodName}}(): {{0.names.length}}",
                        TestArrayReceiver.class.getMethod("execute", HasArray.class));
        Message message = GenericMessageSupplier
                .create(template, new TestReceiver(), "execute", new HasArray()).get();
        String text = ((ReadableMessage) message).getText();
        assertThat(text).isEqualTo(TestReceiver.class.getName() + ".execute(): 2");
    }

    public static class HasName {
        public String getName() {
            return "the name";
        }
        public String getThrowingName() {
            throw new RuntimeException("Abc Xyz");
        }
    }

    public static class HasArray {
        public String[] getNames() {
            return new String[] {"the name", "two"};
        }
    }

    public static class HasArray1 {
        public HasName[] getNames() {
            return new HasName[] {new HasName(), new HasName()};
        }
    }

    public static class HasArray2 {
        public HasName[][] getNames() {
            return new HasName[][] {new HasName[] {new HasName(), new HasName()}};
        }
    }

    public static class TestReceiver {
        public void execute(@SuppressWarnings("unused") HasName arg) {}
    }

    public static class TestArrayReceiver {
        public void execute(@SuppressWarnings("unused") HasArray arg) {}
    }

    public static class TestArrayReceiver1 {
        public void execute(@SuppressWarnings("unused") HasArray1 arg) {}
    }

    public static class TestArrayReceiver2 {
        public void execute(@SuppressWarnings("unused") HasArray2 arg) {}
    }
}
