/*
 * Copyright 2012-2018 the original author or authors.
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
import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.net.URL;
import java.security.CodeSource;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import javax.annotation.Nullable;

import com.google.common.base.Charsets;
import com.google.common.base.Supplier;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.io.Resources;
import com.google.common.primitives.Bytes;
import org.immutables.value.Value;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Type;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.glowroot.agent.config.InstrumentationConfig;
import org.glowroot.agent.weaving.ClassLoaders.LazyDefinedClass;
import org.glowroot.common.util.Styles;

public class AnalyzedWorld {

    private static final Logger logger = LoggerFactory.getLogger(AnalyzedWorld.class);

    private static final Method findLoadedClassMethod;

    static {
        try {
            findLoadedClassMethod =
                    ClassLoader.class.getDeclaredMethod("findLoadedClass", String.class);
            findLoadedClassMethod.setAccessible(true);
        } catch (Exception e) {
            // unrecoverable error
            throw new AssertionError(e);
        }
    }

    // weak keys to prevent retention of class loaders
    // it's important that the weak keys point directly to the class loaders themselves (as opposed
    // to through another instance, e.g. Optional<ClassLoader>) so that the keys won't be cleared
    // while their associated class loaders are still being used
    //
    // not using the much more convenient (and concurrent) guava CacheBuilder since it uses many
    // additional classes that must then be pre-initialized since this is called from inside
    // ClassFileTransformer.transform() (see PreInitializeClasses)
    private final Map<ClassLoader, ConcurrentMap<String, AnalyzedClass>> world = Collections
            .synchronizedMap(new WeakHashMap<ClassLoader, ConcurrentMap<String, AnalyzedClass>>());

    // the analyzed classes for the bootstrap class loader (null) have to be stored separately since
    // LoadingCache doesn't accept null keys, and using an Optional<ClassLoader> for the key makes
    // the weakness on the Optional instance which is not strongly referenced from anywhere and
    // therefore the keys will most likely be cleared while their class loaders are still being used
    //
    // intentionally avoiding Maps.newConcurrentMap() for the same reason as above
    private final ConcurrentMap<String, AnalyzedClass> bootstrapLoaderWorld =
            new ConcurrentHashMap<String, AnalyzedClass>();

    private final Supplier<List<Advice>> advisors;
    private final ImmutableList<ShimType> shimTypes;
    private final ImmutableList<MixinType> mixinTypes;

    public AnalyzedWorld(Supplier<List<Advice>> advisors, List<ShimType> shimTypes,
            List<MixinType> mixinTypes) {
        this.advisors = advisors;
        this.shimTypes = ImmutableList.copyOf(shimTypes);
        this.mixinTypes = ImmutableList.copyOf(mixinTypes);
    }

    public List<Class<?>> getClassesWithReweavableAdvice(boolean remove) {
        List<Class<?>> classes = Lists.newArrayList();
        for (ClassLoader loader : getClassLoaders()) {
            classes.addAll(getClassesWithReweavableAdvice(loader, remove));
        }
        classes.addAll(getClassesWithReweavableAdvice(null, remove));
        return classes;
    }

    public void removeClasses(List<Class<?>> classes) {
        for (Map<String, AnalyzedClass> map : getWorldValues()) {
            for (Class<?> clazz : classes) {
                map.remove(clazz.getName());
            }
        }
        for (Class<?> clazz : classes) {
            bootstrapLoaderWorld.remove(clazz.getName());
        }
    }

    public ImmutableList<ClassLoader> getClassLoaders() {
        synchronized (world) {
            return ImmutableList.copyOf(world.keySet());
        }
    }

    void add(AnalyzedClass analyzedClass, @Nullable ClassLoader loader) {
        ConcurrentMap<String, AnalyzedClass> loaderAnalyzedClasses = getAnalyzedClasses(loader);
        loaderAnalyzedClasses.put(analyzedClass.name(), analyzedClass);
    }

    // it's ok if there are duplicates in the returned list (e.g. an interface that appears twice
    // in a type hierarchy), it's rare, dups don't cause an issue for callers, and so it doesn't
    // seem worth the (minor) performance hit to de-dup every time
    List<AnalyzedClass> getAnalyzedHierarchy(@Nullable String className,
            @Nullable ClassLoader loader, ParseContext parseContext) {
        if (className == null || className.equals("java.lang.Object")) {
            return ImmutableList.of();
        }
        return getSuperClasses(className, loader, parseContext);
    }

    List<Advice> mergeInstrumentationAnnotations(List<Advice> advisors, byte[] classBytes,
            @Nullable ClassLoader loader, String className) {
        byte[] marker = "Lorg/glowroot/agent/api/Instrumentation$".getBytes(Charsets.UTF_8);
        if (Bytes.indexOf(classBytes, marker) == -1) {
            return advisors;
        }
        InstrumentationSeekerClassVisitor cv = new InstrumentationSeekerClassVisitor();
        ClassReader cr = new ClassReader(classBytes);
        cr.accept(cv, ClassReader.SKIP_CODE);
        List<InstrumentationConfig> instrumentationConfigs = cv.getInstrumentationConfigs();
        if (instrumentationConfigs.isEmpty()) {
            return advisors;
        }
        if (loader == null) {
            logger.warn("@Instrumentation annotations not currently supported in bootstrap class"
                    + " loader: {}", className);
            return advisors;
        }
        for (InstrumentationConfig instrumentationConfig : instrumentationConfigs) {
            instrumentationConfig.logValidationErrorsIfAny();
        }
        ImmutableMap<Advice, LazyDefinedClass> newAdvisors =
                AdviceGenerator.createAdvisors(instrumentationConfigs, null, false);
        try {
            ClassLoaders.defineClassesInClassLoader(newAdvisors.values(), loader);
        } catch (Exception e) {
            logger.error(e.getMessage(), e);
        }
        List<Advice> mergedAdvisors = Lists.newArrayList(advisors);
        mergedAdvisors.addAll(newAdvisors.keySet());
        return mergedAdvisors;
    }

    // it's ok if there are duplicates in the returned list (e.g. an interface that appears twice
    // in a type hierarchy), it's rare, dups don't cause an issue for callers, and so it doesn't
    // seem worth the (minor) performance hit to de-dup every time
    private List<AnalyzedClass> getSuperClasses(String className, @Nullable ClassLoader loader,
            ParseContext parseContext) {
        AnalyzedClass analyzedClass;
        try {
            analyzedClass = getOrCreateAnalyzedClass(className, loader);
        } catch (IOException e) {
            logger.error(e.getMessage(), e);
            return ImmutableList.of();
        } catch (ClassNotFoundException e) {
            // log at debug level only since the code referencing the class must not be getting used
            // anyways, as it would fail on execution since the type doesn't exist
            logger.debug("type {} not found while parsing type {}", className, parseContext, e);
            return ImmutableList.of();
        }
        List<AnalyzedClass> superTypes = Lists.newArrayList();
        superTypes.add(analyzedClass);
        String superName = analyzedClass.superName();
        if (superName != null && !superName.equals("java.lang.Object")) {
            superTypes.addAll(getSuperClasses(superName, loader, parseContext));
        }
        for (String interfaceName : analyzedClass.interfaceNames()) {
            superTypes.addAll(getSuperClasses(interfaceName, loader, parseContext));
        }
        return superTypes;
    }

    private AnalyzedClass getOrCreateAnalyzedClass(String className, @Nullable ClassLoader loader)
            throws ClassNotFoundException, IOException {
        ClassLoader analyzedClassLoader = getAnalyzedLoader(className, loader);
        ConcurrentMap<String, AnalyzedClass> loaderAnalyzedClasses =
                getAnalyzedClasses(analyzedClassLoader);
        AnalyzedClass analyzedClass = loaderAnalyzedClasses.get(className);
        if (analyzedClass == null) {
            if (loader != analyzedClassLoader) {
                // this class may have been looked up and stored previously in loader's map, and
                // then subsequently loaded into it's true class loader (analyzedClassLoader)
                ConcurrentMap<String, AnalyzedClass> currLoaderAnalyzedClasses =
                        getAnalyzedClasses(loader);
                analyzedClass = currLoaderAnalyzedClasses.get(className);
                if (analyzedClass != null) {
                    analyzedClass = putAnalyzedClass(loaderAnalyzedClasses, analyzedClass);
                    // remove it from the "incorrect" class loader
                    currLoaderAnalyzedClasses.remove(className);
                    // this
                    return analyzedClass;
                }
            }
            analyzedClass = createAnalyzedClass(className, analyzedClassLoader);
            analyzedClass = putAnalyzedClass(loaderAnalyzedClasses, analyzedClass);
        }
        return analyzedClass;
    }

    private List<Class<?>> getClassesWithReweavableAdvice(@Nullable ClassLoader loader,
            boolean remove) {
        List<Class<?>> classes = Lists.newArrayList();
        ConcurrentMap<String, AnalyzedClass> loaderAnalyzedClasses = getAnalyzedClasses(loader);
        for (Entry<String, AnalyzedClass> innerEntry : loaderAnalyzedClasses.entrySet()) {
            if (innerEntry.getValue().hasReweavableAdvice()) {
                try {
                    classes.add(Class.forName(innerEntry.getKey(), false, loader));
                } catch (ClassNotFoundException e) {
                    logger.warn(e.getMessage(), e);
                }
            }
        }
        if (remove) {
            for (Class<?> clazz : classes) {
                loaderAnalyzedClasses.remove(clazz.getName());
            }
        }
        return classes;
    }

    private AnalyzedClass createAnalyzedClass(String className, @Nullable ClassLoader loader)
            throws ClassNotFoundException, IOException {
        String path = ClassNames.toInternalName(className) + ".class";
        URL url;
        if (loader == null) {
            // null loader means the bootstrap class loader
            url = ClassLoader.getSystemResource(path);
        } else {
            url = loader.getResource(path);
            if (url != null) {
                AnalyzedClass parentLoaderAnalyzedClass =
                        tryToReuseFromParentLoader(className, loader, path, url);
                if (parentLoaderAnalyzedClass != null) {
                    return parentLoaderAnalyzedClass;
                }
            }
        }
        if (url == null) {
            // what follows is just a best attempt in the sort-of-rare case when a custom class
            // loader does not expose .class file contents via getResource(), e.g.
            // org.codehaus.groovy.runtime.callsite.CallSiteClassLoader
            return createAnalyzedClassPlanB(className, loader);
        }
        byte[] bytes = Resources.toByteArray(url);
        List<Advice> advisors =
                mergeInstrumentationAnnotations(this.advisors.get(), bytes, loader, className);
        ThinClassVisitor accv = new ThinClassVisitor();
        new ClassReader(bytes).accept(accv, ClassReader.SKIP_FRAMES + ClassReader.SKIP_CODE);
        ClassAnalyzer classAnalyzer = new ClassAnalyzer(accv.getThinClass(), advisors, shimTypes,
                mixinTypes, loader, this, null, bytes);
        classAnalyzer.analyzeMethods();
        return classAnalyzer.getAnalyzedClass();
    }

    private @Nullable AnalyzedClass tryToReuseFromParentLoader(String className,
            ClassLoader originalLoader, String path, URL url) {
        ClassLoader loader = originalLoader;
        while (loader != null) {
            ClassLoader parentLoader = loader.getParent();
            URL parentLoaderUrl;
            if (parentLoader == null) {
                parentLoaderUrl = ClassLoader.getSystemResource(path);
            } else {
                parentLoaderUrl = parentLoader.getResource(path);
            }
            // comparing results of URL.toExternalForm() since using URL.equals() directly
            // performs name resolution and is a blocking operation (from the javadoc)
            if (parentLoaderUrl != null
                    && parentLoaderUrl.toExternalForm().equals(url.toExternalForm())) {
                // reuse parent loader's AnalyzedClass if available
                // this saves time here, and reduces memory footprint of AnalyzedWorld
                // which can be very noticeable when lots of ClassLoaders, e.g. groovy
                AnalyzedClass parentLoaderAnalyzedClass =
                        getAnalyzedClasses(parentLoader).get(className);
                if (parentLoaderAnalyzedClass != null) {
                    return parentLoaderAnalyzedClass;
                }
            }
            loader = parentLoader;
        }
        return null;
    }

    // plan B covers some class loaders like
    // org.codehaus.groovy.runtime.callsite.CallSiteClassLoader that delegate loadClass() to some
    // other loader where the type may have already been loaded
    private AnalyzedClass createAnalyzedClassPlanB(String className, @Nullable ClassLoader loader)
            throws ClassNotFoundException {
        Class<?> clazz = Class.forName(className, false, loader);
        AnalyzedClass analyzedClass = getAnalyzedClasses(clazz.getClassLoader()).get(className);
        if (analyzedClass != null) {
            return analyzedClass;
        }
        // the class loaded by Class.forName() above was not previously loaded which means
        // weaving was bypassed since ClassFileTransformer.transform() is not re-entrant
        analyzedClass = createAnalyzedClassPlanC(clazz, advisors.get());
        if (analyzedClass.isInterface()) {
            // FIXME log warning if any default methods have advice
            return analyzedClass;
        }
        if (!analyzedClass.analyzedMethods().isEmpty()) {
            logger.warn(
                    "{} was not woven with requested advice (it was first encountered during the"
                            + " weaving of one of its {} and the resource {}.class could not be"
                            + " found in class loader {}, so {} had to be explicitly loaded using"
                            + " Class.forName() in the middle of weaving the {}, which means it was"
                            + " not woven itself since weaving is not re-entrant)",
                    clazz.getName(), analyzedClass.isInterface() ? "implementations" : "subclasses",
                    ClassNames.toInternalName(clazz.getName()), loader, clazz.getName(),
                    analyzedClass.isInterface() ? "implementation" : "subclass");
        }
        return analyzedClass;
    }

    private ConcurrentMap<String, AnalyzedClass> getAnalyzedClasses(@Nullable ClassLoader loader) {
        if (loader == null) {
            return bootstrapLoaderWorld;
        } else {
            // this synchronization is for atomicity of get/put
            synchronized (world) {
                ConcurrentMap<String, AnalyzedClass> map = world.get(loader);
                if (map == null) {
                    map = new ConcurrentHashMap<String, AnalyzedClass>();
                    world.put(loader, map);
                }
                return map;
            }
        }
    }

    private ImmutableList<ConcurrentMap<String, AnalyzedClass>> getWorldValues() {
        synchronized (world) {
            return ImmutableList.copyOf(world.values());
        }
    }

    private static AnalyzedClass putAnalyzedClass(
            ConcurrentMap<String, AnalyzedClass> loaderAnalyzedClasses,
            AnalyzedClass analyzedClass) {
        AnalyzedClass existingAnalyzedClass =
                loaderAnalyzedClasses.putIfAbsent(analyzedClass.name(), analyzedClass);
        if (existingAnalyzedClass != null) {
            // (rare) concurrent AnalyzedClass creation, use the one that made it into the map
            return existingAnalyzedClass;
        }
        return analyzedClass;
    }

    private static @Nullable ClassLoader getAnalyzedLoader(String className,
            @Nullable ClassLoader loader) {
        if (loader == null) {
            return null;
        }
        // can't call Class.forName() since that bypasses ClassFileTransformer.transform() if the
        // class hasn't already been loaded, so instead, call the package protected
        // ClassLoader.findLoadClass()
        Class<?> clazz = null;
        try {
            clazz = (Class<?>) findLoadedClassMethod.invoke(loader, className);
        } catch (Exception e) {
            logger.error(e.getMessage(), e);
        }
        ClassLoader analyzedLoader = loader;
        if (clazz != null) {
            // this class has already been loaded, so the corresponding analyzedClass should already
            // be in the cache under its class loader
            //
            // this helps in cases where the .class files are not available via
            // ClassLoader.getResource(), as well as being a good optimization in other cases
            analyzedLoader = clazz.getClassLoader();
        }
        return analyzedLoader;
    }

    // now that the type has been loaded anyways, build the analyzed class via reflection
    private static AnalyzedClass createAnalyzedClassPlanC(Class<?> clazz, List<Advice> advisors) {
        ImmutableAnalyzedClass.Builder classBuilder = ImmutableAnalyzedClass.builder();
        classBuilder.modifiers(clazz.getModifiers());
        classBuilder.name(clazz.getName());
        Class<?> superClass = clazz.getSuperclass();
        String superName = superClass == null ? null : superClass.getName();
        classBuilder.superName(superName);
        List<String> superClassNames = Lists.newArrayList();
        if (superName != null) {
            superClassNames.add(superName);
        }
        for (Class<?> interfaceClass : clazz.getInterfaces()) {
            String interfaceClassName = interfaceClass.getName();
            classBuilder.addInterfaceNames(interfaceClassName);
            superClassNames.add(interfaceClassName);
        }
        // FIXME handle @Instrumentation.*
        List<String> classAnnotations = Lists.newArrayList();
        for (Annotation annotation : clazz.getAnnotations()) {
            classAnnotations.add(annotation.annotationType().getName());
        }
        // TODO document limitations of superClassNames only containing first level super classes
        // (e.g. doesn't include super class's super class)
        List<AdviceMatcher> adviceMatchers = AdviceMatcher.getAdviceMatchers(clazz.getName(),
                classAnnotations, superClassNames, advisors);
        Map<Method, List<Advice>> bridgeTargetAdvisors = Maps.newHashMap();
        for (Method method : clazz.getDeclaredMethods()) {
            if (!method.isBridge()) {
                continue;
            }
            List<String> methodAnnotations = Lists.newArrayList();
            for (Annotation annotation : method.getAnnotations()) {
                methodAnnotations.add(annotation.annotationType().getName());
            }
            List<Type> parameterTypes = Lists.newArrayList();
            for (Class<?> parameterType : method.getParameterTypes()) {
                parameterTypes.add(Type.getType(parameterType));
            }
            Type returnType = Type.getType(method.getReturnType());
            List<Advice> matchingAdvisors =
                    getMatchingAdvisors(method.getModifiers(), method.getName(), methodAnnotations,
                            parameterTypes, returnType, adviceMatchers);
            if (!matchingAdvisors.isEmpty()) {
                Method targetMethod = getTargetMethod(method, clazz);
                if (targetMethod != null) {
                    bridgeTargetAdvisors.put(targetMethod, matchingAdvisors);
                }
            }
        }
        for (Method method : clazz.getDeclaredMethods()) {
            if (method.isSynthetic()) {
                // don't add synthetic methods to the analyzed model
                continue;
            }
            int modifiers = method.getModifiers();
            List<String> methodAnnotations = Lists.newArrayList();
            for (Annotation annotation : method.getAnnotations()) {
                methodAnnotations.add(annotation.annotationType().getName());
            }
            List<Type> parameterTypes = Lists.newArrayList();
            for (Class<?> parameterType : method.getParameterTypes()) {
                parameterTypes.add(Type.getType(parameterType));
            }
            Type returnType = Type.getType(method.getReturnType());
            List<Advice> matchingAdvisors = getMatchingAdvisors(modifiers, method.getName(),
                    methodAnnotations, parameterTypes, returnType, adviceMatchers);
            List<Advice> extraAdvisors = bridgeTargetAdvisors.get(method);
            if (extraAdvisors != null) {
                matchingAdvisors.addAll(extraAdvisors);
            }
            ClassAnalyzer.sortAdvisors(matchingAdvisors);
            if (!matchingAdvisors.isEmpty()) {
                ImmutableAnalyzedMethod.Builder methodBuilder = ImmutableAnalyzedMethod.builder();
                methodBuilder.name(method.getName());
                for (Type parameterType : parameterTypes) {
                    methodBuilder.addParameterTypes(parameterType.getClassName());
                }
                methodBuilder.returnType(returnType.getClassName());
                methodBuilder.modifiers(modifiers);
                // FIXME re-build signature and set in AnalyzedMethod.signature()
                for (Class<?> exceptionType : method.getExceptionTypes()) {
                    methodBuilder.addExceptions(exceptionType.getName());
                }
                methodBuilder.addAllAdvisors(matchingAdvisors);
                classBuilder.addAnalyzedMethods(methodBuilder.build());
            }
            if (Modifier.isFinal(modifiers) && Modifier.isPublic(modifiers)) {
                ImmutablePublicFinalMethod.Builder publicFinalMethodBuilder =
                        ImmutablePublicFinalMethod.builder()
                                .name(method.getName());
                for (Type parameterType : parameterTypes) {
                    publicFinalMethodBuilder.addParameterTypes(parameterType.getClassName());
                }
                classBuilder.addPublicFinalMethods(publicFinalMethodBuilder.build());
            }
        }
        return classBuilder.build();
    }

    private static @Nullable Method getTargetMethod(Method bridgeMethod, Class<?> clazz) {
        List<Method> possibleTargetMethods = getPossibleTargetMethods(bridgeMethod, clazz);
        if (possibleTargetMethods.isEmpty()) {
            logger.warn("could not find any target for bridge method: {}", bridgeMethod);
        }
        if (possibleTargetMethods.size() == 1) {
            return possibleTargetMethods.get(0);
        }
        // FIXME what now, look at generic signatures?
        logger.warn("found more than one possible target for bridge method: {}", bridgeMethod);
        return null;
    }

    private static List<Method> getPossibleTargetMethods(Method bridgeMethod, Class<?> clazz) {
        List<Method> possibleTargetMethods = Lists.newArrayList();
        for (Method method : clazz.getDeclaredMethods()) {
            if (!method.getName().equals(bridgeMethod.getName())) {
                continue;
            }
            if (method.getParameterTypes().length != bridgeMethod.getParameterTypes().length) {
                continue;
            }
            possibleTargetMethods.add(method);
        }
        return possibleTargetMethods;
    }

    // important that this returns a mutable list
    private static List<Advice> getMatchingAdvisors(int access, String name,
            List<String> methodAnnotations, List<Type> parameterTypes, Type returnType,
            List<AdviceMatcher> adviceMatchers) {
        List<Advice> matchingAdvisors = Lists.newArrayList();
        for (AdviceMatcher adviceMatcher : adviceMatchers) {
            if (adviceMatcher.isMethodLevelMatch(name, methodAnnotations, parameterTypes,
                    returnType, access)) {
                matchingAdvisors.add(adviceMatcher.advice());
            }
        }
        return matchingAdvisors;
    }

    @Value.Immutable
    @Styles.AllParameters
    abstract static class ParseContext {
        abstract String className();
        abstract @Nullable CodeSource codeSource();
        // toString() is used in logger warning construction
        @Override
        public String toString() {
            CodeSource codeSource = codeSource();
            if (codeSource == null) {
                return className();
            } else {
                return className() + " (" + codeSource.getLocation() + ")";
            }
        }
    }
}
