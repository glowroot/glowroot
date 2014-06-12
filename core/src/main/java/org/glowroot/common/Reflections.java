/*
 * Copyright 2013-2014 the original author or authors.
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

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

import org.checkerframework.checker.nullness.qual.Nullable;

import org.glowroot.markers.Static;

/**
 * @author Trask Stalnaker
 * @since 0.5
 */
@Static
public class Reflections {

    private Reflections() {}

    public static Method getDeclaredMethod(Class<?> type, String name, Class<?>... parameterTypes)
            throws ReflectiveException {
        try {
            Method method = type.getDeclaredMethod(name, parameterTypes);
            method.setAccessible(true);
            return method;
        } catch (NoSuchMethodException e) {
            throw new ReflectiveException(e);
        } catch (SecurityException e) {
            throw new ReflectiveException(e);
        }
    }

    public static Method getMethod(Class<?> type, String name, Class<?>... parameterTypes)
            throws ReflectiveException {
        try {
            return type.getMethod(name, parameterTypes);
        } catch (NoSuchMethodException e) {
            throw new ReflectiveException(e);
        } catch (SecurityException e) {
            throw new ReflectiveException(e);
        }
    }

    @Nullable
    public static Object invoke(Method method, Object obj, @Nullable Object... args)
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

    @Nullable
    public static Object invokeStatic(Method method, @Nullable Object... args)
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
