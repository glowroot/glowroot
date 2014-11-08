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

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Method;
import java.util.Iterator;

import javax.tools.ToolProvider;

import com.google.common.base.Splitter;
import com.google.common.io.Closer;
import org.objectweb.asm.Type;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.glowroot.common.Reflections;
import org.glowroot.common.Reflections.ReflectiveException;
import org.glowroot.markers.Immutable;

@Immutable
public class HeapHistograms {

    private static final Logger logger = LoggerFactory.getLogger(HeapHistogram.class);

    private final Method attachMethod;
    private final Method heapHistoMethod;
    private final Method detachMethod;

    static OptionalService<HeapHistograms> create() {
        ClassLoader systemToolClassLoader = ToolProvider.getSystemToolClassLoader();
        Class<?> virtualMachineClass;
        try {
            virtualMachineClass = Class.forName("com.sun.tools.attach.VirtualMachine", true,
                    systemToolClassLoader);
        } catch (ClassNotFoundException e) {
            // log exception at debug level
            logger.debug(e.getMessage(), e);
            return OptionalService.unavailable("Cannot find class"
                    + " com.sun.tools.attach.VirtualMachine (not available in JRE)");
        }
        Class<?> hotSpotVirtualMachineClass;
        try {
            hotSpotVirtualMachineClass = Class.forName("sun.tools.attach.HotSpotVirtualMachine",
                    true, systemToolClassLoader);
        } catch (ClassNotFoundException e) {
            // log exception at debug level
            logger.debug(e.getMessage(), e);
            return OptionalService.unavailable("Cannot find class"
                    + " sun.tools.attach.HotSpotVirtualMachine (not available in JRE)");
        }
        try {
            Method attachMethod = virtualMachineClass.getMethod("attach", String.class);
            Method detachMethod = virtualMachineClass.getMethod("detach");
            Method heapHistoMethod =
                    hotSpotVirtualMachineClass.getMethod("heapHisto", Object[].class);
            return OptionalService.available(
                    new HeapHistograms(attachMethod, heapHistoMethod, detachMethod));
        } catch (SecurityException e) {
            logger.error(e.getMessage(), e);
            return OptionalService.unavailable("<see error log for detail>");
        } catch (NoSuchMethodException e) {
            logger.error(e.getMessage(), e);
            return OptionalService.unavailable("<see error log for detail>");
        }
    }

    private HeapHistograms(Method attachMethod, Method heapHistoMethod, Method detachMethod) {
        super();
        this.attachMethod = attachMethod;
        this.heapHistoMethod = heapHistoMethod;
        this.detachMethod = detachMethod;
    }

    public String heapHistogramJson() throws HeapHistogramException {
        InputStream in = heapHisto();
        try {
            // Closer is used to simulate Java 7 try-with-resources
            Closer closer = Closer.create();
            BufferedReader reader = closer.register(new BufferedReader(new InputStreamReader(in)));
            try {
                return process(reader);
            } catch (Throwable t) {
                throw closer.rethrow(t);
            } finally {
                closer.close();
            }
        } catch (IOException e) {
            throw new HeapHistogramException(e);
        }
    }

    private String process(BufferedReader reader) throws IOException {
        // skip over header lines
        String line = reader.readLine();
        while (line != null && !line.contains("--------")) {
            line = reader.readLine();
        }
        if (line == null) {
            throw new IOException("Unexpected heapHisto output");
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
            // skipping PermGen objects
            if (className.charAt(0) != '<') {
                if (className.charAt(0) == '[') {
                    className = Type.getType(className).getClassName();
                    heapHistogram.addItem(className, bytes, count);
                } else {
                    heapHistogram.addItem(className, bytes, count);
                }
                totalBytes += bytes;
                totalCount += count;
            }
        }
        heapHistogram.setTotalBytes(totalBytes);
        heapHistogram.setTotalCount(totalCount);
        return heapHistogram.toJson();
    }

    private InputStream heapHisto() throws HeapHistogramException {
        Object hotSpotVirtualMachine;
        try {
            hotSpotVirtualMachine = Reflections.invokeStatic(attachMethod, ProcessId.getPid());
        } catch (ReflectiveException e) {
            throw new HeapHistogramException(e);
        }
        if (hotSpotVirtualMachine == null) {
            throw new HeapHistogramException("Method attach() returned null");
        }
        InputStream in;
        try {
            in = (InputStream) Reflections.invoke(heapHistoMethod, hotSpotVirtualMachine,
                    new Object[] {new Object[0]});
        } catch (ReflectiveException e) {
            throw new HeapHistogramException(e);
        }
        try {
            Reflections.invoke(detachMethod, hotSpotVirtualMachine);
        } catch (ReflectiveException e) {
            throw new HeapHistogramException(e);
        }
        if (in == null) {
            throw new HeapHistogramException("Method heapHisto() returned null");
        }
        return in;
    }

    @SuppressWarnings("serial")
    public static class HeapHistogramException extends Exception {
        private HeapHistogramException(Exception cause) {
            super(cause);
        }
        private HeapHistogramException(String message) {
            super(message);
        }
    }
}
