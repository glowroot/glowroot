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
package io.informant.jvm;

import java.io.File;
import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.List;

import checkers.nullness.quals.Nullable;
import com.google.common.collect.Iterables;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.informant.markers.Static;

import static io.informant.common.Nullness.assertNonNull;

/**
 * @author Trask Stalnaker
 * @since 0.5
 */
@Static
public class JDK6 {

    private static final Logger logger = LoggerFactory.getLogger(JDK6.class);

    // java.lang.instrument.Instrumentation
    @Nullable
    private static final Method addTransformerTwoArgMethod;
    @Nullable
    private static final Method isRetransformClassesSupportedMethod;
    @Nullable
    private static final Method retransformClassesMethod;

    // java.io.File
    @Nullable
    private static final Method getFreeSpaceMethod;

    // javax.tools.ToolProvider
    @Nullable
    private static final ClassLoader systemToolClassLoader;

    private static final boolean supported;
    private static volatile String unsupportedReason;

    static {
        if (System.getProperty("java.version").startsWith("1.5")) {
            addTransformerTwoArgMethod = null;
            isRetransformClassesSupportedMethod = null;
            retransformClassesMethod = null;
            getFreeSpaceMethod = null;
            systemToolClassLoader = null;
            supported = false;
            unsupportedReason = "JDK version is 1.5";
        } else {
            // java.lang.instrument.Instrumentation
            addTransformerTwoArgMethod = initAddTransformerMethod();
            isRetransformClassesSupportedMethod = initIsRetransformClassesSupportedMethod();
            retransformClassesMethod = initRetransformClassesMethod();
            // java.io.File
            getFreeSpaceMethod = initGetFreeSpaceMethod();
            // javax.tools.ToolProvider
            systemToolClassLoader = initSystemToolClassLoader();
            // JDK6 is only available if all methods are available
            if (addTransformerTwoArgMethod != null && isRetransformClassesSupportedMethod != null
                    && retransformClassesMethod != null && getFreeSpaceMethod != null) {
                supported = true;
                unsupportedReason = "";
            } else {
                supported = false;
                unsupportedReason = "Unsupported due to error, see Informant log";
            }
        }
    }

    private JDK6() {}

    public static boolean isSupported() {
        return supported;
    }

    public static String getUnsupportedReason() {
        return unsupportedReason;
    }

    public static boolean isRetransformClassesSupported(Instrumentation instrumentation) {
        try {
            Boolean value = (Boolean) isRetransformClassesSupportedMethod.invoke(instrumentation);
            assertNonNull(value, "Instrumentation.isRetransformClassesSupported() returned null");
            return value;
        } catch (IllegalArgumentException e) {
            logger.error(e.getMessage(), e);
            return false;
        } catch (IllegalAccessException e) {
            logger.error(e.getMessage(), e);
            return false;
        } catch (InvocationTargetException e) {
            logger.error(e.getMessage(), e);
            return false;
        }
    }

    public static void addRetransformingTransformer(Instrumentation instrumentation,
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

    public static void retransformClasses(Instrumentation instrumentation, List<Class<?>> classes) {
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

    public static long getFreeSpace(File file) {
        try {
            return (Long) getFreeSpaceMethod.invoke(file);
        } catch (IllegalArgumentException e) {
            logger.error(e.getMessage(), e);
            return -1;
        } catch (IllegalAccessException e) {
            logger.error(e.getMessage(), e);
            return -1;
        } catch (InvocationTargetException e) {
            logger.error(e.getMessage(), e);
            return -1;
        }
    }

    @Nullable
    public static ClassLoader getSystemToolClassLoader() {
        return systemToolClassLoader;
    }

    @Nullable
    private static Method initAddTransformerMethod() {
        try {
            return Instrumentation.class.getMethod("addTransformer", ClassFileTransformer.class,
                    boolean.class);
        } catch (SecurityException e) {
            logger.error(e.getMessage(), e);
            return null;
        } catch (NoSuchMethodException e) {
            logger.error(e.getMessage(), e);
            return null;
        }
    }

    @Nullable
    private static Method initIsRetransformClassesSupportedMethod() {
        try {
            return Instrumentation.class.getMethod("isRetransformClassesSupported");
        } catch (SecurityException e) {
            logger.error(e.getMessage(), e);
            return null;
        } catch (NoSuchMethodException e) {
            logger.error(e.getMessage(), e);
            return null;
        }
    }

    @Nullable
    private static Method initRetransformClassesMethod() {
        try {
            return Instrumentation.class.getMethod("retransformClasses", Class[].class);
        } catch (SecurityException e) {
            logger.error(e.getMessage(), e);
            return null;
        } catch (NoSuchMethodException e) {
            logger.error(e.getMessage(), e);
            return null;
        }
    }

    @Nullable
    private static Method initGetFreeSpaceMethod() {
        try {
            return File.class.getDeclaredMethod("getFreeSpace");
        } catch (SecurityException e) {
            logger.error(e.getMessage(), e);
            return null;
        } catch (NoSuchMethodException e) {
            logger.error(e.getMessage(), e);
            return null;
        }
    }

    @Nullable
    private static ClassLoader initSystemToolClassLoader() {
        Class<?> toolProviderClass;
        try {
            toolProviderClass = Class.forName("javax.tools.ToolProvider");
        } catch (ClassNotFoundException e) {
            logger.error(e.getMessage(), e);
            return null;
        }
        Method getSystemToolClassLoaderMethod;
        try {
            getSystemToolClassLoaderMethod =
                    toolProviderClass.getMethod("getSystemToolClassLoader");
        } catch (SecurityException e) {
            logger.error(e.getMessage(), e);
            return null;
        } catch (NoSuchMethodException e) {
            logger.error(e.getMessage(), e);
            return null;
        }
        try {
            return (ClassLoader) getSystemToolClassLoaderMethod.invoke(null);
        } catch (IllegalArgumentException e) {
            logger.error(e.getMessage(), e);
            return null;
        } catch (IllegalAccessException e) {
            logger.error(e.getMessage(), e);
            return null;
        } catch (InvocationTargetException e) {
            logger.error(e.getMessage(), e);
            return null;
        }
    }
}
