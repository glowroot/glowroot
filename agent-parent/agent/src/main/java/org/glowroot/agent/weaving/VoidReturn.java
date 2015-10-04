/*
 * Copyright 2013-2015 the original author or authors.
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

import javax.annotation.Nullable;

import org.glowroot.agent.plugin.api.weaving.OptionalReturn;
import org.glowroot.common.util.UsedByGeneratedBytecode;

@UsedByGeneratedBytecode
public class VoidReturn implements OptionalReturn {

    private static final VoidReturn INSTANCE = new VoidReturn();

    public static OptionalReturn getInstance() {
        return INSTANCE;
    }

    private VoidReturn() {}

    @Override
    public @Nullable Object getValue() {
        return null;
    }

    @Override
    public boolean isVoid() {
        return true;
    }
}
