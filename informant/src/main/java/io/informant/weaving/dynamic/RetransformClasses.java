/**
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
package io.informant.weaving.dynamic;

import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.List;

import com.google.common.collect.Iterables;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * @author Trask Stalnaker
 * @since 0.5
 */
public class RetransformClasses {

    private static final Logger logger = LoggerFactory.getLogger(RetransformClasses.class);

    public static void addRetransformingTransformer(Instrumentation instrumentation,
            ClassFileTransformer transformer) {
        try {
            Method addTransformerMethod = Instrumentation.class.getMethod("addTransformer",
                    ClassFileTransformer.class, boolean.class);
            addTransformerMethod.invoke(instrumentation, transformer, true);
        } catch (SecurityException e) {
            logger.warn(e.getMessage(), e);
        } catch (NoSuchMethodException e) {
            logger.warn(e.getMessage(), e);
        } catch (IllegalArgumentException e) {
            logger.error(e.getMessage(), e);
        } catch (IllegalAccessException e) {
            logger.warn(e.getMessage(), e);
        } catch (InvocationTargetException e) {
            logger.warn(e.getMessage(), e);
        }
    }

    public static boolean isRetransformClassesSupported(Instrumentation instrumentation) {
        if (System.getProperty("java.version").startsWith("1.5")) {
            return false;
        }
        try {
            Method retransformClassesMethod = Instrumentation.class.getMethod(
                    "isRetransformClassesSupported");
            return (Boolean) retransformClassesMethod.invoke(instrumentation);
        } catch (SecurityException e) {
            logger.warn(e.getMessage(), e);
        } catch (NoSuchMethodException e) {
            logger.warn(e.getMessage(), e);
        } catch (IllegalArgumentException e) {
            logger.error(e.getMessage(), e);
        } catch (IllegalAccessException e) {
            logger.warn(e.getMessage(), e);
        } catch (InvocationTargetException e) {
            logger.warn(e.getMessage(), e);
        }
        return false;
    }

    public static void retransformClasses(Instrumentation instrumentation, List<Class<?>> classes) {
        try {
            Method retransformClassesMethod = Instrumentation.class.getMethod("retransformClasses",
                    Class[].class);
            retransformClassesMethod.invoke(instrumentation,
                    (Object) Iterables.toArray(classes, Class.class));
        } catch (SecurityException e) {
            logger.warn(e.getMessage(), e);
        } catch (NoSuchMethodException e) {
            logger.warn(e.getMessage(), e);
        } catch (IllegalArgumentException e) {
            logger.error(e.getMessage(), e);
        } catch (IllegalAccessException e) {
            logger.warn(e.getMessage(), e);
        } catch (InvocationTargetException e) {
            logger.warn(e.getMessage(), e);
        }
    }

}
