/*
 * Copyright 2014 the original author or authors.
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
package org.glowroot.weaving;

import com.google.common.collect.ImmutableList;
import org.objectweb.asm.Type;

/**
 * @author Trask Stalnaker
 * @since 0.5
 */
public class LazyDefinedClass {

    private final Type type;
    private final byte[] bytes;
    private final ImmutableList<LazyDefinedClass> dependencies;

    public LazyDefinedClass(Type type, byte[] bytes) {
        this(type, bytes, ImmutableList.<LazyDefinedClass>of());
    }

    public LazyDefinedClass(Type type, byte[] bytes, ImmutableList<LazyDefinedClass> dependencies) {
        this.type = type;
        this.bytes = bytes;
        this.dependencies = dependencies;
    }

    public Type getType() {
        return type;
    }

    public byte[] getBytes() {
        return bytes;
    }

    public ImmutableList<LazyDefinedClass> getDependencies() {
        return dependencies;
    }
}
