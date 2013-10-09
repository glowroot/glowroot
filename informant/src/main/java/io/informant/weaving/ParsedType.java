/*
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
package io.informant.weaving;

import checkers.igj.quals.Immutable;
import checkers.nullness.quals.Nullable;
import com.google.common.base.Objects;
import com.google.common.collect.ImmutableList;

import io.informant.markers.NotThreadSafe;

/**
 * @author Trask Stalnaker
 * @since 0.5
 */
// a ParsedType is never created for Object.class
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
    private final boolean hasAdhocPointcut;

    // interfaces that do not extend anything have null superClass
    static ParsedType from(boolean iface, String name, @Nullable String superName,
            ImmutableList<String> interfaceNames, ImmutableList<ParsedMethod> methods) {
        return new ParsedType(iface, name, superName, interfaceNames, methods, false);
    }

    private ParsedType(boolean iface, String name, @Nullable String superName,
            ImmutableList<String> interfaceNames, ImmutableList<ParsedMethod> methods,
            boolean hasAdhocPointcut) {
        this.iface = iface;
        this.name = name;
        this.superName = superName;
        this.interfaceNames = interfaceNames;
        this.methods = methods;
        this.hasAdhocPointcut = hasAdhocPointcut;
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

    @Nullable
    ParsedMethod getMethod(ParsedMethod parsedMethod) {
        for (ParsedMethod method : methods) {
            if (method.equals(parsedMethod)) {
                return method;
            }
        }
        return null;
    }

    public boolean hasAdhocPointcut() {
        return hasAdhocPointcut;
    }

    @Override
    public String toString() {
        return Objects.toStringHelper(this)
                .add("interface", iface)
                .add("name", name)
                .add("superName", superName)
                .add("interfaceNames", interfaceNames)
                .add("methods", methods)
                .add("hasAdhocPointcut", hasAdhocPointcut)
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
        private final ImmutableList<String> interfaceNames;
        private final ImmutableList.Builder<ParsedMethod> methods = ImmutableList.builder();
        private boolean hasAdhocPointcut;

        private Builder(boolean iface, String name, @Nullable String superName,
                ImmutableList<String> interfaceNames) {
            this.iface = iface;
            this.name = name;
            this.superName = superName;
            this.interfaceNames = interfaceNames;
        }

        void addMethod(ParsedMethod method) {
            methods.add(method);
        }

        public void setHasAdhocPointcut(boolean hasAdhocPointcut) {
            this.hasAdhocPointcut = hasAdhocPointcut;
        }

        ParsedType build() {
            return new ParsedType(iface, name, superName, interfaceNames, methods.build(),
                    hasAdhocPointcut);
        }
    }
}
