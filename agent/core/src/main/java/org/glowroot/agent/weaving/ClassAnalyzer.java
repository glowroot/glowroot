/*
 * Copyright 2015-2019 the original author or authors.
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

import java.io.IOException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.net.URL;
import java.security.CodeSource;
import java.util.Arrays;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import java.util.ListIterator;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import com.google.common.base.Joiner;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;
import com.google.common.io.Resources;
import org.checkerframework.checker.nullness.qual.MonotonicNonNull;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.checkerframework.checker.nullness.qual.RequiresNonNull;
import org.immutables.value.Value;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Type;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.glowroot.agent.weaving.AnalyzedWorld.ParseContext;
import org.glowroot.agent.weaving.ClassLoaders.LazyDefinedClass;
import org.glowroot.agent.weaving.ThinClassVisitor.ThinClass;
import org.glowroot.agent.weaving.ThinClassVisitor.ThinMethod;
import org.glowroot.common.config.ImmutableInstrumentationConfig;
import org.glowroot.common.config.InstrumentationConfig;
import org.glowroot.wire.api.model.AgentConfigOuterClass.AgentConfig.InstrumentationConfig.CaptureKind;

import static com.google.common.base.Preconditions.checkNotNull;
import static org.glowroot.wire.api.model.AgentConfigOuterClass.AgentConfig.InstrumentationConfig.AlreadyInTransactionBehavior;
import static org.objectweb.asm.Opcodes.ACC_BRIDGE;
import static org.objectweb.asm.Opcodes.ASM7;
import static org.objectweb.asm.Opcodes.INVOKESPECIAL;

class ClassAnalyzer {

    private static final Logger logger = LoggerFactory.getLogger(ClassAnalyzer.class);

    private final ThinClass thinClass;
    private final String className;
    private final boolean intf;
    private final @Nullable ClassLoader loader;

    private final ImmutableAnalyzedClass.Builder analyzedClassBuilder;
    private final ImmutableList<AdviceMatcher> adviceMatchers;
    private final ImmutableList<AnalyzedClass> superAnalyzedClasses;
    private final ImmutableList<ShimType> matchedShimTypes;
    private final MatchedMixinTypes matchedMixinTypes;
    private final boolean hasMainMethod;
    private final boolean isClassLoader;

    private final ImmutableSet<String> superClassNames;

    private final boolean shortCircuitBeforeAnalyzeMethods;

    private final byte[] classBytes;

    private final boolean hackAdvisors;

    private @MonotonicNonNull Map<String, List<Advice>> methodAdvisors;
    private @MonotonicNonNull List<AnalyzedMethod> methodsThatOnlyNowFulfillAdvice;

    // this is used to propagate bridge method advice to its target
    private @MonotonicNonNull Map<ThinMethod, List<Advice>> bridgeTargetAdvisors;

    ClassAnalyzer(ThinClass thinClass, List<Advice> advisors, List<ShimType> shimTypes,
            List<MixinType> mixinTypes, @Nullable ClassLoader loader, AnalyzedWorld analyzedWorld,
            @Nullable CodeSource codeSource, byte[] classBytes,
            @Nullable Class<?> classBeingRedefined, boolean noLongerNeedToWeaveMainMethods) {
        this.thinClass = thinClass;
        this.loader = loader;
        ImmutableList<String> interfaceNames = ClassNames.fromInternalNames(thinClass.interfaces());
        className = ClassNames.fromInternalName(thinClass.name());
        intf = Modifier.isInterface(thinClass.access());
        String superClassName = ClassNames.fromInternalName(thinClass.superName());
        analyzedClassBuilder = ImmutableAnalyzedClass.builder()
                .modifiers(thinClass.access())
                .name(className)
                .superName(superClassName)
                .addAllInterfaceNames(interfaceNames);
        boolean ejbRemote = false;
        boolean ejbStateless = false;
        for (String annotation : thinClass.annotations()) {
            if (annotation.equals("Ljavax/ejb/Remote;")) {
                ejbRemote = true;
            } else if (annotation.equals("Ljavax/ejb/Stateless;")) {
                ejbStateless = true;
            }
        }

        ParseContext parseContext = ImmutableParseContext.of(className, codeSource);
        List<AnalyzedClass> interfaceAnalyzedHierarchy = Lists.newArrayList();
        // it's ok if there are duplicates in the superAnalyzedClasses list (e.g. an interface
        // that appears twice in a type hierarchy), it's rare, dups don't cause an issue for
        // callers, and so it doesn't seem worth the (minor) performance hit to de-dup every
        // time
        List<AnalyzedClass> superAnalyzedClasses = Lists.newArrayList();
        for (String interfaceName : interfaceNames) {
            interfaceAnalyzedHierarchy.addAll(analyzedWorld.getAnalyzedHierarchy(interfaceName,
                    loader, className, parseContext));
        }
        superAnalyzedClasses.addAll(interfaceAnalyzedHierarchy);

        if (intf) {
            matchedShimTypes = getMatchedShimTypes(shimTypes, className,
                    ImmutableList.<AnalyzedClass>of(), ImmutableList.<AnalyzedClass>of());
            matchedMixinTypes = getMatchedMixinTypes(mixinTypes, className, classBeingRedefined,
                    ImmutableList.<AnalyzedClass>of(), ImmutableList.<AnalyzedClass>of());
            hasMainMethod = false;
            isClassLoader = false;
        } else {
            List<AnalyzedClass> superAnalyzedHierarchy = analyzedWorld
                    .getAnalyzedHierarchy(superClassName, loader, className, parseContext);
            superAnalyzedClasses.addAll(superAnalyzedHierarchy);
            matchedShimTypes = getMatchedShimTypes(shimTypes, className, superAnalyzedHierarchy,
                    interfaceAnalyzedHierarchy);
            matchedMixinTypes = getMatchedMixinTypes(mixinTypes, className, classBeingRedefined,
                    superAnalyzedHierarchy, interfaceAnalyzedHierarchy);
            if (noLongerNeedToWeaveMainMethods) {
                hasMainMethod = false;
            } else {
                hasMainMethod = hasMainOrPossibleProcrunStartMethod(thinClass.nonBridgeMethods())
                        || className.equals("org.apache.commons.daemon.support.DaemonLoader");
            }
            if (className.startsWith("org.glowroot.agent.tests.")) {
                // e.g. see AnalyzedClassPlanBeeWithMoreBadPreloadCacheIT
                isClassLoader = false;
            } else {
                boolean isClassLoader = false;
                for (AnalyzedClass superAnalyzedClass : superAnalyzedHierarchy) {
                    if (superAnalyzedClass.name().equals(ClassLoader.class.getName())) {
                        isClassLoader = true;
                        break;
                    }
                }
                this.isClassLoader = isClassLoader;
            }
        }
        analyzedClassBuilder.addAllShimTypes(matchedShimTypes);
        analyzedClassBuilder.addAllMixinTypes(matchedMixinTypes.reweavable());
        analyzedClassBuilder.addAllNonReweavableMixinTypes(matchedMixinTypes.nonReweavable());

        if ((ejbRemote || ejbStateless) && !intf) {
            Set<String> ejbRemoteInterfaces =
                    getEjbRemoteInterfaces(thinClass, superAnalyzedClasses);
            if (ejbRemoteInterfaces.isEmpty()) {
                this.superAnalyzedClasses = ImmutableList.copyOf(superAnalyzedClasses);
                analyzedClassBuilder.ejbRemote(ejbRemote);
            } else if (loader == null) {
                logger.warn("instrumenting @javax.ejb.Remote not currently supported in bootstrap"
                        + " class loader: {}", className);
                this.superAnalyzedClasses = ImmutableList.copyOf(superAnalyzedClasses);
                analyzedClassBuilder.ejbRemote(false);
            } else {
                List<AnalyzedClass> ejbHackedSuperAnalyzedClasses =
                        hack(thinClass, loader, superAnalyzedClasses, ejbRemoteInterfaces);
                this.superAnalyzedClasses = ImmutableList.copyOf(ejbHackedSuperAnalyzedClasses);
                analyzedClassBuilder.ejbRemote(true);
            }
        } else {
            this.superAnalyzedClasses = ImmutableList.copyOf(superAnalyzedClasses);
            analyzedClassBuilder.ejbRemote(ejbRemote);
        }
        Set<String> superClassNames = Sets.newHashSet();
        superClassNames.add(className);
        for (AnalyzedClass analyzedClass : superAnalyzedClasses) {
            superClassNames.add(analyzedClass.name());
        }
        this.superClassNames = ImmutableSet.copyOf(superClassNames);
        adviceMatchers = AdviceMatcher.getAdviceMatchers(className, thinClass.annotations(),
                superClassNames, advisors);
        if (intf) {
            shortCircuitBeforeAnalyzeMethods = false;
        } else {
            shortCircuitBeforeAnalyzeMethods =
                    !hasSuperAdvice(this.superAnalyzedClasses) && matchedShimTypes.isEmpty()
                            && matchedMixinTypes.reweavable().isEmpty() && adviceMatchers.isEmpty();
        }
        this.classBytes = classBytes;
        this.hackAdvisors = loader != null;
    }

    void analyzeMethods() throws ClassNotFoundException, IOException {
        methodAdvisors = Maps.newHashMap();
        bridgeTargetAdvisors = Maps.newHashMap();
        for (ThinMethod bridgeMethod : thinClass.bridgeMethods()) {
            List<Advice> advisors = analyzeMethod(bridgeMethod);
            if (!advisors.isEmpty()) {
                // don't add advisors to bridge method
                // instead propagate bridge method advice to its target
                ThinMethod targetMethod = getTargetMethod(bridgeMethod);
                if (targetMethod != null) {
                    bridgeTargetAdvisors.put(targetMethod, advisors);
                }
            }
        }
        for (ThinMethod nonBridgeMethod : thinClass.nonBridgeMethods()) {
            List<Advice> advisors = analyzeMethod(nonBridgeMethod);
            // convert here
            if (hackAdvisors) {
                for (ListIterator<Advice> i = advisors.listIterator(); i.hasNext();) {
                    Advice advice = i.next();
                    Advice nonBootstrapLoaderAdvice = advice.nonBootstrapLoaderAdvice();
                    if (nonBootstrapLoaderAdvice != null) {
                        i.set(nonBootstrapLoaderAdvice);
                    }
                }
            }
            if (!advisors.isEmpty()) {
                methodAdvisors.put(nonBridgeMethod.name() + nonBridgeMethod.descriptor(), advisors);
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
            // FIXME only need to return true if any default methods have advice
            return !methodAdvisors.isEmpty() || !matchedMixinTypes.reweavable().isEmpty();
        }
        return !methodAdvisors.isEmpty() || !methodsThatOnlyNowFulfillAdvice.isEmpty()
                || !matchedShimTypes.isEmpty() || !matchedMixinTypes.reweavable().isEmpty()
                || hasMainMethod || isClassLoader;
    }

    ImmutableList<ShimType> getMatchedShimTypes() {
        return matchedShimTypes;
    }

    List<MixinType> getMatchedReweavableMixinTypes() {
        return matchedMixinTypes.reweavable();
    }

    Map<String, List<Advice>> getMethodAdvisors() {
        return checkNotNull(methodAdvisors);
    }

    AnalyzedClass getAnalyzedClass() {
        return analyzedClassBuilder.build();
    }

    boolean isClassLoader() {
        return isClassLoader;
    }

    List<AnalyzedMethod> getMethodsThatOnlyNowFulfillAdvice() {
        return checkNotNull(methodsThatOnlyNowFulfillAdvice);
    }

    @RequiresNonNull("bridgeTargetAdvisors")
    private List<Advice> analyzeMethod(ThinMethod thinMethod) {
        if (Modifier.isFinal(thinMethod.access()) && Modifier.isPublic(thinMethod.access())) {
            ImmutablePublicFinalMethod.Builder builder = ImmutablePublicFinalMethod.builder()
                    .name(thinMethod.name());
            List<Type> parameterTypes =
                    Arrays.asList(Type.getArgumentTypes(thinMethod.descriptor()));
            for (Type parameterType : parameterTypes) {
                builder.addParameterTypes(parameterType.getClassName());
            }
            analyzedClassBuilder.addPublicFinalMethods(builder.build());
        }
        if (shortCircuitBeforeAnalyzeMethods) {
            return ImmutableList.of();
        }
        List<Type> parameterTypes = Arrays.asList(Type.getArgumentTypes(thinMethod.descriptor()));
        Type returnType = Type.getReturnType(thinMethod.descriptor());
        List<String> methodAnnotations = thinMethod.annotations();
        List<Advice> matchingAdvisors = getMatchingAdvisors(thinMethod, methodAnnotations,
                parameterTypes, returnType);
        boolean intfMethod = intf && !Modifier.isStatic(thinMethod.access());
        if (matchingAdvisors.isEmpty() && !intfMethod) {
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
        List<Advice> subTypeRestrictedAdvisors = Lists.newArrayList();
        for (Iterator<Advice> i = matchingAdvisors.iterator(); i.hasNext();) {
            Advice advice = i.next();
            if (!isSubTypeRestrictionMatch(advice, superClassNames)) {
                subTypeRestrictedAdvisors.add(advice);
                i.remove();
            }
        }
        builder.addAllAdvisors(matchingAdvisors)
                .addAllSubTypeRestrictedAdvisors(subTypeRestrictedAdvisors);
        analyzedClassBuilder.addAnalyzedMethods(builder.build());
        return matchingAdvisors;
    }

    // returns mutable list if non-empty so items can be removed
    @RequiresNonNull("bridgeTargetAdvisors")
    private List<Advice> getMatchingAdvisors(ThinMethod thinMethod, List<String> methodAnnotations,
            List<Type> parameterTypes, Type returnType) {
        Set<Advice> matchingAdvisors = Sets.newHashSet();
        for (AdviceMatcher adviceMatcher : adviceMatchers) {
            if (adviceMatcher.isMethodLevelMatch(thinMethod.name(), methodAnnotations,
                    parameterTypes, returnType, thinMethod.access())) {
                matchingAdvisors.add(adviceMatcher.advice());
            }
        }
        if (!thinMethod.name().equals("<init>")) {
            // look at super types
            for (AnalyzedClass superAnalyzedClass : superAnalyzedClasses) {
                for (AnalyzedMethod analyzedMethod : superAnalyzedClass.analyzedMethods()) {
                    if (analyzedMethod.isOverriddenBy(thinMethod.name(), parameterTypes)) {
                        matchingAdvisors.addAll(analyzedMethod.advisors());
                        for (Advice subTypeRestrictedAdvice : analyzedMethod
                                .subTypeRestrictedAdvisors()) {
                            if (isSubTypeRestrictionMatch(subTypeRestrictedAdvice,
                                    superClassNames)) {
                                matchingAdvisors.add(subTypeRestrictedAdvice);
                            }
                        }
                    }
                }
            }
        }
        List<Advice> extraAdvisors = bridgeTargetAdvisors.get(thinMethod);
        if (extraAdvisors != null) {
            matchingAdvisors.addAll(extraAdvisors);
        }
        // sort since the order affects advice nesting
        return sortAdvisors(matchingAdvisors);
    }

    private @Nullable ThinMethod getTargetMethod(ThinMethod bridgeMethod) {
        List<ThinMethod> possibleTargetMethods = getPossibleTargetMethods(bridgeMethod);
        if (possibleTargetMethods.isEmpty()) {
            // probably a visibility bridge for public method in package-private super class
            return null;
        }
        BridgeMethodClassVisitor bmcv = new BridgeMethodClassVisitor();
        new ClassReader(classBytes).accept(bmcv, ClassReader.SKIP_FRAMES);
        Map<String, String> bridgeMethodMap = bmcv.getBridgeTargetMethods();
        String targetMethod = bridgeMethodMap.get(bridgeMethod.name() + bridgeMethod.descriptor());
        if (targetMethod == null) {
            // probably a visibility bridge for public method in package-private super class
            return null;
        }
        for (ThinMethod possibleTargetMethod : possibleTargetMethods) {
            if (targetMethod
                    .equals(possibleTargetMethod.name() + possibleTargetMethod.descriptor())) {
                return possibleTargetMethod;
            }
        }
        logger.warn("could not find match for bridge method in {}: {}", className, bridgeMethod);
        return null;
    }

    private List<ThinMethod> getPossibleTargetMethods(ThinMethod bridgeMethod) {
        List<ThinMethod> possibleTargetMethods = Lists.newArrayList();
        for (ThinMethod possibleTargetMethod : thinClass.nonBridgeMethods()) {
            if (!possibleTargetMethod.name().equals(bridgeMethod.name())) {
                continue;
            }
            Type[] bridgeMethodParamTypes = Type.getArgumentTypes(bridgeMethod.descriptor());
            Type[] possibleTargetMethodParamTypes =
                    Type.getArgumentTypes(possibleTargetMethod.descriptor());
            if (possibleTargetMethodParamTypes.length != bridgeMethodParamTypes.length) {
                continue;
            }
            possibleTargetMethods.add(possibleTargetMethod);
        }
        return possibleTargetMethods;
    }

    private List<AnalyzedMethod> getMethodsThatOnlyNowFulfillAdvice(AnalyzedClass analyzedClass)
            throws ClassNotFoundException, IOException {
        if (analyzedClass.isAbstract()) {
            return ImmutableList.of();
        }
        Map<AnalyzedMethodKey, Set<Advice>> matchingAdvisorSets =
                getInheritedInterfaceMethodsWithAdvice();
        if (matchingAdvisorSets.isEmpty()) {
            return ImmutableList.of();
        }
        for (AnalyzedMethod analyzedMethod : analyzedClass.analyzedMethods()) {
            matchingAdvisorSets.remove(AnalyzedMethodKey.wrap(analyzedMethod));
        }
        if (matchingAdvisorSets.isEmpty()) {
            return ImmutableList.of();
        }
        removeAdviceAlreadyWovenIntoSuperClass(matchingAdvisorSets);
        if (matchingAdvisorSets.isEmpty()) {
            return ImmutableList.of();
        }
        removeMethodsThatWouldOverridePublicFinalMethodsFromSuperClass(matchingAdvisorSets);
        removeMethodsThatAreNotImplementedInSuperClass(matchingAdvisorSets);
        List<AnalyzedMethod> methodsThatOnlyNowFulfillAdvice = Lists.newArrayList();
        for (Map.Entry<AnalyzedMethodKey, Set<Advice>> entry : matchingAdvisorSets.entrySet()) {
            Set<Advice> advisors = entry.getValue();
            if (!advisors.isEmpty()) {
                AnalyzedMethod inheritedMethod = checkNotNull(entry.getKey().analyzedMethod());
                methodsThatOnlyNowFulfillAdvice.add(ImmutableAnalyzedMethod.builder()
                        .copyFrom(inheritedMethod)
                        .advisors(advisors)
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
                }
                matchingAdvisorSet.addAll(superAnalyzedMethod.advisors());
                for (Advice advice : superAnalyzedMethod.subTypeRestrictedAdvisors()) {
                    if (isSubTypeRestrictionMatch(advice, superClassNames)) {
                        matchingAdvisorSet.add(advice);
                    }
                }
                if (!matchingAdvisorSet.isEmpty()) {
                    matchingAdvisorSets.put(key, matchingAdvisorSet);
                }
            }
        }
        return matchingAdvisorSets;
    }

    private void removeAdviceAlreadyWovenIntoSuperClass(
            Map<AnalyzedMethodKey, Set<Advice>> matchingAdvisorSets) {
        for (AnalyzedClass superAnalyzedClass : superAnalyzedClasses) {
            for (AnalyzedMethod superAnalyzedMethod : superAnalyzedClass.analyzedMethods()) {
                if (Modifier.isAbstract(superAnalyzedMethod.modifiers())) {
                    continue;
                }
                AnalyzedMethodKey key = AnalyzedMethodKey.wrap(superAnalyzedMethod);
                Set<Advice> matchingAdvisorSet = matchingAdvisorSets.get(key);
                if (matchingAdvisorSet == null) {
                    continue;
                }
                matchingAdvisorSet.removeAll(superAnalyzedMethod.advisors());
                if (matchingAdvisorSet.isEmpty()) {
                    matchingAdvisorSets.remove(key);
                }
            }
        }
    }

    private void removeMethodsThatWouldOverridePublicFinalMethodsFromSuperClass(
            Map<AnalyzedMethodKey, Set<Advice>> matchingAdvisorSets) {
        for (AnalyzedClass superAnalyzedClass : superAnalyzedClasses) {
            for (PublicFinalMethod publicFinalMethod : superAnalyzedClass.publicFinalMethods()) {
                ImmutableAnalyzedMethodKey key = ImmutableAnalyzedMethodKey.builder()
                        .name(publicFinalMethod.name())
                        .addAllParameterTypes(publicFinalMethod.parameterTypes())
                        .build();
                Set<Advice> value = matchingAdvisorSets.remove(key);
                // need to check if empty due to removeAdviceAlreadyWovenIntoSuperClass() above
                if (value != null && !value.isEmpty()) {
                    logOverridePublicFinalMethodInfo(superAnalyzedClass, publicFinalMethod);
                }
            }
        }
    }

    private void removeMethodsThatAreNotImplementedInSuperClass(
            Map<AnalyzedMethodKey, Set<Advice>> matchingAdvisorSets)
            throws ClassNotFoundException, IOException {
        Set<AnalyzedMethodKey> superAnalyzedMethodKeys = Sets.newHashSet();
        for (AnalyzedClass superAnalyzedClass : superAnalyzedClasses) {
            superAnalyzedMethodKeys
                    .addAll(getNonAbstractMethods(superAnalyzedClass.name(), loader));
        }
        Iterator<AnalyzedMethodKey> i = matchingAdvisorSets.keySet().iterator();
        while (i.hasNext()) {
            AnalyzedMethodKey analyzedMethodKey = i.next();
            if (!superAnalyzedMethodKeys.contains(analyzedMethodKey)) {
                i.remove();
            }
        }
    }

    private void logOverridePublicFinalMethodInfo(AnalyzedClass superAnalyzedClass,
            PublicFinalMethod publicFinalMethod) {
        String format = "Not able to override and instrument final method {}.{}({}) which is"
                + " being used by a subclass {} to implement one or more instrumented interfaces";
        String arg1 = superAnalyzedClass.name();
        String arg2 = publicFinalMethod.name();
        String arg3 = Joiner.on(", ").join(publicFinalMethod.parameterTypes());
        String arg4 = className;
        if (superAnalyzedClass.name().equals("org.jooq.tools.jdbc.JDBC41ResultSet")) {
            // this is a known case, and is only worth logging at debug level since all that these
            // methods in JDBC41ResultSet do is throw an UnsupportedOperationException
            logger.debug(format, arg1, arg2, arg3, arg4);
        } else {
            logger.info(format, arg1, arg2, arg3, arg4);
        }
    }

    private static ImmutableList<ShimType> getMatchedShimTypes(List<ShimType> shimTypes,
            String className, List<AnalyzedClass> superAnalyzedHierarchy,
            List<AnalyzedClass> interfaceAnalyzedHierarchy) {
        Set<ShimType> matchedShimTypes = Sets.newHashSet();
        for (ShimType shimType : shimTypes) {
            // currently only exact matching is supported
            if (shimType.targets().contains(className)) {
                matchedShimTypes.add(shimType);
            }
        }
        for (AnalyzedClass interfaceAnalyzedClass : interfaceAnalyzedHierarchy) {
            matchedShimTypes.addAll(interfaceAnalyzedClass.shimTypes());
        }
        // remove shims that were already implemented in a super class
        for (AnalyzedClass superAnalyzedClass : superAnalyzedHierarchy) {
            if (!superAnalyzedClass.isInterface()) {
                matchedShimTypes.removeAll(superAnalyzedClass.shimTypes());
            }
        }
        return ImmutableList.copyOf(matchedShimTypes);
    }

    private static MatchedMixinTypes getMatchedMixinTypes(List<MixinType> mixinTypes,
            String className, @Nullable Class<?> classBeingRedefined,
            List<AnalyzedClass> superAnalyzedHierarchy,
            List<AnalyzedClass> interfaceAnalyzedHierarchy) {
        Set<MixinType> matchedMixinTypes = Sets.newHashSet();
        for (MixinType mixinType : mixinTypes) {
            // currently only exact matching is supported
            if (mixinType.targets().contains(className)) {
                matchedMixinTypes.add(mixinType);
            }
        }
        for (AnalyzedClass interfaceAnalyzedClass : interfaceAnalyzedHierarchy) {
            matchedMixinTypes.addAll(interfaceAnalyzedClass.mixinTypes());
        }
        for (AnalyzedClass superAnalyzedClass : superAnalyzedHierarchy) {
            matchedMixinTypes.addAll(superAnalyzedClass.nonReweavableMixinTypes());
        }
        // remove mixins that were already implemented in a super class
        for (AnalyzedClass superAnalyzedClass : superAnalyzedHierarchy) {
            if (!superAnalyzedClass.isInterface()) {
                matchedMixinTypes.removeAll(superAnalyzedClass.mixinTypes());
            }
        }
        List<MixinType> nonReweavableMatchedMixinTypes = Lists.newArrayList();
        if (classBeingRedefined != null) {
            Set<String> interfaceNames = Sets.newHashSet();
            for (Class<?> iface : classBeingRedefined.getInterfaces()) {
                interfaceNames.add(iface.getName());
            }
            for (Iterator<MixinType> i = matchedMixinTypes.iterator(); i.hasNext();) {
                MixinType matchedMixinType = i.next();
                for (Type mixinInterface : matchedMixinType.interfaces()) {
                    if (!interfaceNames.contains(mixinInterface.getClassName())) {
                        // re-weaving would fail with "attempted to change superclass or interfaces"
                        logger.debug("not reweaving {} because cannot add mixin type: {}",
                                ClassNames.fromInternalName(className),
                                mixinInterface.getClassName());
                        nonReweavableMatchedMixinTypes.add(matchedMixinType);
                        i.remove();
                        break;
                    }
                }
            }
        }
        return ImmutableMatchedMixinTypes.builder()
                .addAllReweavable(matchedMixinTypes)
                .addAllNonReweavable(nonReweavableMatchedMixinTypes)
                .build();
    }

    private static boolean hasMainOrPossibleProcrunStartMethod(List<ThinMethod> methods) {
        for (ThinMethod method : methods) {
            // checking for start* methods since those seem to be common for procrun users
            if (Modifier.isPublic(method.access()) && Modifier.isStatic(method.access())
                    && (method.name().equals("main") || method.name().startsWith("start"))
                    && method.descriptor().equals("([Ljava/lang/String;)V")) {
                return true;
            }
        }
        return false;
    }

    private static Set<String> getEjbRemoteInterfaces(ThinClass thinClass,
            List<AnalyzedClass> superAnalyzedClasses) {
        Set<String> ejbRemoteInterfaces = Sets.newHashSet();
        for (Type ejbRemoteInterface : thinClass.ejbRemoteInterfaces()) {
            ejbRemoteInterfaces.add(ejbRemoteInterface.getClassName());
        }
        for (AnalyzedClass superAnalyzedClass : superAnalyzedClasses) {
            if (superAnalyzedClass.isInterface() && superAnalyzedClass.ejbRemote()) {
                ejbRemoteInterfaces.add(superAnalyzedClass.name());
            }
        }
        return ejbRemoteInterfaces;
    }

    private static List<AnalyzedClass> hack(ThinClass thinClass, ClassLoader loader,
            List<AnalyzedClass> superAnalyzedClasses, Set<String> ejbRemoteInterfaces) {
        Map<String, List<String>> superInterfaceNames = Maps.newHashMap();
        for (AnalyzedClass analyzedClass : superAnalyzedClasses) {
            if (analyzedClass.isInterface()) {
                superInterfaceNames.put(analyzedClass.name(), analyzedClass.interfaceNames());
            }
        }
        Map<String, String> interfaceNamesToInstrument = Maps.newHashMap();
        for (String ejbRemoteInterface : ejbRemoteInterfaces) {
            addToInterfaceNamesToInstrument(ejbRemoteInterface, superInterfaceNames,
                    interfaceNamesToInstrument, ejbRemoteInterface);
        }
        List<InstrumentationConfig> instrumentationConfigs = Lists.newArrayList();
        for (Map.Entry<String, String> entry : interfaceNamesToInstrument.entrySet()) {
            String shortClassName = entry.getValue();
            int index = shortClassName.lastIndexOf('.');
            if (index != -1) {
                shortClassName = shortClassName.substring(index + 1);
            }
            index = shortClassName.lastIndexOf('$');
            if (index != -1) {
                shortClassName = shortClassName.substring(index + 1);
            }
            instrumentationConfigs.add(ImmutableInstrumentationConfig.builder()
                    .className(entry.getKey())
                    .subTypeRestriction(ClassNames.fromInternalName(thinClass.name()))
                    .methodName("*")
                    .addMethodParameterTypes("..")
                    .captureKind(CaptureKind.TRANSACTION)
                    .transactionType("Web")
                    .transactionNameTemplate("EJB remote: " + shortClassName + "#{{methodName}}")
                    .traceEntryMessageTemplate(
                            "EJB remote: " + entry.getValue() + ".{{methodName}}()")
                    .timerName("ejb remote")
                    .alreadyInTransactionBehavior(AlreadyInTransactionBehavior.CAPTURE_TRACE_ENTRY)
                    .build());
        }
        ImmutableMap<Advice, LazyDefinedClass> newAdvisors =
                AdviceGenerator.createAdvisors(instrumentationConfigs, null, false, false);
        try {
            ClassLoaders.defineClasses(newAdvisors.values(), loader);
        } catch (Exception e) {
            logger.error(e.getMessage(), e);
        }
        Map<String, Advice> newAdvisorsByRemoteInterface = Maps.newHashMap();
        for (Advice advice : newAdvisors.keySet()) {
            newAdvisorsByRemoteInterface.put(advice.pointcut().className(), advice);
        }

        List<AnalyzedClass> ejbHackedSuperAnalyzedClasses = Lists.newArrayList();
        for (AnalyzedClass superAnalyzedClass : superAnalyzedClasses) {
            Advice advice = newAdvisorsByRemoteInterface.get(superAnalyzedClass.name());
            if (advice == null) {
                ejbHackedSuperAnalyzedClasses.add(superAnalyzedClass);
            } else {
                ImmutableAnalyzedClass.Builder builder = ImmutableAnalyzedClass.builder()
                        .copyFrom(superAnalyzedClass);
                List<AnalyzedMethod> analyzedMethods = Lists.newArrayList();
                for (AnalyzedMethod analyzedMethod : superAnalyzedClass.analyzedMethods()) {
                    analyzedMethods.add(ImmutableAnalyzedMethod.builder()
                            .copyFrom(analyzedMethod)
                            .addSubTypeRestrictedAdvisors(advice)
                            .build());
                }
                builder.analyzedMethods(analyzedMethods);
                ejbHackedSuperAnalyzedClasses.add(builder.build());
            }
        }
        return ejbHackedSuperAnalyzedClasses;
    }

    private static void addToInterfaceNamesToInstrument(String interfaceName,
            Map<String, List<String>> superInterfaceNamesMap,
            Map<String, String> interfaceNamesToInstrument, String ejbRemoteInterface) {
        interfaceNamesToInstrument.put(interfaceName, ejbRemoteInterface);
        List<String> superInterfaceNames = superInterfaceNamesMap.get(interfaceName);
        if (superInterfaceNames != null) {
            for (String superInterfaceName : superInterfaceNames) {
                addToInterfaceNamesToInstrument(superInterfaceName, superInterfaceNamesMap,
                        interfaceNamesToInstrument, ejbRemoteInterface);
            }
        }
    }

    private static boolean hasSuperAdvice(List<AnalyzedClass> superAnalyzedClasses) {
        for (AnalyzedClass superAnalyzedClass : superAnalyzedClasses) {
            if (!superAnalyzedClass.analyzedMethods().isEmpty()) {
                return true;
            }
        }
        return false;
    }

    private static boolean isSubTypeRestrictionMatch(Advice advice, Set<String> superClassNames) {
        Pattern pointcutSubTypeRestrictionPattern = advice.pointcutSubTypeRestrictionPattern();
        if (pointcutSubTypeRestrictionPattern == null) {
            String subTypeRestriction = advice.pointcut().subTypeRestriction();
            return subTypeRestriction.isEmpty() || superClassNames.contains(subTypeRestriction);
        }
        for (String superClassName : superClassNames) {
            if (pointcutSubTypeRestrictionPattern.matcher(superClassName).matches()) {
                return true;
            }
        }
        return false;
    }

    static List<Advice> sortAdvisors(Collection<Advice> matchingAdvisors) {
        switch (matchingAdvisors.size()) {
            case 0:
                return ImmutableList.of();
            case 1:
                return Lists.newArrayList(matchingAdvisors);
            default:
                return Advice.ordering.sortedCopy(matchingAdvisors);
        }
    }

    private static List<AnalyzedMethodKey> getNonAbstractMethods(String className,
            @Nullable ClassLoader loader) throws ClassNotFoundException, IOException {
        String path = ClassNames.toInternalName(className) + ".class";
        URL url;
        if (loader == null) {
            // null loader means the bootstrap class loader
            url = ClassLoader.getSystemResource(path);
        } else {
            url = loader.getResource(path);
        }
        if (url == null) {
            // what follows is just a best attempt in the sort-of-rare case when a custom class
            // loader does not expose .class file contents via getResource(), e.g.
            // org.codehaus.groovy.runtime.callsite.CallSiteClassLoader
            return getNonAbstractMethodsPlanB(className, loader);
        }
        byte[] bytes = Resources.toByteArray(url);
        NonAbstractMethodClassVisitor accv = new NonAbstractMethodClassVisitor();
        new ClassReader(bytes).accept(accv, ClassReader.SKIP_FRAMES + ClassReader.SKIP_CODE);
        return accv.analyzedMethodKeys;
    }

    private static List<AnalyzedMethodKey> getNonAbstractMethodsPlanB(String className,
            @Nullable ClassLoader loader) throws ClassNotFoundException {
        Class<?> clazz = Class.forName(className, false, loader);
        List<AnalyzedMethodKey> analyzedMethodKeys = Lists.newArrayList();
        for (Method method : clazz.getDeclaredMethods()) {
            ImmutableAnalyzedMethodKey.Builder builder = ImmutableAnalyzedMethodKey.builder()
                    .name(method.getName());
            for (Class<?> parameterType : method.getParameterTypes()) {
                builder.addParameterTypes(parameterType.getName());
            }
            analyzedMethodKeys.add(builder.build());
        }
        return analyzedMethodKeys;
    }

    // AnalyzedMethod equivalence defined only in terms of method name and parameter types
    // so that overridden methods will be equivalent
    @Value.Immutable
    abstract static class AnalyzedMethodKey {

        abstract String name();
        abstract ImmutableList<String> parameterTypes();
        // null is only used when constructing key purely for lookup
        @Value.Auxiliary
        abstract @Nullable AnalyzedMethod analyzedMethod();

        private static AnalyzedMethodKey wrap(AnalyzedMethod analyzedMethod) {
            return ImmutableAnalyzedMethodKey.builder()
                    .name(analyzedMethod.name())
                    .addAllParameterTypes(analyzedMethod.parameterTypes())
                    .analyzedMethod(analyzedMethod)
                    .build();
        }
    }

    @Value.Immutable
    interface MatchedShimTypes {
        List<ShimType> reweavable();
        List<ShimType> nonReweavable();
    }

    @Value.Immutable
    interface MatchedMixinTypes {
        List<MixinType> reweavable();
        List<MixinType> nonReweavable();
    }

    private static class BridgeMethodClassVisitor extends ClassVisitor {

        private final Map<String, String> bridgeTargetMethods = Maps.newHashMap();

        private BridgeMethodClassVisitor() {
            super(ASM7);
        }

        public Map<String, String> getBridgeTargetMethods() {
            return bridgeTargetMethods;
        }

        @Override
        public @Nullable MethodVisitor visitMethod(int access, String name, String descriptor,
                @Nullable String signature, String /*@Nullable*/ [] exceptions) {
            if ((access & ACC_BRIDGE) == 0) {
                return null;
            }
            return new BridgeMethodVisitor(name, descriptor);
        }

        private class BridgeMethodVisitor extends MethodVisitor {

            private String bridgeMethodName;
            private String bridgeMethodDesc;
            private int bridgeMethodParamCount;

            private boolean found;

            private BridgeMethodVisitor(String bridgeMethodName, String bridgeMethodDesc) {
                super(ASM7);
                this.bridgeMethodName = bridgeMethodName;
                this.bridgeMethodDesc = bridgeMethodDesc;
                bridgeMethodParamCount = Type.getArgumentTypes(bridgeMethodDesc).length;
            }

            @Override
            public void visitMethodInsn(int opcode, String owner, String name, String descriptor,
                    boolean itf) {
                if (found) {
                    return;
                }
                if (!name.equals(bridgeMethodName)) {
                    return;
                }
                if (Type.getArgumentTypes(descriptor).length != bridgeMethodParamCount) {
                    return;
                }
                if (opcode == INVOKESPECIAL) {
                    // this is a generated bridge method that just calls super, see
                    // WeaverTest.shouldHandleBridgeCallingSuper(), presumably the super method
                    // already matches advice
                    return;
                }
                bridgeTargetMethods.put(this.bridgeMethodName + this.bridgeMethodDesc,
                        name + descriptor);
                found = true;
            }
        }
    }

    private static class NonAbstractMethodClassVisitor extends ClassVisitor {

        private List<AnalyzedMethodKey> analyzedMethodKeys = Lists.newArrayList();

        private NonAbstractMethodClassVisitor() {
            super(ASM7);
        }

        @Override
        public @Nullable MethodVisitor visitMethod(int access, String name, String descriptor,
                @Nullable String signature, String /*@Nullable*/ [] exceptions) {
            if (!Modifier.isAbstract(access)) {
                ImmutableAnalyzedMethodKey.Builder builder = ImmutableAnalyzedMethodKey.builder()
                        .name(name);
                for (Type type : Type.getArgumentTypes(descriptor)) {
                    builder.addParameterTypes(type.getClassName());
                }
                analyzedMethodKeys.add(builder.build());
            }
            return null;
        }
    }
}
