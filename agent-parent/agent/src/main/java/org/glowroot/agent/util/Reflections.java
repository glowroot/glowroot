/*
 * Copyright 2013-2016 the original author or authors.
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
package org.glowroot.agent.util;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class Reflections {

    private static final Logger logger = LoggerFactory.getLogger(Reflections.class);

    private Reflections() {}

    public static Method getAnyMethod(Class<?> clazz, String name, Class<?>... parameterTypes)
            throws Exception {
        try {
            Method method = clazz.getMethod(name, parameterTypes);
            // even though getMethod() above only returns public method, still need to
            // setAccessible() in case class is package-private
            method.setAccessible(true);
            return method;
        } catch (NoSuchMethodException e) {
            // log exception at trace level
            logger.trace(e.getMessage(), e);
            return getAnyDeclaredMethod(clazz, name, parameterTypes);
        } catch (NoClassDefFoundError e) {
            // NoClassDefFoundError is thrown if any method signature in clazz references a missing
            // class (see MissingOptionalDependenciesReflectionTest.java)
            // seems best to treat same as NoSuchMethodException

            // log exception at trace level
            logger.trace(e.getMessage(), e);
            return getAnyDeclaredMethod(clazz, name, parameterTypes);
        }
    }

    public static Field getAnyField(Class<?> clazz, String fieldName) throws Exception {
        try {
            Field field = clazz.getField(fieldName);
            field.setAccessible(true);
            return field;
        } catch (NoSuchFieldException e) {
            // log exception at trace level
            logger.trace(e.getMessage(), e);
            return getAnyDeclaredField(clazz, fieldName);
        }
    }

    private static Method getAnyDeclaredMethod(Class<?> clazz, String name,
            Class<?>... parameterTypes) throws Exception {
        try {
            Method method = clazz.getDeclaredMethod(name, parameterTypes);
            method.setAccessible(true);
            return method;
        } catch (NoSuchMethodException e) {
            // log exception at trace level
            logger.trace(e.getMessage(), e);
            Class<?> superClass = clazz.getSuperclass();
            if (superClass == null) {
                throw e;
            }
            return getAnyDeclaredMethod(superClass, name, parameterTypes);
        } catch (NoClassDefFoundError e) {
            // NoClassDefFoundError is thrown if any method signature in clazz references a missing
            // class (see MissingOptionalDependenciesReflectionTest.java)
            // seems best to treat same as NoSuchMethodException

            // log exception at trace level
            logger.trace(e.getMessage(), e);
            Class<?> superClass = clazz.getSuperclass();
            if (superClass == null) {
                throw new NoSuchMethodException();
            }
            return getAnyDeclaredMethod(superClass, name, parameterTypes);
        }
    }

    private static Field getAnyDeclaredField(Class<?> clazz, String fieldName) throws Exception {
        try {
            Field field = clazz.getDeclaredField(fieldName);
            field.setAccessible(true);
            return field;
        } catch (NoSuchFieldException e) {
            // log exception at trace level
            logger.trace(e.getMessage(), e);
            Class<?> superClass = clazz.getSuperclass();
            if (superClass == null) {
                throw e;
            }
            return getAnyDeclaredField(superClass, fieldName);
        }
    }
}
