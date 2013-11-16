/*
 * Copyright 2013 the original author or authors.
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
package org.glowroot.jvm;

import java.io.File;
import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.List;

import checkers.igj.quals.Immutable;
import checkers.nullness.quals.Nullable;
import com.google.common.collect.Iterables;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.glowroot.jvm.OptionalService.OptionalServiceFactory;
import org.glowroot.jvm.OptionalService.OptionalServiceFactoryException;
import org.glowroot.jvm.OptionalService.OptionalServiceFactoryHelper;

/**
 * @author Trask Stalnaker
 * @since 0.5
 */
@Immutable
public class Jdk6 {

    private static final Logger logger = LoggerFactory.getLogger(Jdk6.class);

    // java.lang.instrument.Instrumentation
    private final Method addTransformerTwoArgMethod;
    private final Method isRetransformClassesSupportedMethod;
    private final Method retransformClassesMethod;

    // java.io.File
    private final Method getFreeSpaceMethod;

    // javax.tools.ToolProvider
    @Nullable
    private final ClassLoader systemToolClassLoader;

    private Jdk6(Method addTransformerTwoArgMethod, Method isRetransformClassesSupportedMethod,
            Method retransformClassesMethod, Method getFreeSpaceMethod,
            @Nullable ClassLoader systemToolClassLoader) {
        this.addTransformerTwoArgMethod = addTransformerTwoArgMethod;
        this.isRetransformClassesSupportedMethod = isRetransformClassesSupportedMethod;
        this.retransformClassesMethod = retransformClassesMethod;
        this.getFreeSpaceMethod = getFreeSpaceMethod;
        this.systemToolClassLoader = systemToolClassLoader;
    }

    public boolean isRetransformClassesSupported(Instrumentation instrumentation) {
        MethodWithNonNullReturn method =
                new MethodWithNonNullReturn(isRetransformClassesSupportedMethod, false);
        return (Boolean) method.invoke(instrumentation);
    }

    public void addRetransformingTransformer(Instrumentation instrumentation,
            ClassFileTransformer transformer) {
        try {
            addTransformerTwoArgMethod.invoke(instrumentation, transformer, true);
        } catch (IllegalArgumentException e) {
            logger.error(e.getMessage(), e);
        } catch (IllegalAccessException e) {
            logger.error(e.getMessage(), e);
        } catch (InvocationTargetException e) {
            logger.error(e.getMessage(), e);
        }
    }

    public void retransformClasses(Instrumentation instrumentation, List<Class<?>> classes) {
        try {
            retransformClassesMethod.invoke(instrumentation,
                    (Object) Iterables.toArray(classes, Class.class));
        } catch (IllegalArgumentException e) {
            logger.error(e.getMessage(), e);
        } catch (IllegalAccessException e) {
            logger.error(e.getMessage(), e);
        } catch (InvocationTargetException e) {
            logger.error(e.getMessage(), e);
        }
    }

    public long getFreeSpace(File file) {
        MethodWithNonNullReturn method = new MethodWithNonNullReturn(getFreeSpaceMethod, -1L);
        return (Long) method.invoke(file);
    }

    @Nullable
    ClassLoader getSystemToolClassLoader() {
        return systemToolClassLoader;
    }

    static class Factory implements OptionalServiceFactory<Jdk6> {
        public Jdk6 create() throws OptionalServiceFactoryException {
            if (System.getProperty("java.version").startsWith("1.5")) {
                throw new OptionalServiceFactoryException("Java version is 1.5");
            }
            // java.lang.instrument.Instrumentation
            Method addTransformerTwoArgMethod = OptionalServiceFactoryHelper.getMethod(
                    Instrumentation.class, "addTransformer", ClassFileTransformer.class,
                    boolean.class);
            Method isRetransformClassesSupportedMethod = OptionalServiceFactoryHelper.getMethod(
                    Instrumentation.class, "isRetransformClassesSupported");
            Method retransformClassesMethod = OptionalServiceFactoryHelper.getMethod(
                    Instrumentation.class, "retransformClasses", Class[].class);
            // java.io.File
            Method getFreeSpaceMethod =
                    OptionalServiceFactoryHelper.getDeclaredMethod(File.class, "getFreeSpace");
            Class<?> toolProviderClass =
                    OptionalServiceFactoryHelper.classForName("javax.tools.ToolProvider");
            Method getSystemToolClassLoaderMethod = OptionalServiceFactoryHelper.getMethod(
                    toolProviderClass, "getSystemToolClassLoader");
            // javax.tools.ToolProvider
            ClassLoader systemToolClassLoader = (ClassLoader) OptionalServiceFactoryHelper.invoke(
                    getSystemToolClassLoaderMethod, null);
            return new Jdk6(addTransformerTwoArgMethod, isRetransformClassesSupportedMethod,
                    retransformClassesMethod, getFreeSpaceMethod, systemToolClassLoader);
        }
    }
}
