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

import java.util.Arrays;
import java.util.List;

import com.google.common.base.Objects;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Iterables;
import com.google.common.collect.Lists;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.checkerframework.dataflow.qual.Pure;
import org.objectweb.asm.Type;

import org.glowroot.markers.Immutable;
import org.glowroot.markers.NotThreadSafe;

import static org.objectweb.asm.Opcodes.ACC_NATIVE;

/**
 * @author Trask Stalnaker
 * @since 0.5
 */
// a ParsedType is never created for Object.class
// Strings are interned to reduce memory footprint of ParsedTypeCache
@Immutable
public class ParsedType {

    private final boolean iface;
    private final String name;
    // null superName means the super type is Object.class
    // (a ParsedType is never created for Object.class)
    @Nullable
    private final String superName;
    private final ImmutableList<String> interfaceNames;
    private final ImmutableList<ParsedMethod> methods;
    private final ImmutableList<ParsedMethod> nativeMethods;
    private final boolean hasReweavableAdvice;

    // interfaces that do not extend anything have null superClass
    static ParsedType from(boolean iface, String name, @Nullable String superName,
            List<String> interfaceNames, List<ParsedMethod> methods,
            List<ParsedMethod> nativeMethods) {
        return new ParsedType(iface, name, superName, interfaceNames, methods, nativeMethods,
                false);
    }

    private ParsedType(boolean iface, String name, @Nullable String superName,
            List<String> interfaceNames, List<ParsedMethod> methods,
            List<ParsedMethod> nativeMethods, boolean hasReweavableAdvice) {
        this.iface = iface;
        this.name = name.intern();
        this.superName = superName == null ? null : superName.intern();
        this.interfaceNames = ParsedMethod.internStringList(interfaceNames);
        this.methods = ImmutableList.copyOf(methods);
        this.nativeMethods = ImmutableList.copyOf(nativeMethods);
        this.hasReweavableAdvice = hasReweavableAdvice;
    }

    boolean isInterface() {
        return iface;
    }

    String getName() {
        return name;
    }

    // null superName means the super type is Object.class
    // (a ParsedType is never created for Object.class)
    @Nullable
    String getSuperName() {
        return superName;
    }

    ImmutableList<String> getInterfaceNames() {
        return interfaceNames;
    }

    public ImmutableList<ParsedMethod> getMethods() {
        return methods;
    }

    Iterable<ParsedMethod> getMethodsIncludingNative() {
        return Iterables.concat(methods, nativeMethods);
    }

    @Nullable
    ParsedMethod getMethodOverride(ParsedMethod inheritedMethod) {
        for (ParsedMethod method : methods) {
            if (method.getName().equals(inheritedMethod.getName())
                    && method.getArgTypes().equals(inheritedMethod.getArgTypes())) {
                return method;
            }
        }
        return null;
    }

    boolean hasReweavableAdvice() {
        return hasReweavableAdvice;
    }

    @Override
    @Pure
    public boolean equals(@Nullable Object obj) {
        if (obj instanceof ParsedType) {
            ParsedType that = (ParsedType) obj;
            return Objects.equal(iface, that.iface)
                    && Objects.equal(name, that.name)
                    && Objects.equal(superName, that.superName)
                    && Objects.equal(interfaceNames, that.interfaceNames)
                    && Objects.equal(methods, that.methods)
                    && Objects.equal(nativeMethods, that.nativeMethods)
                    && Objects.equal(hasReweavableAdvice, that.hasReweavableAdvice);
        }
        return false;
    }

    @Override
    @Pure
    public int hashCode() {
        return Objects.hashCode(iface, name, superName, interfaceNames, methods, nativeMethods,
                hasReweavableAdvice);
    }

    @Override
    @Pure
    public String toString() {
        return Objects.toStringHelper(this)
                .add("interface", iface)
                .add("name", name)
                .add("superName", superName)
                .add("interfaceNames", interfaceNames)
                .add("methods", methods)
                .add("nativeMethods", nativeMethods)
                .add("hasReweavableAdvice", hasReweavableAdvice)
                .toString();
    }

    static Builder builder(boolean iface, String name, @Nullable String superName,
            ImmutableList<String> interfaceNames) {
        return new Builder(iface, name, superName, interfaceNames);
    }

    @NotThreadSafe
    static class Builder {

        private final boolean iface;
        private final String name;
        @Nullable
        private final String superName;
        private final List<String> interfaceNames;
        private final List<ParsedMethod> methods = Lists.newArrayList();
        private final List<ParsedMethod> nativeMethods = Lists.newArrayList();
        private boolean hasReweavableAdvice;

        private Builder(boolean iface, String name, @Nullable String superName,
                ImmutableList<String> interfaceNames) {
            this.iface = iface;
            this.name = name;
            this.superName = superName;
            this.interfaceNames = interfaceNames;
        }

        ParsedMethod addParsedMethod(int access, String name, String desc,
                @Nullable String signature, List<String> exceptions) {
            List<Type> argTypes = Arrays.asList(Type.getArgumentTypes(desc));
            ParsedMethod method = ParsedMethod.from(name, argTypes, Type.getReturnType(desc),
                    access, signature, exceptions);
            if ((access & ACC_NATIVE) == 0) {
                methods.add(method);
            } else {
                nativeMethods.add(method);
            }
            return method;
        }
        void setHasReweavableAdvice(boolean hasReweavableAdvice) {
            this.hasReweavableAdvice = hasReweavableAdvice;
        }

        ParsedType build() {
            return new ParsedType(iface, name, superName, interfaceNames, methods, nativeMethods,
                    hasReweavableAdvice);
        }
    }
}
