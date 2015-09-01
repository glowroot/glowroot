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
package org.glowroot.agent.weaving;

import java.lang.reflect.Modifier;
import java.security.CodeSource;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

import javax.annotation.Nullable;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;
import com.google.common.collect.Sets;
import org.checkerframework.checker.nullness.qual.MonotonicNonNull;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Type;

import org.glowroot.agent.weaving.AnalyzedWorld.ParseContext;

import static com.google.common.base.Preconditions.checkNotNull;
import static org.objectweb.asm.Opcodes.ASM5;

class AnalyzingClassVisitor extends ClassVisitor {

    private final ImmutableList<ShimType> shimTypes;
    private final ImmutableList<MixinType> mixinTypes;
    private final ImmutableList<Advice> advisors;
    private final @Nullable ClassLoader loader;
    private final AnalyzedWorld analyzedWorld;
    private final @Nullable CodeSource codeSource;

    private ImmutableList<AdviceMatcher> adviceMatchers = ImmutableList.of();
    private ImmutableList<ShimType> matchedShimTypes = ImmutableList.of();
    private ImmutableList<MixinType> matchedMixinTypes = ImmutableList.of();

    private List<AnalyzedClass> superAnalyzedClasses = ImmutableList.of();

    private ImmutableAnalyzedClass./*@MonotonicNonNull*/Builder analyzedClassBuilder;

    private @MonotonicNonNull AnalyzedClass analyzedClass;

    private @MonotonicNonNull String className;
    private @MonotonicNonNull Set<String> superClassNames;

    public AnalyzingClassVisitor(List<Advice> advisors, List<ShimType> shimTypes,
            List<MixinType> mixinTypes, @Nullable ClassLoader loader, AnalyzedWorld analyzedWorld,
            @Nullable CodeSource codeSource) {
        super(ASM5);
        this.shimTypes = ImmutableList.copyOf(shimTypes);
        this.mixinTypes = ImmutableList.copyOf(mixinTypes);
        this.advisors = ImmutableList.copyOf(advisors);
        this.loader = loader;
        this.analyzedWorld = analyzedWorld;
        this.codeSource = codeSource;
    }

    @Override
    public void visit(int version, int access, String internalName, @Nullable String signature,
            @Nullable String superInternalName,
            String /*@Nullable*/[] interfaceInternalNamesNullable) {

        AnalyzedClass nonInterestingAnalyzedClass =
                visitAndSometimesReturnNonInterestingAnalyzedClass(access, internalName,
                        superInternalName, interfaceInternalNamesNullable);
        if (nonInterestingAnalyzedClass != null) {
            analyzedClass = nonInterestingAnalyzedClass;
            throw ShortCircuitException.INSTANCE;
        }
    }

    public @Nullable AnalyzedClass visitAndSometimesReturnNonInterestingAnalyzedClass(int access,
            String internalName, @Nullable String superInternalName,
            String /*@Nullable*/[] interfaceInternalNamesNullable) {

        ImmutableList<String> interfaceNames =
                ClassNames.fromInternalNames(interfaceInternalNamesNullable);
        className = ClassNames.fromInternalName(internalName);
        String superClassName = ClassNames.fromInternalName(superInternalName);
        analyzedClassBuilder = ImmutableAnalyzedClass.builder()
                .modifiers(access)
                .name(className)
                .superName(superClassName)
                .addAllInterfaceNames(interfaceNames);
        adviceMatchers = AdviceMatcher.getAdviceMatchers(className, advisors);
        if (Modifier.isInterface(access)) {
            superAnalyzedClasses = ImmutableList.of();
            matchedShimTypes = getMatchedShimTypes(className, ImmutableList.<AnalyzedClass>of(),
                    ImmutableList.<AnalyzedClass>of());
            analyzedClassBuilder.addAllShimTypes(matchedShimTypes);
            matchedMixinTypes = getMatchedMixinTypes(className, ImmutableList.<AnalyzedClass>of(),
                    ImmutableList.<AnalyzedClass>of());
            analyzedClassBuilder.addAllMixinTypes(matchedMixinTypes);
            if (adviceMatchers.isEmpty()) {
                return analyzedClassBuilder.build();
            } else {
                return null;
            }
        }
        ParseContext parseContext = ImmutableParseContext.of(className, codeSource);
        List<AnalyzedClass> superAnalyzedHierarchy =
                analyzedWorld.getAnalyzedHierarchy(superClassName, loader, parseContext);
        List<AnalyzedClass> interfaceAnalyzedHierarchy =
                getAnalyzedHierarchy(interfaceNames, parseContext);
        // it's ok if there are duplicates in the superAnalyzedClasses list (e.g. an interface that
        // appears twice in a type hierarchy), it's rare, dups don't cause an issue for callers, and
        // so it doesn't seem worth the (minor) performance hit to de-dup every time
        superAnalyzedClasses = Lists.newArrayList();
        superAnalyzedClasses.addAll(superAnalyzedHierarchy);
        superAnalyzedClasses.addAll(interfaceAnalyzedHierarchy);
        matchedShimTypes =
                getMatchedShimTypes(className, superAnalyzedHierarchy, interfaceAnalyzedHierarchy);
        analyzedClassBuilder.addAllShimTypes(matchedShimTypes);
        matchedMixinTypes =
                getMatchedMixinTypes(className, superAnalyzedHierarchy, interfaceAnalyzedHierarchy);
        analyzedClassBuilder.addAllMixinTypes(matchedMixinTypes);
        if (isNonInteresting()) {
            return analyzedClassBuilder.build();
        } else {
            return null;
        }
    }

    @Override
    public @Nullable MethodVisitor visitMethod(int access, String name, String desc,
            @Nullable String signature, String /*@Nullable*/[] exceptions) {
        visitMethodAndReturnAdvisors(access, name, desc, signature, exceptions);
        return null;
    }

    @Override
    public void visitEnd() {
        checkNotNull(analyzedClassBuilder, "Call to visit() is required");
        analyzedClass = analyzedClassBuilder.build();
    }

    List<Advice> visitMethodAndReturnAdvisors(int access, String name, String desc,
            @Nullable String signature, String /*@Nullable*/[] exceptions) {
        List<Type> parameterTypes = Arrays.asList(Type.getArgumentTypes(desc));
        Type returnType = Type.getReturnType(desc);
        List<Advice> matchingAdvisors =
                getMatchingAdvisors(name, parameterTypes, returnType, access);
        if (!matchingAdvisors.isEmpty()) {
            checkNotNull(analyzedClassBuilder, "Call to visit() is required");
            ImmutableAnalyzedMethod.Builder builder = ImmutableAnalyzedMethod.builder();
            builder.name(name);
            for (Type parameterType : parameterTypes) {
                builder.addParameterTypes(parameterType.getClassName());
            }
            builder.returnType(returnType.getClassName())
                    .modifiers(access)
                    .signature(signature);
            if (exceptions != null) {
                for (String exception : exceptions) {
                    builder.addExceptions(ClassNames.fromInternalName(exception));
                }
            }
            builder.addAllAdvisors(matchingAdvisors);
            analyzedClassBuilder.addAnalyzedMethods(builder.build());
        }
        return matchingAdvisors;
    }

    ImmutableList<ShimType> getMatchedShimTypes() {
        return matchedShimTypes;
    }

    ImmutableList<MixinType> getMatchedMixinTypes() {
        return matchedMixinTypes;
    }

    List<AnalyzedClass> getSuperAnalyzedClasses() {
        return superAnalyzedClasses;
    }

    @Nullable
    AnalyzedClass getAnalyzedClass() {
        return analyzedClass;
    }

    private List<AnalyzedClass> getAnalyzedHierarchy(ImmutableList<String> classNames,
            ParseContext parseContext) {
        List<AnalyzedClass> analyzedHierarchy = Lists.newArrayList();
        for (String className : classNames) {
            analyzedHierarchy
                    .addAll(analyzedWorld.getAnalyzedHierarchy(className, loader, parseContext));
        }
        return analyzedHierarchy;
    }

    private ImmutableList<ShimType> getMatchedShimTypes(String className,
            Iterable<AnalyzedClass> superAnalyzedClasses,
            List<AnalyzedClass> newInterfaceAnalyzedClasses) {
        Set<ShimType> matchedShimTypes = Sets.newHashSet();
        for (ShimType shimType : shimTypes) {
            if (shimType.target().equals(className)) {
                matchedShimTypes.add(shimType);
            }
        }
        for (AnalyzedClass newInterfaceAnalyzedClass : newInterfaceAnalyzedClasses) {
            matchedShimTypes.addAll(newInterfaceAnalyzedClass.shimTypes());
        }
        // remove shims that were already implemented in a super class
        for (AnalyzedClass superAnalyzedClass : superAnalyzedClasses) {
            if (!superAnalyzedClass.isInterface()) {
                matchedShimTypes.removeAll(superAnalyzedClass.shimTypes());
            }
        }
        return ImmutableList.copyOf(matchedShimTypes);
    }

    private ImmutableList<MixinType> getMatchedMixinTypes(String className,
            Iterable<AnalyzedClass> superAnalyzedClasses,
            List<AnalyzedClass> newInterfaceAnalyzedClasses) {
        Set<MixinType> matchedMixinTypes = Sets.newHashSet();
        for (MixinType mixinType : mixinTypes) {
            // currently only exact matching is supported
            if (mixinType.targets().contains(className)) {
                matchedMixinTypes.add(mixinType);
            }
        }
        for (AnalyzedClass newInterfaceAnalyzedClass : newInterfaceAnalyzedClasses) {
            matchedMixinTypes.addAll(newInterfaceAnalyzedClass.mixinTypes());
        }
        // remove mixins that were already implemented in a super class
        for (AnalyzedClass superAnalyzedClass : superAnalyzedClasses) {
            if (!superAnalyzedClass.isInterface()) {
                matchedMixinTypes.removeAll(superAnalyzedClass.mixinTypes());
            }
        }
        return ImmutableList.copyOf(matchedMixinTypes);
    }

    private boolean isNonInteresting() {
        return !hasSuperAdvice() && adviceMatchers.isEmpty() && matchedShimTypes.isEmpty()
                && matchedMixinTypes.isEmpty();
    }

    private boolean hasSuperAdvice() {
        for (AnalyzedClass superAnalyzedClass : superAnalyzedClasses) {
            if (!superAnalyzedClass.analyzedMethods().isEmpty()) {
                return true;
            }
        }
        return false;
    }

    private List<Advice> getMatchingAdvisors(String methodName, List<Type> parameterTypes,
            Type returnType, int modifiers) {
        Set<Advice> matchingAdvisors = Sets.newHashSet();
        for (AdviceMatcher adviceMatcher : adviceMatchers) {
            if (adviceMatcher.isMethodLevelMatch(methodName, parameterTypes, returnType,
                    modifiers)) {
                matchingAdvisors.add(adviceMatcher.advice());
            }
        }
        // look at super types
        checkNotNull(superAnalyzedClasses, "Call to visit() is required");
        for (AnalyzedClass superAnalyzedClass : superAnalyzedClasses) {
            for (AnalyzedMethod analyzedMethod : superAnalyzedClass.analyzedMethods()) {
                if (analyzedMethod.isOverriddenBy(methodName, parameterTypes)) {
                    addToMatchingAdvisors(matchingAdvisors, analyzedMethod.advisors());
                }
            }
        }
        // sort since the order affects advice and timer nesting
        return sortAdvisors(matchingAdvisors);
    }

    void addToMatchingAdvisors(Set<Advice> matchingAdvisors, List<Advice> advisors) {
        for (Advice advice : advisors) {
            if (advice.pointcut().declaringClassName().equals("")) {
                matchingAdvisors.add(advice);
            } else {
                if (isTargetClassNameMatch(advice)) {
                    matchingAdvisors.add(advice);
                }
            }
        }
    }

    private boolean isTargetClassNameMatch(Advice advice) {
        if (superClassNames == null) {
            superClassNames = buildSuperClassNames();
        }
        Pattern targetClassNamePattern = advice.pointcutTargetClassNamePattern();
        if (targetClassNamePattern == null) {
            return superClassNames.contains(advice.pointcutTargetClassName());
        } else {
            for (String superClassName : superClassNames) {
                if (targetClassNamePattern.matcher(superClassName).matches()) {
                    return true;
                }
            }
            return false;
        }
    }

    private Set<String> buildSuperClassNames() {
        checkNotNull(className, "Call to visit() is required");
        Set<String> superClassNames = Sets.newHashSet();
        superClassNames.add(className);
        for (AnalyzedClass analyzedClass : superAnalyzedClasses) {
            superClassNames.add(analyzedClass.name());
        }
        return superClassNames;
    }

    private List<Advice> sortAdvisors(Set<Advice> matchingAdvisors) {
        switch (matchingAdvisors.size()) {
            case 0:
                return ImmutableList.of();
            case 1:
                return ImmutableList.copyOf(matchingAdvisors);
            default:
                return Advice.ordering.immutableSortedCopy(matchingAdvisors);
        }
    }

    @SuppressWarnings("serial")
    static class ShortCircuitException extends RuntimeException {
        static final ShortCircuitException INSTANCE = new ShortCircuitException();
        private ShortCircuitException() {}
    }
}
