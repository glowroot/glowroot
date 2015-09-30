/*
 * Copyright 2012-2015 the original author or authors.
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
package org.glowroot.agent.plugin.api.weaving;

import javax.annotation.Nullable;

/**
 * For modeling an optional return value from a method when it is unknown whether that method
 * returns void or a value (value can be null).
 */
public interface OptionalReturn {

    /**
     * Returns {@code true} if this instance represents a void return.
     */
    boolean isVoid();

    /**
     * Returns the return value. Returns null if this instance represents a void return.
     */
    @Nullable
    Object getValue();
}
