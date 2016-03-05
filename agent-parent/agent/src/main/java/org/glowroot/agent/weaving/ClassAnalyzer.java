/*
 * Copyright 2015-2016 the original author or authors.
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
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.regex.Pattern;

import javax.annotation.Nullable;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;
import org.checkerframework.checker.nullness.qual.MonotonicNonNull;
import org.immutables.value.Value;
import org.objectweb.asm.Type;

import org.glowroot.agent.weaving.AnalyzedWorld.ParseContext;
import org.glowroot.agent.weaving.ThinClassVisitor.ThinClass;
import org.glowroot.agent.weaving.ThinClassVisitor.ThinMethod;

import static com.google.common.base.Preconditions.checkNotNull;

class ClassAnalyzer {

    private final ThinClass thinClass;
    private final String className;

    private final ImmutableAnalyzedClass.Builder analyzedClassBuilder;
    private final ImmutableList<AdviceMatcher> adviceMatchers;
    private final ImmutableList<AnalyzedClass> superAnalyzedClasses;
    private final ImmutableList<ShimType> matchedShimTypes;
    private final ImmutableList<MixinType> matchedMixinTypes;

    private final ImmutableSet<String> superClassNames;

    private final boolean shortCircuitBeforeAnalyzeMethods;

    private @MonotonicNonNull Map<String, List<Advice>> methodAdvisors;
    private @MonotonicNonNull List<AnalyzedMethod> methodsThatOnlyNowFulfillAdvice;

    ClassAnalyzer(ThinClass thinClass, List<Advice> advisors, List<ShimType> shimTypes,
            List<MixinType> mixinTypes, @Nullable ClassLoader loader, AnalyzedWorld analyzedWorld,
            @Nullable CodeSource codeSource) {
        this.thinClass = thinClass;
        ImmutableList<String> interfaceNames = ClassNames.fromInternalNames(thinClass.interfaces());
        className = ClassNames.fromInternalName(thinClass.name());
        String superClassName = ClassNames.fromInternalName(thinClass.superName());
        analyzedClassBuilder = ImmutableAnalyzedClass.builder()
                .modifiers(thinClass.access())
                .name(className)
                .superName(superClassName)
                .addAllInterfaceNames(interfaceNames);
        adviceMatchers =
                AdviceMatcher.getAdviceMatchers(className, thinClass.annotations(), advisors);
        if (Modifier.isInterface(thinClass.access())) {
            superAnalyzedClasses = ImmutableList.of();
            matchedShimTypes = getMatchedShimTypes(shimTypes, className,
                    ImmutableList.<AnalyzedClass>of(), ImmutableList.<AnalyzedClass>of());
            analyzedClassBuilder.addAllShimTypes(matchedShimTypes);
            matchedMixinTypes = getMatchedMixinTypes(mixinTypes, className,
                    ImmutableList.<AnalyzedClass>of(), ImmutableList.<AnalyzedClass>of());
            analyzedClassBuilder.addAllMixinTypes(matchedMixinTypes);
            shortCircuitBeforeAnalyzeMethods = adviceMatchers.isEmpty();
        } else {
            ParseContext parseContext = ImmutableParseContext.of(className, codeSource);
            List<AnalyzedClass> superAnalyzedHierarchy =
                    analyzedWorld.getAnalyzedHierarchy(superClassName, loader, parseContext);
            List<AnalyzedClass> interfaceAnalyzedHierarchy = Lists.newArrayList();
            for (String interfaceName : interfaceNames) {
                interfaceAnalyzedHierarchy.addAll(
                        analyzedWorld.getAnalyzedHierarchy(interfaceName, loader, parseContext));
            }
            // it's ok if there are duplicates in the superAnalyzedClasses list (e.g. an interface
            // that appears twice in a type hierarchy), it's rare, dups don't cause an issue for
            // callers, and so it doesn't seem worth the (minor) performance hit to de-dup every
            // time
            List<AnalyzedClass> superAnalyzedClasses = Lists.newArrayList();
            superAnalyzedClasses.addAll(superAnalyzedHierarchy);
            superAnalyzedClasses.addAll(interfaceAnalyzedHierarchy);
            this.superAnalyzedClasses = ImmutableList.copyOf(superAnalyzedClasses);
            matchedShimTypes = getMatchedShimTypes(shimTypes, className, superAnalyzedHierarchy,
                    interfaceAnalyzedHierarchy);
            analyzedClassBuilder.addAllShimTypes(matchedShimTypes);
            matchedMixinTypes = getMatchedMixinTypes(mixinTypes, className, superAnalyzedHierarchy,
                    interfaceAnalyzedHierarchy);
            analyzedClassBuilder.addAllMixinTypes(matchedMixinTypes);
            shortCircuitBeforeAnalyzeMethods =
                    !hasSuperAdvice(superAnalyzedClasses) && matchedShimTypes.isEmpty()
                            && matchedMixinTypes.isEmpty() && adviceMatchers.isEmpty();
        }
        Set<String> superClassNames = Sets.newHashSet();
        superClassNames.add(className);
        for (AnalyzedClass analyzedClass : superAnalyzedClasses) {
            superClassNames.add(analyzedClass.name());
        }
        this.superClassNames = ImmutableSet.copyOf(superClassNames);
    }

    boolean isShortCircuitBeforeAnalyzeMethods() {
        return shortCircuitBeforeAnalyzeMethods;
    }

    void analyzeMethods() {
        methodAdvisors = Maps.newHashMap();
        for (ThinMethod thinMethod : thinClass.methods()) {
            List<Advice> advisors = analyzeMethod(thinMethod);
            removeSuperseded(advisors);
            if (!advisors.isEmpty()) {
                methodAdvisors.put(thinMethod.name() + thinMethod.desc(), advisors);
            }
        }
        AnalyzedClass mostlyAnalyzedClass = analyzedClassBuilder.build();
        methodsThatOnlyNowFulfillAdvice = getMethodsThatOnlyNowFulfillAdvice(mostlyAnalyzedClass);
        analyzedClassBuilder.addAllAnalyzedMethods(methodsThatOnlyNowFulfillAdvice);
    }

    boolean isWeavingRequired() {
        checkNotNull(methodAdvisors);
        checkNotNull(methodsThatOnlyNowFulfillAdvice);
        if (Modifier.isInterface(thinClass.access())) {
            return false;
        }
        return !methodAdvisors.isEmpty() || !methodsThatOnlyNowFulfillAdvice.isEmpty()
                || !matchedShimTypes.isEmpty() || !matchedMixinTypes.isEmpty();
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

    Set<String> getSuperClassNames() {
        return superClassNames;
    }

    Map<String, List<Advice>> getMethodAdvisors() {
        return checkNotNull(methodAdvisors);
    }

    AnalyzedClass getAnalyzedClass() {
        return analyzedClassBuilder.build();
    }

    List<AnalyzedMethod> getMethodsThatOnlyNowFulfillAdvice() {
        return checkNotNull(methodsThatOnlyNowFulfillAdvice);
    }

    private List<Advice> analyzeMethod(ThinMethod thinMethod) {
        List<Type> parameterTypes = Arrays.asList(Type.getArgumentTypes(thinMethod.desc()));
        Type returnType = Type.getReturnType(thinMethod.desc());
        List<String> methodAnnotations = thinMethod.annotations();
        List<Advice> matchingAdvisors = getMatchingAdvisors(thinMethod.name(), methodAnnotations,
                parameterTypes, returnType, thinMethod.access());
        if (matchingAdvisors.isEmpty()) {
            return ImmutableList.of();
        }
        ImmutableAnalyzedMethod.Builder builder = ImmutableAnalyzedMethod.builder();
        builder.name(thinMethod.name());
        for (Type parameterType : parameterTypes) {
            builder.addParameterTypes(parameterType.getClassName());
        }
        builder.returnType(returnType.getClassName())
                .modifiers(thinMethod.access())
                .signature(thinMethod.signature());
        for (String exception : thinMethod.exceptions()) {
            builder.addExceptions(ClassNames.fromInternalName(exception));
        }
        List<Advice> declaredOnlyMatchingAdvisors = Lists.newArrayList();
        for (Iterator<Advice> i = matchingAdvisors.iterator(); i.hasNext();) {
            Advice advice = i.next();
            if (advice.pointcutClassName().equals(advice.pointcutMethodDeclaringClassName())) {
                continue;
            } else if (!isTargetClassNameMatch(advice, superClassNames)) {
                declaredOnlyMatchingAdvisors.add(advice);
                i.remove();
            }
        }
        builder.addAllAdvisors(matchingAdvisors);
        builder.addAllDeclaredOnlyAdvisors(declaredOnlyMatchingAdvisors);
        analyzedClassBuilder.addAnalyzedMethods(builder.build());
        return matchingAdvisors;
    }

    private void removeSuperseded(List<Advice> advisors) {
        Set<String> superseded = Sets.newHashSet();
        for (Advice advice : advisors) {
            String supersedes = advice.pointcut().supersedes();
            if (!supersedes.isEmpty()) {
                superseded.add(supersedes);
            }
        }
        Iterator<Advice> i = advisors.iterator();
        while (i.hasNext()) {
            String timerName = i.next().pointcut().timerName();
            if (superseded.contains(timerName)) {
                i.remove();
            }
        }
    }

    // returns mutable list if non-empty
    private List<Advice> getMatchingAdvisors(String methodName, List<String> methodAnnotations,
            List<Type> parameterTypes, Type returnType, int modifiers) {
        Set<Advice> matchingAdvisors = Sets.newHashSet();
        for (AdviceMatcher adviceMatcher : adviceMatchers) {
            if (adviceMatcher.isMethodLevelMatch(methodName, methodAnnotations, parameterTypes,
                    returnType, modifiers)) {
                matchingAdvisors.add(adviceMatcher.advice());
            }
        }
        // look at super types
        for (AnalyzedClass superAnalyzedClass : superAnalyzedClasses) {
            for (AnalyzedMethod analyzedMethod : superAnalyzedClass.analyzedMethods()) {
                if (analyzedMethod.isOverriddenBy(methodName, parameterTypes)) {
                    matchingAdvisors.addAll(analyzedMethod.advisors());
                    matchingAdvisors.addAll(analyzedMethod.declaredOnlyAdvisors());
                }
            }
        }
        // sort since the order affects advice and timer nesting
        return sortAdvisors(matchingAdvisors);
    }

    private List<AnalyzedMethod> getMethodsThatOnlyNowFulfillAdvice(AnalyzedClass analyzedClass) {
        if (analyzedClass.isInterface() || analyzedClass.isAbstract()) {
            ImmutableMap.of();
        }
        Map<AnalyzedMethodKey, Set<Advice>> matchingAdvisorSets =
                getInheritedInterfaceMethodsWithAdvice();
        for (AnalyzedMethod analyzedMethod : analyzedClass.analyzedMethods()) {
            matchingAdvisorSets.remove(AnalyzedMethodKey.wrap(analyzedMethod));
        }
        removeAdviceAlreadyWovenIntoSuperClass(matchingAdvisorSets);
        List<AnalyzedMethod> methodsThatOnlyNowFulfillAdvice = Lists.newArrayList();
        for (Entry<AnalyzedMethodKey, Set<Advice>> entry : matchingAdvisorSets.entrySet()) {
            AnalyzedMethod inheritedMethod = entry.getKey().analyzedMethod();
            Set<Advice> advisors = entry.getValue();
            if (!advisors.isEmpty()) {
                methodsThatOnlyNowFulfillAdvice.add(ImmutableAnalyzedMethod.builder()
                        .copyFrom(inheritedMethod)
                        .advisors(advisors)
                        .declaredOnlyAdvisors(ImmutableList.<Advice>of())
                        .build());
            }
        }
        return methodsThatOnlyNowFulfillAdvice;
    }

    private Map<AnalyzedMethodKey, Set<Advice>> getInheritedInterfaceMethodsWithAdvice() {
        Map<AnalyzedMethodKey, Set<Advice>> matchingAdvisorSets = Maps.newHashMap();
        for (AnalyzedClass superAnalyzedClass : superAnalyzedClasses) {
            for (AnalyzedMethod superAnalyzedMethod : superAnalyzedClass.analyzedMethods()) {
                AnalyzedMethodKey key = AnalyzedMethodKey.wrap(superAnalyzedMethod);
                Set<Advice> matchingAdvisorSet = matchingAdvisorSets.get(key);
                if (matchingAdvisorSet == null) {
                    matchingAdvisorSet = Sets.newHashSet();
                    matchingAdvisorSets.put(key, matchingAdvisorSet);
                }
                if (superAnalyzedClass.isInterface()) {
                    addToMatchingAdvisorsIfTargetClassNameMatch(matchingAdvisorSet,
                            superAnalyzedMethod.advisors(), superClassNames);
                } else {
                    addToMatchingAdvisorsIfTargetClassNameMatch(matchingAdvisorSet,
                            superAnalyzedMethod.declaredOnlyAdvisors(), superClassNames);
                }
            }
        }
        return matchingAdvisorSets;
    }

    private void removeAdviceAlreadyWovenIntoSuperClass(
            Map<AnalyzedMethodKey, Set<Advice>> matchingAdvisorSets) {
        for (AnalyzedClass superAnalyzedClass : superAnalyzedClasses) {
            if (superAnalyzedClass.isInterface()) {
                continue;
            }
            for (AnalyzedMethod superAnalyzedMethod : superAnalyzedClass.analyzedMethods()) {
                Set<Advice> matchingAdvisorSet =
                        matchingAdvisorSets.get(AnalyzedMethodKey.wrap(superAnalyzedMethod));
                if (matchingAdvisorSet == null) {
                    continue;
                }
                matchingAdvisorSet.removeAll(superAnalyzedMethod.advisors());
            }
        }
    }

    private static void addToMatchingAdvisorsIfTargetClassNameMatch(Set<Advice> matchingAdvisors,
            List<Advice> advisors, Set<String> superClassNames) {
        for (Advice advice : advisors) {
            if (isTargetClassNameMatch(advice, superClassNames)) {
                matchingAdvisors.add(advice);
            }
        }
    }

    private static ImmutableList<ShimType> getMatchedShimTypes(List<ShimType> shimTypes,
            String className, Iterable<AnalyzedClass> superAnalyzedClasses,
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

    private static ImmutableList<MixinType> getMatchedMixinTypes(List<MixinType> mixinTypes,
            String className, Iterable<AnalyzedClass> superAnalyzedClasses,
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

    private static boolean hasSuperAdvice(List<AnalyzedClass> superAnalyzedClasses) {
        for (AnalyzedClass superAnalyzedClass : superAnalyzedClasses) {
            if (!superAnalyzedClass.analyzedMethods().isEmpty()) {
                return true;
            }
        }
        return false;
    }

    private static boolean isTargetClassNameMatch(Advice advice, Set<String> superClassNames) {
        Pattern classNamePattern = advice.pointcutClassNamePattern();
        if (classNamePattern == null) {
            String className = advice.pointcutClassName();
            return className.isEmpty() || superClassNames.contains(className);
        }
        for (String superClassName : superClassNames) {
            if (classNamePattern.matcher(superClassName).matches()) {
                return true;
            }
        }
        return false;
    }

    // returns mutable list if non-empty
    private static List<Advice> sortAdvisors(Set<Advice> matchingAdvisors) {
        switch (matchingAdvisors.size()) {
            case 0:
                return ImmutableList.of();
            case 1:
                return Lists.newArrayList(matchingAdvisors);
            default:
                return Advice.ordering.sortedCopy(matchingAdvisors);
        }
    }

    // AnalyzedMethod equivalence defined only in terms of method name and parameter types
    // so that overridden methods will be equivalent
    @Value.Immutable
    abstract static class AnalyzedMethodKey {

        abstract String name();
        abstract ImmutableList<String> parameterTypes();
        @Value.Auxiliary
        abstract AnalyzedMethod analyzedMethod();

        private static AnalyzedMethodKey wrap(AnalyzedMethod analyzedMethod) {
            return ImmutableAnalyzedMethodKey.builder()
                    .name(analyzedMethod.name())
                    .addAllParameterTypes(analyzedMethod.parameterTypes())
                    .analyzedMethod(analyzedMethod)
                    .build();
        }
    }
}
