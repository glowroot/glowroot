/*
 * Copyright 2012-2014 the original author or authors.
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

import java.lang.reflect.Modifier;
import java.util.List;

import javax.annotation.Nullable;
import javax.annotation.concurrent.Immutable;

import com.google.common.base.Objects;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;
import org.objectweb.asm.Type;

import static org.objectweb.asm.Opcodes.ACC_FINAL;
import static org.objectweb.asm.Opcodes.ACC_SYNCHRONIZED;

/**
 * @author Trask Stalnaker
 * @since 0.5
 */
// TODO intern all Strings in this class to minimize long term memory usage
@Immutable
public class ParsedMethod {

    private final String name;
    private final ImmutableList<String> argTypes;
    private final String returnType;
    private final int modifiers;

    // fields below are needed for public methods in case they end up fulfilling an interface in a
    // subclass
    private final boolean isFinal;
    private final String desc;
    @Nullable
    private final String signature;
    private final ImmutableList<String> exceptions;

    static ParsedMethod from(String name, List<Type> argTypes, Type returnType, int modifiers,
            String desc, @Nullable String signature, List<String> exceptions) {
        List<String> argTypeNames = Lists.newArrayList();
        for (Type argType : argTypes) {
            argTypeNames.add(argType.getClassName());
        }
        String returnTypeName = returnType.getClassName();
        return new ParsedMethod(name, argTypeNames, returnTypeName, modifiers, desc,
                signature, exceptions);
    }

    private ParsedMethod(String name, List<String> argTypes, String returnType, int modifiers,
            String desc, @Nullable String signature, List<String> exceptions) {
        this.name = name;
        this.argTypes = ImmutableList.copyOf(argTypes);
        this.returnType = returnType;
        // remove final and synchronized modifiers from the parsed method model
        this.modifiers = modifiers & ~ACC_FINAL & ~ACC_SYNCHRONIZED;
        // but still need to keep track of whether method is final
        isFinal = Modifier.isFinal(modifiers);
        this.desc = desc;
        this.signature = signature;
        this.exceptions = ImmutableList.copyOf(exceptions);
    }

    public String getName() {
        return name;
    }

    // these are class names, e.g.
    public ImmutableList<String> getArgTypes() {
        return argTypes;
    }

    public String getReturnType() {
        return returnType;
    }

    public int getModifiers() {
        return modifiers;
    }

    boolean isFinal() {
        return isFinal;
    }

    String getDesc() {
        return desc;
    }

    @Nullable
    String getSignature() {
        return signature;
    }

    ImmutableList<String> getExceptions() {
        return exceptions;
    }

    // equals and hashCode are only defined in terms of name and argTypes since those uniquely
    // identify a method within a given class
    /*@Pure*/
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
    /*@Pure*/
    @Override
    public int hashCode() {
        return Objects.hashCode(name, argTypes);
    }

    /*@Pure*/
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
