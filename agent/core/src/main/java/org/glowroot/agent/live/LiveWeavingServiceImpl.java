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
package org.glowroot.agent.live;

import java.lang.instrument.Instrumentation;
import java.lang.instrument.UnmodifiableClassException;
import java.lang.reflect.Modifier;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Splitter;
import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import com.google.common.collect.ArrayListMultimap;
import com.google.common.collect.ComparisonChain;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Iterables;
import com.google.common.collect.Lists;
import com.google.common.collect.Multimap;
import com.google.common.collect.Ordering;
import com.google.common.collect.Sets;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.checkerframework.checker.nullness.qual.RequiresNonNull;
import org.immutables.value.Value;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.glowroot.agent.config.ConfigService;
import org.glowroot.agent.live.ClasspathCache.UiAnalyzedMethod;
import org.glowroot.agent.util.MaybePatterns;
import org.glowroot.agent.weaving.AdviceCache;
import org.glowroot.agent.weaving.AnalyzedWorld;
import org.glowroot.common.config.InstrumentationConfig;
import org.glowroot.common.live.LiveWeavingService;
import org.glowroot.wire.api.model.DownstreamServiceOuterClass.GlobalMeta;
import org.glowroot.wire.api.model.DownstreamServiceOuterClass.MethodSignature;

import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;
import static org.objectweb.asm.Opcodes.ACC_FINAL;
import static org.objectweb.asm.Opcodes.ACC_SYNCHRONIZED;

public class LiveWeavingServiceImpl implements LiveWeavingService {

    private static final Logger logger = LoggerFactory.getLogger(LiveWeavingServiceImpl.class);

    private static final String THE_SINGLE_KEY = "THE_SINGLE_KEY";
    private static final Splitter splitter = Splitter.on(' ').omitEmptyStrings();

    private final AnalyzedWorld analyzedWorld;
    private final @Nullable Instrumentation instrumentation;
    private final ConfigService configService;
    private final AdviceCache adviceCache;
    private final boolean jvmRetransformClassesSupported;

    // hopefully can simplify someday https://github.com/google/guava/issues/872
    private final LoadingCache<String, ClasspathCache> classpathCache = CacheBuilder.newBuilder()
            .softValues()
            .maximumSize(1)
            .build(new CacheLoader<String, ClasspathCache>() {
                @Override
                public ClasspathCache load(String key) throws Exception {
                    return new ClasspathCache(analyzedWorld, instrumentation);
                }
            });

    public LiveWeavingServiceImpl(AnalyzedWorld analyzedWorld,
            @Nullable Instrumentation instrumentation, ConfigService configService,
            AdviceCache adviceCache, boolean jvmRetransformClassesSupported) {
        this.analyzedWorld = analyzedWorld;
        this.instrumentation = instrumentation;
        this.configService = configService;
        this.adviceCache = adviceCache;
        this.jvmRetransformClassesSupported = jvmRetransformClassesSupported;
    }

    @Override
    public GlobalMeta getGlobalMeta(String agentId) {
        return GlobalMeta.newBuilder()
                .setJvmOutOfSync(adviceCache.isOutOfSync(configService.getInstrumentationConfigs()))
                .setJvmRetransformClassesSupported(jvmRetransformClassesSupported)
                .build();
    }

    @Override
    public void preloadClasspathCache(String agentId) {
        // run in background and return immediate so as not to block single UI thread (when running
        // embedded) or single gRPC thread (when reporting to central collector)
        Thread thread = new Thread(new Runnable() {
            @Override
            public void run() {
                getClasspathCache().updateCache();
            }
        });
        thread.setName("Glowroot-Preload-Classpath-Cache");
        thread.setDaemon(true);
        thread.start();
    }

    @Override
    public List<String> getMatchingClassNames(String agentId, String partialClassName, int limit) {
        return getClasspathCache().getMatchingClassNames(partialClassName, limit);
    }

    // returns the first <limit> matching method names, ordered alphabetically (case-insensitive)
    @Override
    public List<String> getMatchingMethodNames(String agentId, String className,
            String partialMethodName, int limit) {
        String partialMethodNameUpper = partialMethodName.toUpperCase(Locale.ENGLISH);
        Set<String> methodNames = Sets.newHashSet();
        for (UiAnalyzedMethod analyzedMethod : getClasspathCache().getAnalyzedMethods(className)) {
            String methodName = analyzedMethod.name();
            if (methodName.equals("<init>") || methodName.equals("<clinit>")) {
                // static initializers are not supported by weaver
                // (see AdviceMatcher.isMethodNameMatch())
                // and constructors do not support @OnBefore advice at this time
                continue;
            }
            if (methodName.toUpperCase(Locale.ENGLISH).contains(partialMethodNameUpper)) {
                methodNames.add(methodName);
            }
        }
        ImmutableList<String> sortedMethodNames =
                Ordering.natural().immutableSortedCopy(methodNames);
        if (methodNames.size() > limit) {
            return sortedMethodNames.subList(0, limit);
        } else {
            return sortedMethodNames;
        }
    }

    @Override
    public List<MethodSignature> getMethodSignatures(String agentId, String className,
            String methodName) {
        if (methodName.contains("*") || methodName.contains("|")) {
            return ImmutableList.of();
        }
        List<UiAnalyzedMethod> analyzedMethods = getAnalyzedMethods(className, methodName);
        List<MethodSignature> methodSignatures = Lists.newArrayList();
        for (UiAnalyzedMethod analyzedMethod : analyzedMethods) {
            MethodSignature.Builder builder = MethodSignature.newBuilder();
            builder.setName(analyzedMethod.name());
            builder.addAllParameterType(analyzedMethod.parameterTypes());
            builder.setReturnType(analyzedMethod.returnType());
            // strip final and synchronized from displayed modifiers since they have no impact on
            // the weaver's method matching
            int reducedModifiers = analyzedMethod.modifiers() & ~ACC_FINAL & ~ACC_SYNCHRONIZED;
            String modifierNames = Modifier.toString(reducedModifiers);
            for (String modifier : splitter.split(modifierNames)) {
                builder.addModifier(modifier.toLowerCase(Locale.ENGLISH));
            }
            methodSignatures.add(builder.build());
        }
        return methodSignatures;
    }

    @Override
    public int reweave(String agentId) throws Exception {
        if (instrumentation == null) {
            // this method is called from GlowrootAgentInit.resetConfigForTests() when
            // instrumentation is null
            return 0;
        }
        // this command is filtered out of the UI when retransform classes is not supported
        checkState(instrumentation.isRetransformClassesSupported(),
                "Retransform classes is not supported");
        return reweaveInternal();
    }

    private List<UiAnalyzedMethod> getAnalyzedMethods(String className, String methodName) {
        // use set to remove duplicate methods (e.g. same class loaded by multiple class loaders)
        Set<UiAnalyzedMethod> analyzedMethods = Sets.newHashSet();
        for (UiAnalyzedMethod analyzedMethod : getClasspathCache().getAnalyzedMethods(className)) {
            if (analyzedMethod.name().equals(methodName)) {
                analyzedMethods.add(analyzedMethod);
            }
        }
        // order methods by accessibility, then by name, then by number of args
        return new UiAnalyzedMethodOrdering().sortedCopy(analyzedMethods);
    }

    private ClasspathCache getClasspathCache() {
        return classpathCache.getUnchecked(THE_SINGLE_KEY);
    }

    @RequiresNonNull("instrumentation")
    private int reweaveInternal() throws Exception {
        List<InstrumentationConfig> configs = configService.getInstrumentationConfigs();
        adviceCache.updateAdvisors(configs);
        Set<PointcutClassName> pointcutClassNames = Sets.newHashSet();
        for (InstrumentationConfig config : configs) {
            PointcutClassName subTypeRestrictionPointClassName = null;
            String subTypeRestriction = config.subTypeRestriction();
            if (!subTypeRestriction.isEmpty()) {
                subTypeRestrictionPointClassName =
                        PointcutClassName.fromMaybePattern(subTypeRestriction, null, false);
            }
            String className = config.className();
            if (!className.isEmpty()) {
                pointcutClassNames.add(PointcutClassName.fromMaybePattern(className,
                        subTypeRestrictionPointClassName, config.methodName().equals("<init>")));
            }
        }
        Set<Class<?>> classes = Sets.newHashSet();
        Set<Class<?>> possibleNewReweavableClasses = getExistingModifiableSubClasses(
                pointcutClassNames, instrumentation.getAllLoadedClasses(), instrumentation);
        // need to remove these classes from AnalyzedWorld, otherwise if a subclass and its parent
        // class are both in the list and the subclass is re-transformed first, it will use the
        // old cached AnalyzedClass for its parent which will have the old AnalyzedMethod advisors
        List<Class<?>> existingReweavableClasses =
                analyzedWorld.getClassesWithReweavableAdvice(true);
        analyzedWorld.removeClasses(possibleNewReweavableClasses);
        classes.addAll(existingReweavableClasses);
        classes.addAll(possibleNewReweavableClasses);
        if (classes.isEmpty()) {
            return 0;
        }
        instrumentation.retransformClasses(Iterables.toArray(classes, Class.class));
        List<Class<?>> updatedReweavableClasses =
                analyzedWorld.getClassesWithReweavableAdvice(false);
        // all existing reweavable classes were woven
        int count = existingReweavableClasses.size();
        // now add newly reweavable classes
        for (Class<?> possibleNewReweavableClass : possibleNewReweavableClasses) {
            if (updatedReweavableClasses.contains(possibleNewReweavableClass)
                    && !existingReweavableClasses.contains(possibleNewReweavableClass)) {
                count++;
            }
        }
        return count;
    }

    public static void initialReweave(Set<PointcutClassName> pointcutClassNames,
            Class<?>[] initialLoadedClasses, Instrumentation instrumentation) {
        if (!instrumentation.isRetransformClassesSupported()) {
            return;
        }
        Set<Class<?>> classes = getExistingModifiableSubClasses(pointcutClassNames,
                initialLoadedClasses, instrumentation);
        for (Class<?> clazz : classes) {
            if (clazz.isInterface()) {
                continue;
            }
            try {
                instrumentation.retransformClasses(clazz);
            } catch (UnmodifiableClassException e) {
                // IBM J9 VM Java 6 throws UnmodifiableClassException even though call to
                // isModifiableClass() in getExistingModifiableSubClasses() returns true
                logger.debug(e.getMessage(), e);
            }
        }
    }

    private static Set<Class<?>> getExistingModifiableSubClasses(
            Set<PointcutClassName> pointcutClassNames, Class<?>[] classes,
            Instrumentation instrumentation) {
        List<Class<?>> matchingClasses = Lists.newArrayList();
        Multimap<Class<?>, Class<?>> subClasses = ArrayListMultimap.create();
        for (Class<?> clazz : classes) {
            if (!instrumentation.isModifiableClass(clazz)) {
                continue;
            }
            Class<?> superclass = clazz.getSuperclass();
            if (superclass != null) {
                subClasses.put(superclass, clazz);
            }
            for (Class<?> iface : clazz.getInterfaces()) {
                subClasses.put(iface, clazz);
            }
            for (PointcutClassName pointcutClassName : pointcutClassNames) {
                if (pointcutClassName.appliesTo(clazz.getName())) {
                    matchingClasses.add(clazz);
                    break;
                }
            }
        }
        Set<Class<?>> matchingSubClasses = Sets.newHashSet();
        for (Class<?> matchingClass : matchingClasses) {
            addToMatchingSubClasses(matchingClass, matchingSubClasses, subClasses);
        }
        return matchingSubClasses;
    }

    private static void addToMatchingSubClasses(Class<?> clazz, Set<Class<?>> matchingSubClasses,
            Multimap<Class<?>, Class<?>> subClasses) {
        matchingSubClasses.add(clazz);
        for (Class<?> subClass : subClasses.get(clazz)) {
            addToMatchingSubClasses(subClass, matchingSubClasses, subClasses);
        }
    }

    @VisibleForTesting
    static class UiAnalyzedMethodOrdering extends Ordering<UiAnalyzedMethod> {

        @Override
        public int compare(UiAnalyzedMethod left, UiAnalyzedMethod right) {
            return ComparisonChain.start()
                    .compare(getAccessibility(left), getAccessibility(right))
                    .compare(left.name(), right.name())
                    .compare(left.parameterTypes().size(), right.parameterTypes().size())
                    .result();
        }

        private static int getAccessibility(UiAnalyzedMethod analyzedMethod) {
            int modifiers = analyzedMethod.modifiers();
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

    @Value.Immutable
    public abstract static class PointcutClassName {

        abstract @Nullable Pattern pattern();
        abstract @Nullable String nonPattern();
        abstract @Nullable PointcutClassName subTypeRestriction();
        abstract boolean doNotMatchSubClasses();

        public static PointcutClassName fromMaybePattern(String maybePattern,
                @Nullable PointcutClassName subTypeRestriction, boolean doNotMatchSubClasses) {
            Pattern pattern = MaybePatterns.buildPattern(maybePattern);
            if (pattern == null) {
                return fromNonPattern(maybePattern, subTypeRestriction, doNotMatchSubClasses);
            } else {
                return fromPattern(pattern, subTypeRestriction, doNotMatchSubClasses);
            }
        }

        public static PointcutClassName fromPattern(Pattern pattern,
                @Nullable PointcutClassName subTypeRestrictionPointcutClassName,
                boolean doNotMatchSubClasses) {
            return ImmutablePointcutClassName.builder()
                    .pattern(pattern)
                    .nonPattern(null)
                    .subTypeRestriction(subTypeRestrictionPointcutClassName)
                    .doNotMatchSubClasses(doNotMatchSubClasses)
                    .build();
        }

        public static PointcutClassName fromNonPattern(String nonPattern,
                @Nullable PointcutClassName subTypeRestrictionPointcutClassName,
                boolean doNotMatchSubClasses) {
            return ImmutablePointcutClassName.builder()
                    .pattern(null)
                    .nonPattern(nonPattern)
                    .subTypeRestriction(subTypeRestrictionPointcutClassName)
                    .doNotMatchSubClasses(doNotMatchSubClasses)
                    .build();
        }

        private boolean appliesTo(String className) {
            PointcutClassName subTypeRestriction = subTypeRestriction();
            if (subTypeRestriction != null && !subTypeRestriction.appliesTo(className)) {
                return false;
            }
            Pattern pattern = pattern();
            if (pattern != null) {
                return pattern.matcher(className).matches();
            } else {
                return checkNotNull(nonPattern()).equals(className);
            }
        }
    }
}
