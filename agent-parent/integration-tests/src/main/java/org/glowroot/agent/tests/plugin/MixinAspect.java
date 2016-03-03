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
package org.glowroot.agent.tests.plugin;

import javax.annotation.Nullable;

import org.glowroot.agent.plugin.api.weaving.Mixin;
import org.glowroot.agent.plugin.api.weaving.MixinInit;

public class MixinAspect {

    @Mixin("java.lang.Runnable")
    public static class RunnableMixinImpl implements RunnableMixin {

        private @Nullable String string;

        @MixinInit
        private void initHasString() {
            string = "a string";
        }

        @Override
        public @Nullable String getString() {
            return string;
        }

        @Override
        public void setString(@Nullable String string) {
            this.string = string;
        }
    }

    public interface RunnableMixin {
        @Nullable
        String getString();
        void setString(@Nullable String string);
    }
}
