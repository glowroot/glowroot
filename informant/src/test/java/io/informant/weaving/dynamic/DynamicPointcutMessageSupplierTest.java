/**
 * Copyright 2013 the original author or authors.
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
package io.informant.weaving.dynamic;

import org.junit.Test;

import io.informant.api.Message;
import io.informant.api.internal.ReadableMessage;

import static org.fest.assertions.api.Assertions.assertThat;

/**
 * @author Trask Stalnaker
 * @since 0.5
 */
public class DynamicPointcutMessageSupplierTest {

    @Test
    public void shouldRenderConstant() {
        DynamicPointcutMessageTemplate template = DynamicPointcutMessageTemplate.create("abc");
        Message message = DynamicPointcutMessageSupplier.create(template, new HasName(),
                "execute", new HasName()).get();
        String text = ((ReadableMessage) message).getText();
        assertThat(text).isEqualTo("abc");
    }

    @Test
    public void shouldRenderNormal() {
        DynamicPointcutMessageTemplate template = DynamicPointcutMessageTemplate
                .create("{{this.class.name}}.{{methodName}}(): {{0.name}} => {{ret}}");
        Message message = DynamicPointcutMessageSupplier.create(template, new HasName(),
                "execute", new HasName()).get();
        String text = ((ReadableMessage) message).getText();
        assertThat(text).isEqualTo(HasName.class.getName() + ".execute(): the name => ");
    }

    @Test
    public void shouldRenderTrailingText() {
        DynamicPointcutMessageTemplate template = DynamicPointcutMessageTemplate
                .create("{{this.class.name}}.{{methodName}}(): {{0.name}} trailing");
        Message message = DynamicPointcutMessageSupplier.create(template, new HasName(),
                "execute", new HasName()).get();
        String text = ((ReadableMessage) message).getText();
        assertThat(text).isEqualTo(HasName.class.getName() + ".execute(): the name trailing");
    }

    private static class HasName {
        @SuppressWarnings("unused")
        public String getName() {
            return "the name";
        }
    }
}
