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

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

import checkers.igj.quals.Immutable;
import checkers.nullness.quals.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.glowroot.markers.UsedByJsonBinding;

import static org.glowroot.common.Nullness.castNonNull;

/**
 * @author Trask Stalnaker
 * @since 0.5
 */
public class OptionalService<T> {

    private static final Logger logger = LoggerFactory.getLogger(OptionalService.class);

    @Nullable
    private final T service;
    private final Availability availability;

    public OptionalService(OptionalServiceFactory<T> factory) {
        T serviceLocal = null;
        Availability availabilityLocal = null;
        try {
            serviceLocal = factory.create();
            availabilityLocal = new Availability(true, "");
        } catch (OptionalServiceFactoryException e) {
            Throwable cause = e.getCause();
            if (cause == null) {
                // unavailable due to "expected" cause
                String message = e.getMessage();
                // OptionalServiceFactoryException can only be constructed with non-null cause or
                // non-null message
                castNonNull(message);
                availabilityLocal = new Availability(false, message);
            } else {
                logger.warn(e.getMessage(), e);
                availabilityLocal = new Availability(false, "Unavailable due to error: "
                        + cause.getMessage() + ", see Glowroot log for stack trace");
            }
        }
        service = serviceLocal;
        availability = availabilityLocal;
    }

    @Nullable
    public T getService() {
        return service;
    }

    public Availability getAvailability() {
        return availability;
    }

    interface OptionalServiceFactory<T> {
        T create() throws OptionalServiceFactoryException;
    }

    static class OptionalServiceFactoryHelper {

        static Class<?> classForName(String className) throws OptionalServiceFactoryException {
            return classForName(className, OptionalServiceFactoryHelper.class.getClassLoader());
        }

        static Class<?> classForName(String className, @Nullable ClassLoader classLoader)
                throws OptionalServiceFactoryException {
            try {
                return Class.forName(className, true, classLoader);
            } catch (ClassNotFoundException e) {
                throw new OptionalServiceFactoryException("Could not find class " + className);
            }
        }

        static Method getMethod(Class<?> type, String methodName, Class<?>... parameterTypes)
                throws OptionalServiceFactoryException {
            try {
                return type.getMethod(methodName, parameterTypes);
            } catch (NoSuchMethodException e) {
                throw new OptionalServiceFactoryException("Could not find method " + methodName
                        + " on class " + type.getName());
            } catch (SecurityException e) {
                throw new OptionalServiceFactoryException(e);
            }
        }

        static Method getDeclaredMethod(Class<?> type, String methodName,
                Class<?>... parameterTypes) throws OptionalServiceFactoryException {
            try {
                return type.getDeclaredMethod(methodName, parameterTypes);
            } catch (NoSuchMethodException e) {
                throw new OptionalServiceFactoryException("Could not find method " + methodName
                        + " on class " + type.getName());
            } catch (SecurityException e) {
                throw new OptionalServiceFactoryException(e);
            }
        }

        @Nullable
        static Object invoke(Method method, @Nullable Object obj, Object... args)
                throws OptionalServiceFactoryException {
            try {
                return method.invoke(obj, args);
            } catch (IllegalAccessException e) {
                throw new OptionalServiceFactoryException(e);
            } catch (IllegalArgumentException e) {
                throw new OptionalServiceFactoryException(e);
            } catch (InvocationTargetException e) {
                throw new OptionalServiceFactoryException(e);
            }
        }
    }

    @SuppressWarnings("serial")
    static class OptionalServiceFactoryException extends Exception {

        OptionalServiceFactoryException(String message) {
            super(message);
        }

        OptionalServiceFactoryException(Exception e) {
            super(e);
        }
    }

    @UsedByJsonBinding
    @Immutable
    public static class Availability {

        private final boolean available;
        // reason only needed when available is false
        private final String reason;

        public Availability(boolean available, String reason) {
            this.available = available;
            this.reason = reason;
        }

        public boolean isAvailable() {
            return available;
        }

        public String getReason() {
            return reason;
        }
    }
}
