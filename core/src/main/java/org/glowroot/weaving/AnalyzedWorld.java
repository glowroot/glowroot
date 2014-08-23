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

import java.io.IOException;
import java.lang.reflect.Method;
import java.net.URL;
import java.security.CodeSource;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import com.google.common.base.MoreObjects;
import com.google.common.base.Supplier;
import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;
import com.google.common.io.Resources;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Type;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.glowroot.common.Reflections;
import org.glowroot.common.Reflections.ReflectiveException;
import org.glowroot.markers.Singleton;

import static com.google.common.base.Preconditions.checkNotNull;
import static org.objectweb.asm.Opcodes.ACC_SYNTHETIC;

/**
 * @author Trask Stalnaker
 * @since 0.5
 */
@Singleton
public class AnalyzedWorld {

    private static final Logger logger = LoggerFactory.getLogger(AnalyzedWorld.class);

    private static final Method findLoadedClassMethod;

    static {
        try {
            findLoadedClassMethod = Reflections.getDeclaredMethod(ClassLoader.class,
                    "findLoadedClass", new Class[] {String.class});
        } catch (ReflectiveException e) {
            // unrecoverable error
            throw new AssertionError(e);
        }
    }

    // weak keys to prevent retention of class loaders
    // it's important that the weak keys point directly to the class loaders themselves (as opposed
    // to through another instance, e.g. Optional<ClassLoader>) so that the keys won't be cleared
    // while their associated class loaders are still being used
    //
    // note, not using nested loading cache since the nested loading cache maintains a strong
    // reference to the class loader
    private final LoadingCache<ClassLoader, ConcurrentMap<String, AnalyzedClass>> world =
            CacheBuilder.newBuilder().weakKeys()
                    .build(new CacheLoader<ClassLoader, ConcurrentMap<String, AnalyzedClass>>() {
                        @Override
                        public ConcurrentMap<String, AnalyzedClass> load(ClassLoader loader) {
                            // intentionally avoiding Maps.newConcurrentMap() since it uses many
                            // additional classes that must then be pre-initialized since this
                            // is called from inside ClassFileTransformer.transform()
                            // (see PreInitializeClasses)
                            return new ConcurrentHashMap<String, AnalyzedClass>();
                        }
                    });

    // the analyzed classes for the bootstrap class loader (null) have to be stored separately since
    // LoadingCache doesn't accept null keys, and using an Optional<ClassLoader> for the key makes
    // the weakness on the Optional instance which is not strongly referenced from anywhere and
    // therefore the keys will most likely be cleared while their class loaders are still being used
    //
    // intentionally avoiding Maps.newConcurrentMap() for the same reason as above
    private final ConcurrentMap<String, AnalyzedClass> bootstrapLoaderWorld =
            new ConcurrentHashMap<String, AnalyzedClass>();

    private final Supplier<ImmutableList<Advice>> advisors;
    private final ImmutableList<MixinType> mixinTypes;

    @Nullable
    private final ExtraBootResourceFinder extraBootResourceFinder;

    private final AnalyzedClass javaLangObjectAnalyzedClass;

    public AnalyzedWorld(Supplier<ImmutableList<Advice>> advisors, List<MixinType> mixinTypes,
            @Nullable ExtraBootResourceFinder extraBootResourceFinder) {
        this.advisors = advisors;
        this.mixinTypes = ImmutableList.copyOf(mixinTypes);
        this.extraBootResourceFinder = extraBootResourceFinder;
        javaLangObjectAnalyzedClass = createAnalyzedClassPlanC(Object.class, advisors.get());
    }

    public List<Class<?>> getClassesWithReweavableAdvice() {
        List<Class<?>> classes = Lists.newArrayList();
        for (Entry<ClassLoader, ConcurrentMap<String, AnalyzedClass>> outerEntry : world
                .asMap().entrySet()) {
            for (Entry<String, AnalyzedClass> innerEntry : outerEntry.getValue().entrySet()) {
                if (innerEntry.getValue().hasReweavableAdvice()) {
                    try {
                        classes.add(Class.forName(innerEntry.getKey(), false, outerEntry.getKey()));
                    } catch (ClassNotFoundException e) {
                        logger.warn(e.getMessage(), e);
                    }
                }
            }
        }
        for (Entry<String, AnalyzedClass> entry : bootstrapLoaderWorld.entrySet()) {
            if (entry.getValue().hasReweavableAdvice()) {
                try {
                    classes.add(Class.forName(entry.getKey(), false, null));
                } catch (ClassNotFoundException e) {
                    logger.warn(e.getMessage(), e);
                }
            }
        }
        return classes;
    }

    public void clearClassesBeforeReweaving(Iterable<Class<?>> classes) {
        for (Class<?> clazz : classes) {
            ClassLoader loader = clazz.getClassLoader();
            if (loader == null) {
                bootstrapLoaderWorld.remove(clazz.getName());
            } else {
                world.getUnchecked(loader).remove(clazz.getName());
            }
        }
    }

    public List<Class<?>> getExistingSubClasses(Set<String> rootClassNames) {
        List<Class<?>> classes = Lists.newArrayList();
        for (ClassLoader loader : world.asMap().keySet()) {
            classes.addAll(getExistingSubClasses(rootClassNames, loader));
        }
        classes.addAll(getExistingSubClasses(rootClassNames, null));
        return classes;
    }

    public List<AnalyzedClass> getAnalyzedClasses(String className) {
        List<AnalyzedClass> analyzedClasses = Lists.newArrayList();
        AnalyzedClass analyzedClass = bootstrapLoaderWorld.get(className);
        if (analyzedClass != null) {
            analyzedClasses.add(analyzedClass);
        }
        for (Map<String, AnalyzedClass> loaderAnalyzedClasses : world.asMap().values()) {
            analyzedClass = loaderAnalyzedClasses.get(className);
            if (analyzedClass != null) {
                analyzedClasses.add(analyzedClass);
            }
        }
        return analyzedClasses;
    }

    public ImmutableList<ClassLoader> getClassLoaders() {
        return ImmutableList.copyOf(world.asMap().keySet());
    }

    void add(AnalyzedClass analyzedClass, @Nullable ClassLoader loader) {
        ConcurrentMap<String, AnalyzedClass> loaderAnalyzedClasses = getAnalyzedClasses(loader);
        String className = analyzedClass.getName();
        loaderAnalyzedClasses.put(className, analyzedClass);
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

    AnalyzedClass getAnalyzedClass(String className, @Nullable ClassLoader loader)
            throws ClassNotFoundException, IOException {
        return getOrCreateAnalyzedClass(className, loader);
    }

    AnalyzedClass getJavaLangObjectAnalyzedClass() {
        return javaLangObjectAnalyzedClass;
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
        String superName = analyzedClass.getSuperName();
        if (superName != null && !superName.equals("java.lang.Object")) {
            superTypes.addAll(getSuperClasses(superName, loader, parseContext));
        }
        for (String interfaceName : analyzedClass.getInterfaceNames()) {
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
            analyzedClass = createAnalyzedClass(className, analyzedClassLoader);
            AnalyzedClass existingAnalyzedClass =
                    loaderAnalyzedClasses.putIfAbsent(className, analyzedClass);
            if (existingAnalyzedClass != null) {
                // (rare) concurrent AnalyzedClass creation, use the one that made it into the map
                analyzedClass = existingAnalyzedClass;
            }
        }
        return analyzedClass;
    }

    private List<Class<?>> getExistingSubClasses(Set<String> rootClassNames,
            @Nullable ClassLoader loader) {
        List<Class<?>> classes = Lists.newArrayList();
        for (AnalyzedClass analyzedClass : getAnalyzedClasses(loader).values()) {
            if (isSubClass(analyzedClass, rootClassNames, loader)) {
                try {
                    classes.add(Class.forName(analyzedClass.getName(), false, loader));
                } catch (ClassNotFoundException e) {
                    logger.warn(e.getMessage(), e);
                }
            }
        }
        return classes;
    }

    private boolean isSubClass(AnalyzedClass analyzedClass, Set<String> rootClassNames,
            @Nullable ClassLoader loader) {
        List<String> superClassNames = getExistingClassHierarchy(analyzedClass, loader);
        for (String superClassName : superClassNames) {
            if (rootClassNames.contains(superClassName)) {
                return true;
            }
        }
        return false;
    }

    private List<String> getExistingClassHierarchy(AnalyzedClass analyzedClass,
            @Nullable ClassLoader loader) {
        List<String> superClasses = Lists.newArrayList(analyzedClass.getName());
        String superName = analyzedClass.getSuperName();
        if (superName != null && !superName.equals("java.lang.Object")) {
            AnalyzedClass superAnalyzedClass = getExistingAnalyzedClass(superName, loader);
            if (superAnalyzedClass != null) {
                superClasses.addAll(getExistingClassHierarchy(superAnalyzedClass, loader));
            }
        }
        for (String interfaceName : analyzedClass.getInterfaceNames()) {
            AnalyzedClass interfaceAnalyzedClass = getExistingAnalyzedClass(interfaceName, loader);
            if (interfaceAnalyzedClass != null) {
                superClasses.addAll(getExistingClassHierarchy(interfaceAnalyzedClass, loader));
            }
        }
        return superClasses;
    }

    @Nullable
    private AnalyzedClass getExistingAnalyzedClass(String className, @Nullable ClassLoader loader) {
        ClassLoader analyzedLoader = getAnalyzedLoader(className, loader);
        if (analyzedLoader == null) {
            return bootstrapLoaderWorld.get(className);
        }
        return world.getUnchecked(analyzedLoader).get(className);
    }

    @Nullable
    private ClassLoader getAnalyzedLoader(String className, @Nullable ClassLoader loader) {
        if (loader == null) {
            return null;
        }
        // can't call Class.forName() since that bypasses ClassFileTransformer.transform() if the
        // class hasn't already been loaded, so instead, call the package protected
        // ClassLoader.findLoadClass()
        Class<?> clazz = null;
        try {
            clazz = (Class<?>) Reflections.invoke(findLoadedClassMethod, loader, className);
        } catch (ReflectiveException e) {
            logger.error(e.getMessage(), e);
        }
        ClassLoader analyzedLoader = loader;
        if (clazz != null) {
            // this class has already been loaded, so the corresponding analyzedType should already
            // be in the cache under its class loader
            //
            // this helps in cases where the .class files are not available via
            // ClassLoader.getResource(), as well as being a good optimization in other cases
            analyzedLoader = clazz.getClassLoader();
        }
        return analyzedLoader;
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
                ClassLoader tempLoader = loader;
                while (tempLoader != null) {
                    ClassLoader parentLoader = tempLoader.getParent();
                    URL parentLoaderUrl;
                    if (parentLoader == null) {
                        parentLoaderUrl = ClassLoader.getSystemResource(path);
                    } else {
                        parentLoaderUrl = parentLoader.getResource(path);
                    }
                    if (url.equals(parentLoaderUrl)) {
                        // reuse parent loader's AnalyzedClass if available
                        // this saves time here, and reduces memory footprint of AnalyzedWorld
                        // which can be very noticeable when lots of ClassLoaders, e.g. groovy
                        AnalyzedClass parentLoaderAnalyzedClass =
                                getAnalyzedClasses(parentLoader).get(className);
                        if (parentLoaderAnalyzedClass != null) {
                            return parentLoaderAnalyzedClass;
                        }
                    }
                    tempLoader = parentLoader;
                }
            }
        }
        if (url == null && extraBootResourceFinder != null) {
            url = extraBootResourceFinder.findResource(path);
        }
        if (url == null) {
            // what follows is just a best attempt in the sort-of-rare case when a custom class
            // loader does not expose .class file contents via getResource(), e.g.
            // org.codehaus.groovy.runtime.callsite.CallSiteClassLoader
            return createAnalyzedClassPlanB(className, loader);
        }
        AnalyzingClassVisitor cv =
                new AnalyzingClassVisitor(advisors.get(), mixinTypes, loader, this, null);
        byte[] bytes = Resources.toByteArray(url);
        ClassReader cr = new ClassReader(bytes);
        cr.accept(cv, 0);
        AnalyzedClass analyzedClass = cv.getAnalyzedClass();
        checkNotNull(analyzedClass); // analyzedClass is non-null after visiting the class
        return analyzedClass;
    }

    // plan B covers some class loaders like
    // org.codehaus.groovy.runtime.callsite.CallSiteClassLoader that delegate loadClass() to some
    // other loader where the type may have already been loaded
    private AnalyzedClass createAnalyzedClassPlanB(String className, @Nullable ClassLoader loader)
            throws ClassNotFoundException {
        Class<?> clazz = Class.forName(className, false, loader);
        AnalyzedClass analyzedClass = getAnalyzedClasses(clazz.getClassLoader()).get(className);
        if (analyzedClass == null) {
            // a class was loaded by Class.forName() above that was not previously loaded which
            // means weaving was bypassed since ClassFileTransformer.transform() is not re-entrant

            // TODO inspect the class after loading to see if any advice even applies to it, if not
            // then no need to log warning
            logger.warn("could not find resource {}.class in class loader {}, so the class"
                    + " had to be loaded using Class.forName() during weaving of one of its"
                    + " subclasses, which means it was not woven itself since weaving is not"
                    + " re-entrant", ClassNames.toInternalName(clazz.getName()), loader);
            return createAnalyzedClassPlanC(clazz, advisors.get());
        } else {
            // the type was previously loaded so weaving was not bypassed, yay!
            return analyzedClass;
        }
    }

    private ConcurrentMap<String, AnalyzedClass> getAnalyzedClasses(@Nullable ClassLoader loader) {
        if (loader == null) {
            return bootstrapLoaderWorld;
        } else {
            return world.getUnchecked(loader);
        }
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
                .add("world", world)
                .add("bootstrapLoaderWorld", bootstrapLoaderWorld)
                .toString();
    }

    // now that the type has been loaded anyways, build the analyzed class via reflection
    private static AnalyzedClass createAnalyzedClassPlanC(Class<?> clazz,
            ImmutableList<Advice> advisors) {
        List<AdviceMatcher> adviceMatchers =
                AdviceMatcher.getAdviceMatchers(clazz.getName(), advisors);
        List<AnalyzedMethod> analyzedMethods = Lists.newArrayList();
        for (Method method : clazz.getDeclaredMethods()) {
            if (method.isSynthetic()) {
                // don't add synthetic methods to the analyzed model
                continue;
            }
            List<Type> parameterTypes = Lists.newArrayList();
            for (Class<?> parameterType : method.getParameterTypes()) {
                parameterTypes.add(Type.getType(parameterType));
            }
            Type returnType = Type.getType(method.getReturnType());
            List<Advice> matchingAdvisors = getMatchingAdvisors(method.getModifiers(),
                    method.getName(), parameterTypes, returnType, adviceMatchers);
            if (!matchingAdvisors.isEmpty() && (method.getModifiers() & ACC_SYNTHETIC) == 0) {
                // don't add synthetic methods to the analyzed model
                List<String> exceptions = Lists.newArrayList();
                for (Class<?> exceptionType : method.getExceptionTypes()) {
                    exceptions.add(Type.getInternalName(exceptionType));
                }
                AnalyzedMethod analyzedMethod = AnalyzedMethod.from(method.getName(),
                        parameterTypes,
                        returnType, method.getModifiers(), null, exceptions, matchingAdvisors);
                analyzedMethods.add(analyzedMethod);
            }
        }
        List<String> interfaceNames = Lists.newArrayList();
        for (Class<?> interfaceClass : clazz.getInterfaces()) {
            interfaceNames.add(interfaceClass.getName());
        }
        Class<?> superClass = clazz.getSuperclass();
        String superName = superClass == null ? null : superClass.getName();
        return AnalyzedClass.from(clazz.getModifiers(), clazz.getName(), superName, interfaceNames,
                analyzedMethods);
    }

    private static List<Advice> getMatchingAdvisors(int access, String name,
            List<Type> parameterTypes, Type returnType, List<AdviceMatcher> adviceMatchers) {
        List<Advice> matchingAdvisors = Lists.newArrayList();
        for (AdviceMatcher adviceMatcher : adviceMatchers) {
            if (adviceMatcher.isMethodLevelMatch(name, parameterTypes, returnType, access)) {
                matchingAdvisors.add(adviceMatcher.getAdvice());
            }
        }
        return matchingAdvisors;
    }

    static class ParseContext {
        private final String className;
        @Nullable
        private final CodeSource codeSource;
        ParseContext(String className, @Nullable CodeSource codeSource) {
            this.codeSource = codeSource;
            this.className = className;
        }
        // toString() is used in logger warning construction
        @Override
        public String toString() {
            if (codeSource == null) {
                return className;
            } else {
                return className + " (" + codeSource.getLocation() + ")";
            }
        }
    }
}
