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
import java.lang.reflect.Method;

import javax.annotation.concurrent.ThreadSafe;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.glowroot.common.Reflections;
import org.glowroot.common.Reflections.ReflectiveException;
import org.glowroot.jvm.OptionalService.Availability;
import org.glowroot.jvm.OptionalService.OptionalServiceFactory;
import org.glowroot.jvm.OptionalService.OptionalServiceFactoryException;
import org.glowroot.jvm.OptionalService.OptionalServiceFactoryHelper;

/**
 * @author Trask Stalnaker
 * @since 0.5
 */
@ThreadSafe
public class ThreadAllocatedBytes {

    private static final Logger logger = LoggerFactory.getLogger(ThreadAllocatedBytes.class);

    private final Class<?> sunThreadMXBeanClass;
    private final Method getThreadAllocatedBytesMethod;
    private volatile boolean disabledDueToError;

    private ThreadAllocatedBytes(Class<?> sunThreadMXBeanClass,
            Method getThreadAllocatedBytesMethod) {
        this.sunThreadMXBeanClass = sunThreadMXBeanClass;
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

    public Availability getAvailability() {
        if (!isEnabled(sunThreadMXBeanClass)) {
            return new Availability(false, "com.sun.management.ThreadMXBean"
                    + ".isThreadAllocatedMemoryEnabled() returned false");
        }
        if (disabledDueToError) {
            return new Availability(false, "Disabled due to error, see Glowroot log");
        }
        return new Availability(true, "");
    }

    private static boolean isEnabled(Class<?> sunThreadMXBeanClass) {
        Method isEnabledMethod;
        try {
            isEnabledMethod = Reflections.getMethod(sunThreadMXBeanClass,
                    "isThreadAllocatedMemoryEnabled");
        } catch (ReflectiveException e) {
            logger.error(e.getMessage(), e);
            return false;
        }
        MethodWithNonNullReturn method = new MethodWithNonNullReturn(isEnabledMethod, false);
        return (Boolean) method.invoke(ManagementFactory.getThreadMXBean());
    }

    static class Factory implements OptionalServiceFactory<ThreadAllocatedBytes> {
        @Override
        public ThreadAllocatedBytes create() throws OptionalServiceFactoryException {
            Class<?> sunThreadMXBeanClass;
            try {
                sunThreadMXBeanClass = Class.forName("com.sun.management.ThreadMXBean");
            } catch (ClassNotFoundException e) {
                throw new OptionalServiceFactoryException(
                        "Cannot find class com.sun.management.ThreadMXBean"
                                + " (introduced in Oracle Java SE 6u25)");
            }
            Method isSupportedMethod = OptionalServiceFactoryHelper.getMethod(sunThreadMXBeanClass,
                    "isThreadAllocatedMemorySupported");
            Boolean supported = (Boolean) OptionalServiceFactoryHelper.invoke(isSupportedMethod,
                    ManagementFactory.getThreadMXBean());
            if (supported == null) {
                throw new OptionalServiceFactoryException(
                        "ThreadMXBean.isThreadAllocatedMemorySupported() returned null");
            }
            if (supported) {
                Method getThreadAllocatedBytesMethod =
                        OptionalServiceFactoryHelper.getMethod(sunThreadMXBeanClass,
                                "getThreadAllocatedBytes", long.class);
                return new ThreadAllocatedBytes(sunThreadMXBeanClass,
                        getThreadAllocatedBytesMethod);
            }
            throw new OptionalServiceFactoryException("Method com.sun.management.ThreadMXBean"
                    + ".isThreadAllocatedMemorySupported() returned false");
        }
    }
}
