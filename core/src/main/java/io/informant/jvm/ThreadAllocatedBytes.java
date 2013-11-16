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

import java.lang.management.ManagementFactory;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

import checkers.nullness.quals.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.informant.markers.Static;

/**
 * @author Trask Stalnaker
 * @since 0.5
 */
@Static
public class ThreadAllocatedBytes {

    private static final Logger logger = LoggerFactory.getLogger(ThreadAllocatedBytes.class);

    @Nullable
    private static final Class<?> sunThreadMXBeanClass;

    @Nullable
    private static final Method getThreadAllocatedBytesMethod;

    private static final boolean supported;
    private static final String unsupportedReason;

    private static volatile boolean disabledDueToError;

    static {
        sunThreadMXBeanClass = initSunThreadMXBeanClass();
        if (sunThreadMXBeanClass == null) {
            getThreadAllocatedBytesMethod = null;
            supported = false;
            unsupportedReason = "No such class com.sun.management.ThreadMXBean (introduced in"
                    + " Oracle Java SE 6u25)";
        } else {
            if (isSupported(sunThreadMXBeanClass)) {
                getThreadAllocatedBytesMethod =
                        initGetThreadAllocatedBytesMethod(sunThreadMXBeanClass);
                if (getThreadAllocatedBytesMethod == null) {
                    supported = false;
                    unsupportedReason = "Unsupported due to error, see Informant log";
                } else {
                    supported = true;
                    unsupportedReason = "";
                }
            } else {
                getThreadAllocatedBytesMethod = null;
                supported = false;
                unsupportedReason = "com.sun.management.ThreadMXBean"
                        + ".isThreadAllocatedMemorySupported() returned false";
            }
        }
    }

    private ThreadAllocatedBytes() {}

    public static long getThreadAllocatedBytesSafely(long threadId) {
        if (!supported) {
            // getThreadAllocatedBytes() throws UnsupportedOperationException in this case
            return -1;
        }
        if (disabledDueToError) {
            // prevent excessive error logging in case there is a problem
            return -1;
        }
        try {
            return (Long) getThreadAllocatedBytesMethod.invoke(ManagementFactory.getThreadMXBean(),
                    threadId);
        } catch (IllegalArgumentException e) {
            logger.error(e.getMessage(), e);
            disabledDueToError = true;
            return -1;
        } catch (IllegalAccessException e) {
            logger.error(e.getMessage(), e);
            disabledDueToError = true;
            return -1;
        } catch (InvocationTargetException e) {
            logger.error(e.getMessage(), e);
            disabledDueToError = true;
            return -1;
        }
    }

    public static Availability getAvailability() {
        if (!supported) {
            return Availability.unavailable(unsupportedReason);
        } else if (!isEnabled(sunThreadMXBeanClass)) {
            return Availability.unavailable("com.sun.management.ThreadMXBean"
                    + ".isThreadAllocatedMemoryEnabled() returned false");
        } else if (disabledDueToError) {
            return Availability.unavailable("Disabled due to error, see Informant log");
        } else {
            return Availability.available();
        }
    }

    @Nullable
    private static Class<?> initSunThreadMXBeanClass() {
        try {
            return Class.forName("com.sun.management.ThreadMXBean");
        } catch (ClassNotFoundException e) {
            // this is ok, just means its not available
            return null;
        }
    }

    @Nullable
    private static Method initGetThreadAllocatedBytesMethod(Class<?> sunThreadMXBeanClass) {
        try {
            return sunThreadMXBeanClass.getMethod("getThreadAllocatedBytes", long.class);
        } catch (SecurityException e) {
            logger.error(e.getMessage(), e);
            return null;
        } catch (NoSuchMethodException e) {
            logger.error(e.getMessage(), e);
            return null;
        }
    }

    @Nullable
    private static boolean isSupported(Class<?> sunThreadMXBeanClass) {
        try {
            Method isSupportedMethod =
                    sunThreadMXBeanClass.getMethod("isThreadAllocatedMemorySupported");
            return (Boolean) isSupportedMethod.invoke(ManagementFactory.getThreadMXBean());
        } catch (SecurityException e) {
            logger.error(e.getMessage(), e);
            return false;
        } catch (NoSuchMethodException e) {
            logger.error(e.getMessage(), e);
            return false;
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

    @Nullable
    private static boolean isEnabled(Class<?> sunThreadMXBeanClass) {
        try {
            Method isEnabledMethod =
                    sunThreadMXBeanClass.getMethod("isThreadAllocatedMemoryEnabled");
            return (Boolean) isEnabledMethod.invoke(ManagementFactory.getThreadMXBean());
        } catch (SecurityException e) {
            logger.error(e.getMessage(), e);
            return false;
        } catch (NoSuchMethodException e) {
            logger.error(e.getMessage(), e);
            return false;
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
}
