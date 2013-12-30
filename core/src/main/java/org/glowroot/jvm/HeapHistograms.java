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

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Method;
import java.util.Iterator;

import checkers.igj.quals.Immutable;
import checkers.nullness.quals.Nullable;
import com.google.common.base.Splitter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.glowroot.common.Reflections;
import org.glowroot.common.Reflections.ReflectiveException;
import org.glowroot.jvm.OptionalService.OptionalServiceFactory;
import org.glowroot.jvm.OptionalService.OptionalServiceFactoryException;
import org.glowroot.jvm.OptionalService.OptionalServiceFactoryHelper;

/**
 * @author Trask Stalnaker
 * @since 0.5
 */
@Immutable
public class HeapHistograms {

    private static final Logger logger = LoggerFactory.getLogger(HeapHistograms.class);

    private final Method attachMethod;
    private final Method heapHistoMethod;
    private final Method detachMethod;

    public HeapHistograms(Method attachMethod, Method heapHistoMethod, Method detachMethod) {
        super();
        this.attachMethod = attachMethod;
        this.heapHistoMethod = heapHistoMethod;
        this.detachMethod = detachMethod;
    }

    public String heapHistogramJson() throws HeapHistogramException {
        InputStream in = heapHisto();
        final BufferedReader reader = new BufferedReader(new InputStreamReader(in));
        IOException exception = null;
        try {
            return process(reader);
        } catch (IOException e) {
            exception = e;
            throw new HeapHistogramException(e);
        } finally {
            try {
                reader.close();
            } catch (IOException e) {
                if (exception == null) {
                    // don't re-throw since it could suppress a non-IOException thrown above
                    // but at least log it in this case
                    // (ideally would use JDK7 try-with-resources which handles these corner cases)
                    logger.error(e.getMessage(), e);
                }
            }
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

    static class Factory implements OptionalServiceFactory<HeapHistograms> {
        @Nullable
        private final Jdk6 jdk6;
        Factory(@Nullable Jdk6 jdk6) {
            this.jdk6 = jdk6;
        }
        public HeapHistograms create() throws OptionalServiceFactoryException {
            if (jdk6 == null) {
                throw new OptionalServiceFactoryException("Oracle Java SE 6 or higher is required");
            }
            ClassLoader systemToolClassLoader = jdk6.getSystemToolClassLoader();
            Class<?> virtualMachineClass = OptionalServiceFactoryHelper.classForName(
                    "com.sun.tools.attach.VirtualMachine", systemToolClassLoader);
            Class<?> hotSpotVirtualMachineClass = OptionalServiceFactoryHelper.classForName(
                    "sun.tools.attach.HotSpotVirtualMachine", systemToolClassLoader);
            Method attachMethod = OptionalServiceFactoryHelper.getMethod(virtualMachineClass,
                    "attach", String.class);
            Method detachMethod = OptionalServiceFactoryHelper.getMethod(virtualMachineClass,
                    "detach");
            Method heapHistoMethod = OptionalServiceFactoryHelper.getMethod(
                    hotSpotVirtualMachineClass, "heapHisto", Object[].class);
            return new HeapHistograms(attachMethod, heapHistoMethod, detachMethod);
        }
    }
}
