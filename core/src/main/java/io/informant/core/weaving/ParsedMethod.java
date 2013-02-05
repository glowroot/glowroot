/**
 * Copyright 2012-2013 the original author or authors.
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
package io.informant.core.weaving;

import org.objectweb.asm.Type;

import checkers.igj.quals.Immutable;
import checkers.nullness.quals.Nullable;

import com.google.common.base.Objects;
import com.google.common.collect.ImmutableList;

/**
 * @author Trask Stalnaker
 * @since 0.5
 */
@Immutable
public class ParsedMethod {

    private final String name;
    private final ImmutableList<Type> argTypes;
    private final Type returnType;
    private final int modifiers;

    static ParsedMethod from(String name, ImmutableList<Type> argTypes, Type returnType,
            int modifiers) {
        return new ParsedMethod(name, argTypes, returnType, modifiers);
    }

    private ParsedMethod(String name, ImmutableList<Type> argTypes, Type returnType,
            int modifiers) {
        this.name = name;
        this.argTypes = argTypes;
        this.returnType = returnType;
        this.modifiers = modifiers;
    }

    public String getName() {
        return name;
    }

    public ImmutableList<Type> getArgTypes() {
        return argTypes;
    }

    public Type getReturnType() {
        return returnType;
    }

    public int getModifiers() {
        return modifiers;
    }

    // equals and hashCode are only defined in terms of name and argTypes since those uniquely
    // identify a method within a given class
    @Override
    public boolean equals(@Nullable Object obj) {
        if (obj == this) {
            return true;
        }
        if (obj instanceof ParsedMethod) {
            ParsedMethod that = (ParsedMethod) obj;
            return Objects.equal(name, that.name) && Objects.equal(argTypes, that.argTypes);
        }
        return false;
    }

    // equals and hashCode are only defined in terms of name and argTypes since those uniquely
    // identify a method within a given class
    @Override
    public int hashCode() {
        return Objects.hashCode(name, argTypes);
    }

    @Override
    public String toString() {
        return Objects.toStringHelper(this)
                .add("name", name)
                .add("argTypes", argTypes)
                .add("returnType", returnType)
                .add("modifiers", modifiers)
                .toString();
    }
}
