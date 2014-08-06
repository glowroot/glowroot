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
import java.util.Arrays;
import java.util.List;

import com.google.common.base.Objects;
import com.google.common.base.Predicate;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Iterables;
import com.google.common.collect.Lists;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.objectweb.asm.Type;

import org.glowroot.markers.Immutable;
import org.glowroot.markers.NotThreadSafe;

import static com.google.common.base.Preconditions.checkNotNull;

/**
 * @author Trask Stalnaker
 * @since 0.5
 */
// an AnalyzedClass is never created for Object.class
// Strings are interned to reduce memory footprint of AnalyzedWorld
@Immutable
public class AnalyzedClass {

    private final int modifiers;
    private final String name;
    // null superName means the super class is Object.class
    // (an AnalyzedClass is never created for Object.class)
    @Nullable
    private final String superName;
    private final ImmutableList<String> interfaceNames;
    private final ImmutableList<AnalyzedMethod> analyzedMethods;

    private final ImmutableList<MixinType> mixinTypes;

    // interfaces that do not extend anything have null superClass
    static AnalyzedClass from(int modifiers, String name, @Nullable String superName,
            List<String> interfaceNames, List<AnalyzedMethod> methods) {
        return new AnalyzedClass(modifiers, name, superName, interfaceNames, methods,
                ImmutableList.<MixinType>of());
    }

    private AnalyzedClass(int modifiers, String name, @Nullable String superName,
            List<String> interfaceNames, List<AnalyzedMethod> analyzedMethods,
            List<MixinType> mixinTypes) {
        this.modifiers = modifiers;
        this.name = name.intern();
        this.superName = superName == null ? null : superName.intern();
        this.interfaceNames = AnalyzedMethod.internStringList(interfaceNames);
        this.analyzedMethods = ImmutableList.copyOf(analyzedMethods);
        this.mixinTypes = ImmutableList.copyOf(mixinTypes);
    }

    boolean isInterface() {
        return Modifier.isInterface(modifiers);
    }

    boolean isAbstract() {
        return Modifier.isAbstract(modifiers);
    }

    String getName() {
        return name;
    }

    // null superName means the super class is Object.class
    // (an AnalyzedClass is never created for Object.class)
    @Nullable
    String getSuperName() {
        return superName;
    }

    ImmutableList<String> getInterfaceNames() {
        return interfaceNames;
    }

    public Iterable<AnalyzedMethod> getMethodsExcludingNative() {
        return Iterables.filter(analyzedMethods, new Predicate<AnalyzedMethod>() {
            @Override
            public boolean apply(@Nullable AnalyzedMethod analyzedMethod) {
                checkNotNull(analyzedMethod);
                return !Modifier.isNative(analyzedMethod.getModifiers());
            }
        });
    }

    List<AnalyzedMethod> getAnalyzedMethods() {
        return analyzedMethods;
    }

    ImmutableList<MixinType> getMixinTypes() {
        return mixinTypes;
    }

    boolean hasReweavableAdvice() {
        for (AnalyzedMethod analyzedMethod : analyzedMethods) {
            for (Advice advice : analyzedMethod.getAdvisors()) {
                if (advice.isReweavable()) {
                    return true;
                }
            }
        }
        return false;
    }

    @Override
    public boolean equals(@Nullable Object obj) {
        if (obj instanceof AnalyzedClass) {
            AnalyzedClass that = (AnalyzedClass) obj;
            return Objects.equal(modifiers, that.modifiers)
                    && Objects.equal(name, that.name)
                    && Objects.equal(superName, that.superName)
                    && Objects.equal(interfaceNames, that.interfaceNames)
                    && Objects.equal(analyzedMethods, that.analyzedMethods);
        }
        return false;
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(modifiers, name, superName, interfaceNames, analyzedMethods);
    }

    @Override
    public String toString() {
        return Objects.toStringHelper(this)
                .add("modifiers", modifiers)
                .add("name", name)
                .add("superName", superName)
                .add("interfaceNames", interfaceNames)
                .add("methods", analyzedMethods)
                .toString();
    }

    static Builder builder(int modifiers, String name, @Nullable String superName,
            ImmutableList<String> interfaceNames) {
        return new Builder(modifiers, name, superName, interfaceNames);
    }

    @NotThreadSafe
    static class Builder {

        private final int modifiers;
        private final String name;
        @Nullable
        private final String superName;
        private final List<String> interfaceNames;
        private final List<AnalyzedMethod> methods = Lists.newArrayList();
        private final List<MixinType> mixinTypes = Lists.newArrayList();

        private Builder(int modifiers, String name, @Nullable String superName,
                ImmutableList<String> interfaceNames) {
            this.modifiers = modifiers;
            this.name = name;
            this.superName = superName;
            this.interfaceNames = interfaceNames;
        }

        AnalyzedMethod addAnalyzedMethod(int access, String name, String desc,
                @Nullable String signature, List<String> exceptions, List<Advice> advisors) {
            List<Type> parameterTypes = Arrays.asList(Type.getArgumentTypes(desc));
            AnalyzedMethod method = AnalyzedMethod.from(name, parameterTypes,
                    Type.getReturnType(desc),
                    access, signature, exceptions, advisors);
            methods.add(method);
            return method;
        }

        void addMixinType(MixinType mixinType) {
            mixinTypes.add(mixinType);
        }

        void addMixinTypes(List<MixinType> mixinTypes) {
            this.mixinTypes.addAll(mixinTypes);
        }

        AnalyzedClass build() {
            return new AnalyzedClass(modifiers, name, superName, interfaceNames, methods,
                    mixinTypes);
        }
    }
}
