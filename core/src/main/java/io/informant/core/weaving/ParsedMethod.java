/**
 * Copyright 2012 the original author or authors.
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

import java.util.Arrays;

import javax.annotation.Nullable;
import javax.annotation.concurrent.Immutable;

import org.objectweb.asm.Type;

import com.google.common.base.Objects;

/**
 * @author Trask Stalnaker
 * @since 0.5
 */
@Immutable
class ParsedMethod {

    private final String name;
    private final Type[] argTypes;
    private final Type returnType;
    private final int modifiers;

    static ParsedMethod from(String name, Type[] argTypes, Type returnType, int modifiers) {
        return new ParsedMethod(name, argTypes, returnType, modifiers);
    }

    private ParsedMethod(String name, Type[] args, Type returnType, int modifiers) {
        this.name = name;
        this.argTypes = args;
        this.returnType = returnType;
        this.modifiers = modifiers;
    }

    String getName() {
        return name;
    }

    Type[] getArgTypes() {
        return argTypes;
    }

    Type getReturnType() {
        return returnType;
    }

    int getModifiers() {
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
            return Objects.equal(name, that.name) && Arrays.equals(argTypes, that.argTypes);
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
