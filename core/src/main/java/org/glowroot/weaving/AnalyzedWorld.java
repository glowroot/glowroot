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
package org.glowroot.weaving;

import java.io.IOException;
import java.lang.reflect.Method;
import java.net.URL;
import java.security.CodeSource;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import javax.annotation.Nullable;

import com.google.common.base.Supplier;
import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;
import com.google.common.io.Resources;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Type;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.glowroot.common.Reflections;
import org.glowroot.common.Reflections.ReflectiveException;
import org.glowroot.weaving.WeavingClassVisitor.ShortCircuitException;

import static com.google.common.base.Preconditions.checkNotNull;

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

    private final Supplier<List<Advice>> advisors;
    private final ImmutableList<MixinType> mixinTypes;

    private final @Nullable ExtraBootResourceFinder extraBootResourceFinder;

    public AnalyzedWorld(Supplier<List<Advice>> advisors, List<MixinType> mixinTypes,
            @Nullable ExtraBootResourceFinder extraBootResourceFinder) {
        this.advisors = advisors;
        this.mixinTypes = ImmutableList.copyOf(mixinTypes);
        this.extraBootResourceFinder = extraBootResourceFinder;
    }

    public List<Class<?>> getClassesWithReweavableAdvice(boolean remove) {
        List<Class<?>> classes = Lists.newArrayList();
        for (ClassLoader loader : world.asMap().keySet()) {
            classes.addAll(getClassesWithReweavableAdvice(loader, remove));
        }
        classes.addAll(getClassesWithReweavableAdvice(null, remove));
        return classes;
    }

    public void removeClasses(List<Class<?>> classes) {
        for (Map<String, AnalyzedClass> map : world.asMap().values()) {
            for (Class<?> clazz : classes) {
                map.remove(clazz.getName());
            }
        }
        for (Class<?> clazz : classes) {
            bootstrapLoaderWorld.remove(clazz.getName());
        }
    }

    public ImmutableList<ClassLoader> getClassLoaders() {
        return ImmutableList.copyOf(world.asMap().keySet());
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

    AnalyzedClass getAnalyzedClass(String className, @Nullable ClassLoader loader)
            throws ClassNotFoundException, IOException {
        return getOrCreateAnalyzedClass(className, loader);
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

    private AnalyzedClass putAnalyzedClass(
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

    private @Nullable ClassLoader getAnalyzedLoader(String className,
            @Nullable ClassLoader loader) {
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
            // this class has already been loaded, so the corresponding analyzedClass should already
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
                AnalyzedClass parentLoaderAnalyzedClass =
                        tryToReuseFromParentLoader(className, loader, path, url);
                if (parentLoaderAnalyzedClass != null) {
                    return parentLoaderAnalyzedClass;
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
        try {
            cr.accept(cv, ClassReader.SKIP_CODE);
        } catch (ShortCircuitException e) {
            // this is ok, in either case analyzed class is now available
        }
        AnalyzedClass analyzedClass = cv.getAnalyzedClass();
        checkNotNull(analyzedClass); // analyzedClass is non-null after visiting the class
        return analyzedClass;
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
        if (analyzedClass == null) {
            // a class was loaded by Class.forName() above that was not previously loaded which
            // means weaving was bypassed since ClassFileTransformer.transform() is not re-entrant
            analyzedClass = createAnalyzedClassPlanC(clazz, advisors.get());
            for (AnalyzedMethod analyzedMethod : analyzedClass.analyzedMethods()) {
                if (!analyzedMethod.advisors().isEmpty()) {
                    logger.warn("{} was not woven with requested advice (it was first encountered"
                            + " during the weaving of one of its {} and the resource {}.class"
                            + " could not be found in class loader {}, so {} had to be explicitly"
                            + " loaded using Class.forName() in the middle of weaving the {},"
                            + " which means it was not woven itself since weaving is not"
                            + " re-entrant)", clazz.getName(),
                            analyzedClass.isInterface() ? "implementations" : "subclasses",
                            ClassNames.toInternalName(clazz.getName()), loader, clazz.getName(),
                            analyzedClass.isInterface() ? "implementation" : "subclass");
                    break;
                }
            }
        }
        return analyzedClass;
    }

    private ConcurrentMap<String, AnalyzedClass> getAnalyzedClasses(@Nullable ClassLoader loader) {
        if (loader == null) {
            return bootstrapLoaderWorld;
        } else {
            return world.getUnchecked(loader);
        }
    }

    // now that the type has been loaded anyways, build the analyzed class via reflection
    private static AnalyzedClass createAnalyzedClassPlanC(Class<?> clazz, List<Advice> advisors) {
        ImmutableAnalyzedClass.Builder classBuilder = ImmutableAnalyzedClass.builder();
        classBuilder.modifiers(clazz.getModifiers());
        classBuilder.name(clazz.getName());
        Class<?> superClass = clazz.getSuperclass();
        String superName = superClass == null ? null : superClass.getName();
        classBuilder.superName(superName);
        for (Class<?> interfaceClass : clazz.getInterfaces()) {
            classBuilder.addInterfaceNames(interfaceClass.getName());
        }
        List<AdviceMatcher> adviceMatchers =
                AdviceMatcher.getAdviceMatchers(clazz.getName(), advisors);
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
            if (!matchingAdvisors.isEmpty()) {
                ImmutableAnalyzedMethod.Builder methodBuilder = ImmutableAnalyzedMethod.builder();
                methodBuilder.name(method.getName());
                for (Type parameterType : parameterTypes) {
                    methodBuilder.addParameterTypes(parameterType.getClassName());
                }
                methodBuilder.returnType(returnType.getClassName());
                methodBuilder.modifiers(method.getModifiers());
                for (Class<?> exceptionType : method.getExceptionTypes()) {
                    methodBuilder.addExceptions(exceptionType.getName());
                }
                methodBuilder.addAllAdvisors(matchingAdvisors);
                classBuilder.addAnalyzedMethods(methodBuilder.build());
            }
        }
        return classBuilder.build();
    }

    private static List<Advice> getMatchingAdvisors(int access, String name,
            List<Type> parameterTypes, Type returnType, List<AdviceMatcher> adviceMatchers) {
        List<Advice> matchingAdvisors = Lists.newArrayList();
        for (AdviceMatcher adviceMatcher : adviceMatchers) {
            if (adviceMatcher.isMethodLevelMatch(name, parameterTypes, returnType, access)) {
                matchingAdvisors.add(adviceMatcher.advice());
            }
        }
        return matchingAdvisors;
    }

    static class ParseContext {
        private final String className;
        private final @Nullable CodeSource codeSource;
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
