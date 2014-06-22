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
package org.glowroot.local.ui;

import java.lang.reflect.Modifier;
import java.util.List;

import com.google.common.base.Objects;
import com.google.common.collect.ComparisonChain;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;
import com.google.common.collect.Ordering;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.checkerframework.dataflow.qual.Pure;
import org.objectweb.asm.Type;

import org.glowroot.markers.Immutable;

import static com.google.common.base.Preconditions.checkNotNull;

/**
 * @author Trask Stalnaker
 * @since 0.5
 */
@Immutable
public class UiParsedMethod {

    private final String name;
    private final ImmutableList<String> argTypes;
    private final String returnType;
    private final int modifiers;

    @Nullable
    private final String signature;
    private final ImmutableList<String> exceptions;

    static UiParsedMethod from(String name, List<Type> argTypes, Type returnType, int modifiers,
            @Nullable String signature, List<String> exceptions) {
        List<String> argTypeNames = Lists.newArrayList();
        for (Type argType : argTypes) {
            argTypeNames.add(argType.getClassName());
        }
        String returnTypeName = returnType.getClassName();
        return new UiParsedMethod(name, argTypeNames, returnTypeName, modifiers, signature,
                exceptions);
    }

    private UiParsedMethod(String name, List<String> argTypes, String returnType, int modifiers,
            @Nullable String signature, List<String> exceptions) {
        this.name = name;
        this.argTypes = ImmutableList.copyOf(argTypes);
        this.returnType = returnType.intern();
        this.modifiers = modifiers;
        this.signature = signature;
        this.exceptions = ImmutableList.copyOf(exceptions);
    }

    public String getName() {
        return name;
    }

    // these are class names
    public ImmutableList<String> getArgTypes() {
        return argTypes;
    }

    public String getReturnType() {
        return returnType;
    }

    public int getModifiers() {
        return modifiers;
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
    // this is currently important because UiParsedMethod is used in a set to filter out duplicates
    // in PointcutConfigJsonService
    @Override
    @Pure
    public boolean equals(@Nullable Object obj) {
        if (obj == this) {
            return true;
        }
        if (obj instanceof UiParsedMethod) {
            UiParsedMethod that = (UiParsedMethod) obj;
            return Objects.equal(name, that.name) && Objects.equal(argTypes, that.argTypes);
        }
        return false;
    }

    // equals and hashCode are only defined in terms of name and argTypes since those uniquely
    // identify a method within a given class
    // this is currently important because UiParsedMethod is used in a set to filter out duplicates
    // in PointcutConfigJsonService
    @Override
    @Pure
    public int hashCode() {
        return Objects.hashCode(name, argTypes);
    }

    @Override
    @Pure
    public String toString() {
        return Objects.toStringHelper(this)
                .add("name", name)
                .add("argTypes", argTypes)
                .add("returnType", returnType)
                .add("modifiers", modifiers)
                .add("signature", signature)
                .add("exceptions", exceptions)
                .toString();
    }

    @Immutable
    static class UiParsedMethodOrdering extends Ordering<UiParsedMethod> {

        static final UiParsedMethodOrdering INSTANCE = new UiParsedMethodOrdering();

        @Override
        public int compare(@Nullable UiParsedMethod left, @Nullable UiParsedMethod right) {
            checkNotNull(left);
            checkNotNull(right);
            return ComparisonChain.start()
                    .compare(getAccessibility(left), getAccessibility(right))
                    .compare(left.getName(), right.getName())
                    .compare(left.getArgTypes().size(), right.getArgTypes().size())
                    .compare(left.getArgTypes().size(), right.getArgTypes().size())
                    .result();
        }

        private static int getAccessibility(UiParsedMethod parsedMethod) {
            int modifiers = parsedMethod.getModifiers();
            if (Modifier.isPublic(modifiers)) {
                return 1;
            } else if (Modifier.isProtected(modifiers)) {
                return 2;
            } else if (Modifier.isPrivate(modifiers)) {
                return 4;
            } else {
                // package-private
                return 3;
            }
        }
    }
}
