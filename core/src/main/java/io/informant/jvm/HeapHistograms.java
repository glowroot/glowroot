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

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Iterator;

import checkers.nullness.quals.Nullable;
import com.google.common.base.Splitter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.informant.markers.Static;

/**
 * @author Trask Stalnaker
 * @since 0.5
 */
@Static
public class HeapHistograms {

    private static final Logger logger = LoggerFactory.getLogger(HeapHistograms.class);

    @Nullable
    private static final Method attachMethod;
    @Nullable
    private static final Method heapHistoMethod;
    @Nullable
    private static final Method detachMethod;

    private static final boolean supported;
    private static final String unsupportedReason;

    static {
        if (!JDK6.isSupported()) {
            attachMethod = null;
            heapHistoMethod = null;
            detachMethod = null;
            supported = false;
            unsupportedReason = "Oracle Java SE 6 or higher is required";
        } else {
            ClassLoader systemToolClassLoader = JDK6.getSystemToolClassLoader();
            if (systemToolClassLoader == null) {
                attachMethod = null;
                heapHistoMethod = null;
                detachMethod = null;
                supported = false;
                unsupportedReason = "Unsupported due to error, see Informant log";
            } else {
                attachMethod = initAttachMethod(systemToolClassLoader);
                heapHistoMethod = initHeapHistoMethod(systemToolClassLoader);
                detachMethod = initDetachMethod(systemToolClassLoader);
                if (attachMethod == null || heapHistoMethod == null || detachMethod == null) {
                    supported = false;
                    unsupportedReason = "Unsupported due to error, see Informant log";
                } else {
                    supported = true;
                    unsupportedReason = "";
                }
            }
        }
    }

    private HeapHistograms() {}

    public static Availability getAvailability() {
        return Availability.from(supported, unsupportedReason);
    }

    public static String heapHistogramJson() throws SecurityException, NoSuchMethodException,
            IllegalAccessException, InvocationTargetException, IOException {
        InputStream in = heapHisto();
        BufferedReader reader = new BufferedReader(new InputStreamReader(in));
        // skip over header lines
        String line = reader.readLine();
        while (!line.contains("--------")) {
            line = reader.readLine();
        }
        Splitter splitter = Splitter.on(' ').omitEmptyStrings();
        HeapHistogram heapHistogram = new HeapHistogram();
        long totalBytes = 0;
        long totalCount = 0;
        while ((line = reader.readLine()) != null) {
            Iterator<String> parts = splitter.split(line).iterator();
            String num = parts.next();
            if (num.equals("Total")) {
                break;
            }
            long count = Long.parseLong(parts.next());
            long bytes = Long.parseLong(parts.next());
            String className = parts.next();
            if (className.charAt(0) != '<') {
                // skipping PermGen objects
                heapHistogram.addItem(className, bytes, count);
                totalBytes += bytes;
                totalCount += count;
            }
        }
        heapHistogram.setTotalBytes(totalBytes);
        heapHistogram.setTotalCount(totalCount);
        return heapHistogram.toJson();

    }

    private static InputStream heapHisto() throws SecurityException, NoSuchMethodException,
            IllegalAccessException, InvocationTargetException {
        Object hotSpotVirtualMachine = attachMethod.invoke(null, ProcessId.getPid());
        InputStream in = (InputStream) heapHistoMethod.invoke(hotSpotVirtualMachine,
                new Object[] {new Object[0]});
        detachMethod.invoke(hotSpotVirtualMachine);
        return in;
    }

    @Nullable
    private static Method initAttachMethod(ClassLoader classLoader) {
        try {
            Class<?> virtualMachineClass = Class.forName("com.sun.tools.attach.VirtualMachine",
                    true, classLoader);
            return virtualMachineClass.getMethod("attach", String.class);
        } catch (ClassNotFoundException e) {
            logger.debug(e.getMessage(), e);
            return null;
        } catch (SecurityException e) {
            logger.debug(e.getMessage(), e);
            return null;
        } catch (NoSuchMethodException e) {
            logger.debug(e.getMessage(), e);
            return null;
        }
    }

    @Nullable
    private static Method initHeapHistoMethod(ClassLoader classLoader) {
        try {
            Class<?> hotSpotVirtualMachineClass = Class.forName(
                    "sun.tools.attach.HotSpotVirtualMachine", true, classLoader);
            return hotSpotVirtualMachineClass.getMethod("heapHisto", Object[].class);
        } catch (ClassNotFoundException e) {
            logger.debug(e.getMessage(), e);
            return null;
        } catch (SecurityException e) {
            logger.debug(e.getMessage(), e);
            return null;
        } catch (NoSuchMethodException e) {
            logger.debug(e.getMessage(), e);
            return null;
        }
    }

    @Nullable
    private static Method initDetachMethod(ClassLoader classLoader) {
        try {
            Class<?> hotSpotVirtualMachineClass = Class.forName(
                    "sun.tools.attach.HotSpotVirtualMachine", true, classLoader);
            return hotSpotVirtualMachineClass.getMethod("detach");
        } catch (ClassNotFoundException e) {
            logger.debug(e.getMessage(), e);
            return null;
        } catch (SecurityException e) {
            logger.debug(e.getMessage(), e);
            return null;
        } catch (NoSuchMethodException e) {
            logger.debug(e.getMessage(), e);
            return null;
        }
    }
}
