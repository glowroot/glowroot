/*
 * Copyright 2013-2015 the original author or authors.
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
package org.glowroot.common;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

import javax.annotation.Nullable;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class Reflections {

    private static final Logger logger = LoggerFactory.getLogger(Reflections.class);

    private Reflections() {}

    public static Method getMethod(Class<?> clazz, String name, Class<?>... parameterTypes)
            throws ReflectiveException {
        try {
            Method method = clazz.getMethod(name, parameterTypes);
            method.setAccessible(true);
            return method;
        } catch (NoSuchMethodException e) {
            throw new ReflectiveException(e);
        } catch (SecurityException e) {
            throw new ReflectiveException(e);
        }
    }

    public static Method getDeclaredMethod(Class<?> clazz, String name, Class<?>... parameterTypes)
            throws ReflectiveException {
        try {
            Method method = clazz.getDeclaredMethod(name, parameterTypes);
            method.setAccessible(true);
            return method;
        } catch (NoSuchMethodException e) {
            throw new ReflectiveException(e);
        } catch (SecurityException e) {
            throw new ReflectiveException(e);
        }
    }

    public static Method getAnyMethod(Class<?> clazz, String name, Class<?>... parameterTypes)
            throws ReflectiveException {
        try {
            return getMethod(clazz, name, parameterTypes);
        } catch (ReflectiveException e) {
            // log exception at trace level
            logger.trace(e.getMessage(), e);
            return getDeclaredMethod(clazz, name, parameterTypes);
        }
    }

    public static <T> Constructor<T> getConstructor(Class<T> clazz, Class<?>... parameterTypes)
            throws ReflectiveException {
        try {
            Constructor<T> constructor = clazz.getConstructor(parameterTypes);
            constructor.setAccessible(true);
            return constructor;
        } catch (NoSuchMethodException e) {
            throw new ReflectiveException(e);
        } catch (SecurityException e) {
            throw new ReflectiveException(e);
        }
    }

    public static Field getAnyField(Class<?> clazz, String fieldName) throws ReflectiveException {
        try {
            Field field = clazz.getField(fieldName);
            field.setAccessible(true);
            return field;
        } catch (NoSuchFieldException e) {
            // log exception at trace level
            logger.trace(e.getMessage(), e);
            try {
                Field field = clazz.getDeclaredField(fieldName);
                field.setAccessible(true);
                return field;
            } catch (SecurityException f) {
                throw new ReflectiveException(f);
            } catch (NoSuchFieldException f) {
                throw new ReflectiveException(f);
            }
        } catch (SecurityException e) {
            throw new ReflectiveException(e);
        }
    }

    public static @Nullable Object invoke(Method method, Object obj, @Nullable Object... args)
            throws ReflectiveException {
        try {
            return method.invoke(obj, args);
        } catch (IllegalAccessException e) {
            throw new ReflectiveException(e);
        } catch (IllegalArgumentException e) {
            throw new ReflectiveException(e);
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause();
            if (cause == null) {
                // this shouldn't really happen
                throw new ReflectiveException(e);
            }
            throw new ReflectiveTargetException(cause);
        }
    }

    public static @Nullable Object invokeStatic(Method method, @Nullable Object... args)
            throws ReflectiveException {
        try {
            return method.invoke(null, args);
        } catch (IllegalAccessException e) {
            throw new ReflectiveException(e);
        } catch (IllegalArgumentException e) {
            throw new ReflectiveException(e);
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause();
            if (cause == null) {
                // this shouldn't really happen
                throw new ReflectiveException(e);
            }
            throw new ReflectiveTargetException(cause);
        }
    }

    public static <T> T invoke(Constructor<T> constructor, @Nullable Object... args)
            throws ReflectiveException {
        try {
            return constructor.newInstance(args);
        } catch (IllegalAccessException e) {
            throw new ReflectiveException(e);
        } catch (IllegalArgumentException e) {
            throw new ReflectiveException(e);
        } catch (InstantiationException e) {
            throw new ReflectiveException(e);
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause();
            if (cause == null) {
                // this shouldn't really happen
                throw new ReflectiveException(e);
            }
            throw new ReflectiveTargetException(cause);
        }
    }

    public static @Nullable Object getFieldValue(Field field, Object obj)
            throws ReflectiveException {
        try {
            return field.get(obj);
        } catch (IllegalAccessException e) {
            throw new ReflectiveException(e);
        } catch (IllegalArgumentException e) {
            throw new ReflectiveException(e);
        }
    }

    @SuppressWarnings("serial")
    public static class ReflectiveException extends Exception {
        public ReflectiveException(Throwable cause) {
            super(cause);
        }
    }

    @SuppressWarnings("serial")
    public static class ReflectiveTargetException extends ReflectiveException {
        private ReflectiveTargetException(Throwable cause) {
            super(cause);
        }
    }
}
