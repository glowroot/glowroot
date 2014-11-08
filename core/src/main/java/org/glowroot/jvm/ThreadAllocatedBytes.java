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
package org.glowroot.jvm;

import java.lang.management.ManagementFactory;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.glowroot.common.Reflections;
import org.glowroot.common.Reflections.ReflectiveException;

public class ThreadAllocatedBytes {

    private static final Logger logger = LoggerFactory.getLogger(ThreadAllocatedBytes.class);

    private final Method getThreadAllocatedBytesMethod;
    private volatile boolean disabledDueToError;

    static OptionalService<ThreadAllocatedBytes> create() {
        Class<?> sunThreadMXBeanClass;
        try {
            sunThreadMXBeanClass = Class.forName("com.sun.management.ThreadMXBean");
        } catch (ClassNotFoundException e) {
            // log exception at debug level
            logger.debug(e.getMessage(), e);
            return OptionalService.unavailable("Cannot find class com.sun.management.ThreadMXBean"
                    + " (introduced in Oracle Java SE 6u25)");
        }
        Method isSupportedMethod;
        try {
            isSupportedMethod = sunThreadMXBeanClass.getMethod("isThreadAllocatedMemorySupported");
        } catch (SecurityException e) {
            logger.error(e.getMessage(), e);
            return OptionalService.unavailable("<see error log for detail>");
        } catch (NoSuchMethodException e) {
            logger.error(e.getMessage(), e);
            return OptionalService.unavailable("<see error log for detail>");
        }
        Boolean supported;
        try {
            supported = (Boolean) isSupportedMethod.invoke(ManagementFactory.getThreadMXBean());
        } catch (IllegalArgumentException e) {
            logger.error(e.getMessage(), e);
            return OptionalService.unavailable("<see error log for detail>");
        } catch (IllegalAccessException e) {
            logger.error(e.getMessage(), e);
            return OptionalService.unavailable("<see error log for detail>");
        } catch (InvocationTargetException e) {
            logger.error(e.getMessage(), e);
            return OptionalService.unavailable("<see error log for detail>");
        }
        if (supported == null) {
            return OptionalService.unavailable(
                    "ThreadMXBean.isThreadAllocatedMemorySupported() returned null");
        }
        if (!supported) {
            return OptionalService.unavailable("Method com.sun.management.ThreadMXBean"
                    + ".isThreadAllocatedMemorySupported() returned false");
        }
        Method getThreadAllocatedBytesMethod;
        try {
            getThreadAllocatedBytesMethod =
                    sunThreadMXBeanClass.getMethod("getThreadAllocatedBytes", long.class);
        } catch (SecurityException e) {
            logger.error(e.getMessage(), e);
            return OptionalService.unavailable("<see error log for detail>");
        } catch (NoSuchMethodException e) {
            logger.error(e.getMessage(), e);
            return OptionalService.unavailable("<see error log for detail>");
        }
        return OptionalService.available(new ThreadAllocatedBytes(getThreadAllocatedBytesMethod));
    }

    private ThreadAllocatedBytes(Method getThreadAllocatedBytesMethod) {
        this.getThreadAllocatedBytesMethod = getThreadAllocatedBytesMethod;
    }

    public long getThreadAllocatedBytesSafely(long threadId) {
        if (disabledDueToError) {
            // prevent excessive error logging in case there is a problem
            return -1;
        }
        try {
            Long threadAllocatedBytes = (Long) Reflections.invoke(getThreadAllocatedBytesMethod,
                    ManagementFactory.getThreadMXBean(), threadId);
            if (threadAllocatedBytes == null) {
                logger.error("method unexpectedly returned null:"
                        + " com.sun.management.ThreadMXBean.getThreadAllocatedBytes()");
                disabledDueToError = true;
                return -1;
            }
            return threadAllocatedBytes;
        } catch (ReflectiveException e) {
            logger.error(e.getMessage(), e);
            disabledDueToError = true;
            return -1;
        }
    }
}
