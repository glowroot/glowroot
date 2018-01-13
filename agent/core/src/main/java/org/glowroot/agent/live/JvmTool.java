/*
 * Copyright 2017-2018 the original author or authors.
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
package org.glowroot.agent.live;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.lang.management.ManagementFactory;
import java.lang.reflect.Method;
import java.util.List;

import javax.annotation.Nullable;
import javax.tools.ToolProvider;

import com.google.common.base.Joiner;
import com.google.common.base.Splitter;
import com.google.common.base.StandardSystemProperty;
import com.google.common.base.Strings;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;
import com.google.common.io.ByteStreams;
import com.google.common.io.Closer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.glowroot.agent.util.JavaVersion;
import org.glowroot.common.live.LiveJvmService.UnavailableDueToRunningInJreException;

import static com.google.common.base.Preconditions.checkNotNull;

class JvmTool {

    private static final Logger logger = LoggerFactory.getLogger(JvmTool.class);

    private static final int UNAVAILABLE_DUE_TO_RUNNING_IN_JRE_STATUS = 12345;

    private JvmTool() {}

    public static void main(String[] args) throws Exception {
        long pid = Long.parseLong(args[0]);
        String methodName = args[1];
        try {
            run(pid, methodName, new SystemOutProcessor());
        } catch (UnavailableDueToRunningInJreException e) {
            System.exit(UNAVAILABLE_DUE_TO_RUNNING_IN_JRE_STATUS);
        }
    }

    static <T> T run(long pid, String methodName, InputStreamProcessor<T> processor,
            boolean allowAttachSelf, @Nullable File glowrootJarFile) throws Exception {
        if (allowAttachSelf) {
            return run(pid, methodName, processor);
        } else {
            return runExternalAttach(pid, methodName, processor, glowrootJarFile);
        }
    }

    private static <T> T run(long pid, String methodName, InputStreamProcessor<T> processor)
            throws Exception {
        ClassLoader systemToolClassLoader;
        if (JavaVersion.isGreaterThanOrEqualToJava9()) {
            systemToolClassLoader = LiveJvmServiceImpl.class.getClassLoader();
        } else {
            systemToolClassLoader = ToolProvider.getSystemToolClassLoader();
        }
        Class<?> vmClass;
        try {
            vmClass = Class.forName("com.sun.tools.attach.VirtualMachine", true,
                    systemToolClassLoader);
        } catch (ClassNotFoundException e) {
            throw new UnavailableDueToRunningInJreException();
        }
        Method attachMethod = vmClass.getMethod("attach", String.class);
        Method detachMethod = vmClass.getMethod("detach");
        Class<?> hotSpotVmClass = Class.forName("sun.tools.attach.HotSpotVirtualMachine", true,
                systemToolClassLoader);
        Method method = hotSpotVmClass.getMethod(methodName, Object[].class);

        Object vm = attachMethod.invoke(null, Long.toString(pid));
        try {
            InputStream in = (InputStream) method.invoke(vm, (Object) new Object[0]);
            checkNotNull(in);
            return processAndClose(in, processor);
        } finally {
            detachMethod.invoke(vm);
        }
    }

    private static <T> T runExternalAttach(long pid, String methodName,
            InputStreamProcessor<T> processor, @Nullable File glowrootJarFile) throws Exception {
        List<String> command = buildCommand(pid, methodName, glowrootJarFile);
        ProcessBuilder processBuilder = new ProcessBuilder(command);
        Process process = processBuilder.start();
        Closer closer = Closer.create();
        InputStream in = closer.register(process.getInputStream());
        InputStream err = closer.register(process.getErrorStream());
        ErrorStreamReader errorStreamReader = new ErrorStreamReader(err);
        Thread errorStreamReaderThread = new Thread(errorStreamReader);
        errorStreamReaderThread.setName("Glowroot-JVM-Tool-Error-Stream-Reader");
        errorStreamReaderThread.setDaemon(true);
        errorStreamReaderThread.start();
        T result = null;
        Exception processingException = null;
        try {
            result = processAndClose(in, processor);
        } catch (Exception e) {
            processingException = e;
        } catch (Throwable t) {
            processingException = new RuntimeException(t);
        }
        errorStreamReaderThread.join();
        closer.close();
        int status = process.waitFor();
        if (status == UNAVAILABLE_DUE_TO_RUNNING_IN_JRE_STATUS) {
            throw new UnavailableDueToRunningInJreException();
        } else if (status != 0) {
            logger.error("error occurred while trying to run jvm tool:\n{}\n{}",
                    Joiner.on(' ').join(command), errorStreamReader.getOutput().trim());
            throw new IllegalStateException("Error occurred while trying to run jvm tool");
        }
        if (result == null) {
            throw checkNotNull(processingException);
        }
        return result;
    }

    private static List<String> buildCommand(long pid, String methodName,
            @Nullable File glowrootJarFile) {
        List<String> command = Lists.newArrayList();
        String javaExecutable = StandardSystemProperty.JAVA_HOME.value() + File.separator + "bin"
                + File.separator + "java";
        command.add(javaExecutable);
        command.add("-classpath");
        command.add(Joiner.on(File.pathSeparatorChar).join(buildClasspath(glowrootJarFile)));
        command.add(JvmTool.class.getName());
        command.add(Long.toString(pid));
        command.add(methodName);
        return command;
    }

    private static List<String> buildClasspath(@Nullable File glowrootJarFile) {
        if (glowrootJarFile == null || !isShaded()) {
            // this is just to support testing
            List<String> classpath = Lists.newArrayList();
            if (glowrootJarFile != null) {
                classpath.add(glowrootJarFile.getAbsolutePath());
            }
            classpath.addAll(splitClasspath(StandardSystemProperty.JAVA_CLASS_PATH.value()));
            for (String jvmArg : ManagementFactory.getRuntimeMXBean().getInputArguments()) {
                if (jvmArg.startsWith("-Xbootclasspath/a:")) {
                    classpath.addAll(
                            splitClasspath(jvmArg.substring("-Xbootclasspath/a:".length())));
                    break;
                }
            }
            return classpath;
        } else {
            return ImmutableList.of(glowrootJarFile.getAbsolutePath());
        }
    }

    private static List<String> splitClasspath(@Nullable String classpath) {
        if (Strings.isNullOrEmpty(classpath)) {
            return ImmutableList.of();
        }
        return Splitter.on(File.pathSeparatorChar).splitToList(classpath);
    }

    private static <T> T processAndClose(InputStream in, InputStreamProcessor<T> processor)
            throws IOException {
        Closer closer = Closer.create();
        try {
            closer.register(in);
            return processor.process(in);
        } catch (Throwable t) {
            throw closer.rethrow(t);
        } finally {
            closer.close();
        }
    }

    private static boolean isShaded() {
        try {
            Class.forName("org.glowroot.agent.shaded.org.slf4j.Logger");
            return true;
        } catch (ClassNotFoundException e) {
            // log exception at trace level
            logger.trace(e.getMessage(), e);
            return false;
        }
    }

    interface InputStreamProcessor<T> {
        // the input stream is not buffered, so should be buffered somehow
        // the input stream will be closed by the caller
        T process(InputStream in) throws IOException;
    }

    private static class SystemOutProcessor implements InputStreamProcessor</*@Nullable*/ Void> {
        @Override
        public @Nullable Void process(InputStream in) throws IOException {
            ByteStreams.copy(in, System.out);
            return null;
        }
    }

    private static class ErrorStreamReader implements Runnable {

        private final InputStream errorStream;
        private final ByteArrayOutputStream baos;

        private ErrorStreamReader(InputStream errorStream) {
            this.errorStream = errorStream;
            this.baos = new ByteArrayOutputStream();
        }

        @Override
        public void run() {
            try {
                ByteStreams.copy(errorStream, baos);
            } catch (IOException e) {
                logger.error(e.getMessage(), e);
            }
        }

        private String getOutput() {
            return new String(baos.toByteArray());
        }
    }
}
