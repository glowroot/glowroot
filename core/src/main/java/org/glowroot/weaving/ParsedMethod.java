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
// Strings are interned to reduce memory footprint of ParsedTypeCache
@Immutable
public class ParsedMethod {

    private final String name;
    private final ImmutableList<String> argTypes;
    private final String returnType;
    private final int modifiers;

    // fields below are needed for public methods in case they end up fulfilling an interface in a
    // subclass
    private final boolean isFinal;
    @Nullable
    private final String signature;
    private final ImmutableList<String> exceptions;

    static ParsedMethod from(String name, List<Type> argTypes, Type returnType, int modifiers,
            @Nullable String signature, List<String> exceptions) {
        List<String> argTypeNames = Lists.newArrayList();
        for (Type argType : argTypes) {
            argTypeNames.add(argType.getClassName());
        }
        String returnTypeName = returnType.getClassName();
        return new ParsedMethod(name, argTypeNames, returnTypeName, modifiers, signature,
                exceptions);
    }

    // desc is only passed to validate calculation for now
    private ParsedMethod(String name, List<String> argTypes, String returnType, int modifiers,
            @Nullable String signature, List<String> exceptions) {
        this.name = name.intern();
        this.argTypes = internStringList(argTypes);
        this.returnType = returnType.intern();
        // remove final and synchronized modifiers from the parsed method model
        this.modifiers = modifiers & ~ACC_FINAL & ~ACC_SYNCHRONIZED;
        // but still need to keep track of whether method is final
        isFinal = Modifier.isFinal(modifiers);
        this.signature = signature == null ? null : signature.intern();
        this.exceptions = internStringList(exceptions);
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

    // this is only used for the rare case of WeavingClassVisitor.overrideAndWeaveInheritedMethod()
    @Nullable
    String getSignature() {
        return signature;
    }

    // this is only used for the rare case of WeavingClassVisitor.overrideAndWeaveInheritedMethod()
    ImmutableList<String> getExceptions() {
        return exceptions;
    }

    // this is only used for the rare case of WeavingClassVisitor.overrideAndWeaveInheritedMethod()
    String getDesc() {
        Type[] types = new Type[argTypes.size()];
        for (int i = 0; i < argTypes.size(); i++) {
            types[i] = getType(argTypes.get(i));
        }
        return Type.getMethodDescriptor(getType(returnType), types);
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

    static ImmutableList<String> internStringList(List<String> strings) {
        List<String> internedStrings = Lists.newArrayListWithCapacity(strings.size());
        for (String string : strings) {
            internedStrings.add(string.intern());
        }
        return ImmutableList.copyOf(internedStrings);
    }

    // this is only used for the rare case of WeavingClassVisitor.overrideAndWeaveInheritedMethod()
    private static Type getType(String type) {
        if (type.equals(Void.TYPE.getName())) {
            return Type.VOID_TYPE;
        }
        if (type.equals(Boolean.TYPE.getName())) {
            return Type.BOOLEAN_TYPE;
        }
        if (type.equals(Character.TYPE.getName())) {
            return Type.CHAR_TYPE;
        }
        if (type.equals(Byte.TYPE.getName())) {
            return Type.BYTE_TYPE;
        }
        if (type.equals(Short.TYPE.getName())) {
            return Type.SHORT_TYPE;
        }
        if (type.equals(Integer.TYPE.getName())) {
            return Type.INT_TYPE;
        }
        if (type.equals(Float.TYPE.getName())) {
            return Type.FLOAT_TYPE;
        }
        if (type.equals(Long.TYPE.getName())) {
            return Type.LONG_TYPE;
        }
        if (type.equals(Double.TYPE.getName())) {
            return Type.DOUBLE_TYPE;
        }
        if (type.endsWith("[]")) {
            StringBuilder sb = new StringBuilder();
            String remaining = type;
            while (remaining.endsWith("[]")) {
                sb.append("[");
                remaining = remaining.substring(0, remaining.length() - 2);
            }
            Type elementType = getType(remaining);
            sb.append(elementType.getDescriptor());
            return Type.getType(sb.toString());
        }
        return Type.getType('L' + type.replace('.', '/') + ';');
    }
}
