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
package io.informant.local.ui;

import java.io.File;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.lang.management.RuntimeMXBean;
import java.lang.management.ThreadInfo;
import java.lang.management.ThreadMXBean;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Enumeration;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Properties;

import javax.management.JMException;
import javax.management.ObjectName;
import javax.management.openmbean.CompositeData;

import checkers.nullness.quals.Nullable;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectWriter;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.common.base.Splitter;
import com.google.common.base.Strings;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;
import com.google.common.collect.Ordering;
import com.google.common.io.CharStreams;
import com.google.common.primitives.Ints;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.informant.common.ObjectMappers;
import io.informant.config.WithVersionJsonView;
import io.informant.markers.Singleton;
import io.informant.markers.UsedByReflection;

import static io.informant.common.Nullness.assertNonNull;

/**
 * Json service to read jvm info, bound to /backend/jvm.
 * 
 * @author Trask Stalnaker
 * @since 0.5
 */
@Singleton
@JsonService
class JvmJsonService {

    private static final Logger logger = LoggerFactory.getLogger(JvmJsonService.class);
    private static final ObjectMapper mapper = ObjectMappers.create();

    private static final Ordering<ThreadInfo> orderingByStackSize = new Ordering<ThreadInfo>() {
        @Override
        public int compare(@Nullable ThreadInfo left, @Nullable ThreadInfo right) {
            assertNonNull(left, "Ordering of non-null elements only");
            assertNonNull(right, "Ordering of non-null elements only");
            return -Ints.compare(left.getStackTrace().length, right.getStackTrace().length);
        }
    };

    @JsonServiceMethod
    String getGeneralInfo() throws IOException, JMException {
        logger.debug("getGeneralInfo()");
        String runtimeName = ManagementFactory.getRuntimeMXBean().getName();
        int index = runtimeName.indexOf('@');
        String pid;
        if (index > 0) {
            pid = runtimeName.substring(0, index);
        } else {
            pid = "<unknown>";
        }
        String command = System.getProperty("sun.java.command");
        String mainClass = null;
        List<String> arguments = ImmutableList.of();
        if (command != null) {
            index = command.indexOf(' ');
            if (index > 0) {
                mainClass = command.substring(0, index);
                arguments = Lists
                        .newArrayList(Splitter.on(' ').split(command.substring(index + 1)));
            }
        }
        RuntimeMXBean runtimeMXBean = ManagementFactory.getRuntimeMXBean();
        String jvm = System.getProperty("java.vm.name") + " ("
                + System.getProperty("java.vm.version") + ", " + System.getProperty("java.vm.info")
                + ")";
        String java = "version " + System.getProperty("java.version") + ", vendor "
                + System.getProperty("java.vm.vendor");
        String javaHome = System.getProperty("java.home");

        StringBuilder sb = new StringBuilder();
        JsonGenerator jg = mapper.getFactory().createGenerator(CharStreams.asWriter(sb));
        ObjectWriter writer = mapper.writerWithView(WithVersionJsonView.class);
        jg.writeStartObject();
        jg.writeNumberField("startTime", runtimeMXBean.getStartTime());
        jg.writeNumberField("uptime", runtimeMXBean.getUptime());
        jg.writeStringField("pid", pid);
        jg.writeStringField("mainClass", mainClass);
        jg.writeFieldName("mainClassArguments");
        writer.writeValue(jg, arguments);
        jg.writeStringField("jvm", jvm);
        jg.writeStringField("java", java);
        jg.writeStringField("javaHome", javaHome);
        jg.writeFieldName("jvmArguments");
        writer.writeValue(jg, runtimeMXBean.getInputArguments());
        jg.writeEndObject();
        jg.close();
        return sb.toString();
    }

    @JsonServiceMethod
    String getSystemProperties() throws IOException {
        logger.debug("getSystemProperties()");
        Properties properties = System.getProperties();
        StringBuilder sb = new StringBuilder();
        JsonGenerator jg = mapper.getFactory().createGenerator(CharStreams.asWriter(sb));
        jg.writeStartObject();
        for (Enumeration<?> e = properties.propertyNames(); e.hasMoreElements();) {
            Object obj = e.nextElement();
            if (obj instanceof String) {
                String propertyName = (String) obj;
                String propertyValue = properties.getProperty(propertyName);
                jg.writeStringField(propertyName, propertyValue);
            }
        }
        jg.writeEndObject();
        jg.close();
        return sb.toString();
    }

    @JsonServiceMethod
    String getThreadDump() throws IOException {
        logger.debug("getThreadDump()");
        ThreadMXBean threadBean = ManagementFactory.getThreadMXBean();
        long currentThreadId = Thread.currentThread().getId();
        List<ThreadInfo> threadInfos = Lists.newArrayList();
        for (long threadId : threadBean.getAllThreadIds()) {
            if (threadId != currentThreadId) {
                ThreadInfo threadInfo = threadBean.getThreadInfo(threadId, Integer.MAX_VALUE);
                if (threadInfo != null) {
                    threadInfos.add(threadInfo);
                }
            }
        }
        threadInfos = orderingByStackSize.sortedCopy(threadInfos);
        StringBuilder sb = new StringBuilder();
        JsonGenerator jg = mapper.getFactory().createGenerator(CharStreams.asWriter(sb));
        jg.writeStartArray();
        for (ThreadInfo threadInfo : threadInfos) {
            jg.writeStartObject();
            jg.writeStringField("name", threadInfo.getThreadName());
            jg.writeStringField("state", threadInfo.getThreadName());
            jg.writeStringField("lockName", threadInfo.getLockName());
            jg.writeArrayFieldStart("stackTrace");
            for (StackTraceElement stackTraceElement : threadInfo.getStackTrace()) {
                jg.writeString(stackTraceElement.toString());
            }
            jg.writeEndArray();
            jg.writeEndObject();
        }
        jg.writeEndArray();
        jg.close();
        return sb.toString();
    }

    @JsonServiceMethod
    String getHeapDumpDefaults() throws IOException, JMException {
        logger.debug("getHeapDumpDefaults()");
        ObjectName hotSpotDiagnostic =
                ObjectName.getInstance("com.sun.management:type=HotSpotDiagnostic");
        CompositeData option = (CompositeData) ManagementFactory.getPlatformMBeanServer()
                .invoke(hotSpotDiagnostic, "getVMOption", new Object[] {"HeapDumpPath"},
                        new String[] {"java.lang.String"});
        String heapDumpPath = (String) option.get("value");
        if (Strings.isNullOrEmpty(heapDumpPath)) {
            heapDumpPath = new File(System.getProperty("java.io.tmpdir")).getAbsolutePath();
        }
        StringBuilder sb = new StringBuilder();
        JsonGenerator jg = mapper.getFactory().createGenerator(CharStreams.asWriter(sb));
        jg.writeStartObject();
        jg.writeStringField("directory", heapDumpPath);
        jg.writeBooleanField("checkDiskSpaceSupported", isAtLeastJdk6());
        jg.writeEndObject();
        jg.close();
        return sb.toString();
    }

    @JsonServiceMethod
    String checkDiskSpace(String content) throws IOException, JMException, NoSuchMethodException,
            SecurityException, IllegalAccessException, InvocationTargetException {
        logger.debug("checkDiskSpace(): content={}", content);
        ObjectNode rootNode = (ObjectNode) mapper.readTree(content);
        String directory = rootNode.get("directory").asText();
        File dir = new File(directory);
        if (!dir.exists()) {
            return "{\"error\": \"Directory doesn't exist\"}";
        }
        if (!dir.isDirectory()) {
            return "{\"error\": \"Path is not a directory\"}";
        }
        Method getFreeSpaceMethod = File.class.getDeclaredMethod("getFreeSpace");
        long diskSpace = (Long) getFreeSpaceMethod.invoke(new File(directory));
        return Long.toString(diskSpace);
    }

    @JsonServiceMethod
    String dumpHeap(String content) throws IOException, JMException {
        logger.debug("dumpHeap(): content={}", content);
        ObjectNode rootNode = (ObjectNode) mapper.readTree(content);
        String directory = rootNode.get("directory").asText();
        File dir = new File(directory);
        if (!dir.exists()) {
            return "{\"error\": \"Directory doesn't exist\"}";
        }
        if (!dir.isDirectory()) {
            return "{\"error\": \"Path is not a directory\"}";
        }
        String date = new SimpleDateFormat("yyyyMMdd").format(new Date());
        File file = new File(dir, "heapdump-" + date + ".hprof");
        int i = 1;
        while (file.exists()) {
            i++;
            file = new File(dir, "heapdump-" + date + "-" + i + ".hprof");
        }
        ObjectName hotSpotDiagnostic =
                ObjectName.getInstance("com.sun.management:type=HotSpotDiagnostic");
        ManagementFactory.getPlatformMBeanServer().invoke(hotSpotDiagnostic, "dumpHeap",
                new Object[] {file.getAbsolutePath(), false},
                new String[] {"java.lang.String", "boolean"});

        StringBuilder sb = new StringBuilder();
        JsonGenerator jg = mapper.getFactory().createGenerator(CharStreams.asWriter(sb));
        jg.writeStartObject();
        jg.writeStringField("filename", file.getAbsolutePath());
        jg.writeNumberField("size", file.length());
        jg.writeEndObject();
        jg.close();
        return sb.toString();
    }

    @JsonServiceMethod
    String getDiagnosticOptions() throws IOException, JMException {
        logger.debug("getDiagnosticOptions()");
        ObjectName hotSpotDiagnostic =
                ObjectName.getInstance("com.sun.management:type=HotSpotDiagnostic");
        CompositeData[] diagnosticOptions =
                (CompositeData[]) ManagementFactory.getPlatformMBeanServer().getAttribute(
                        hotSpotDiagnostic, "DiagnosticOptions");

        List<VMOption> options = Lists.newArrayList();
        for (CompositeData diagnosticOption : diagnosticOptions) {
            String name = (String) diagnosticOption.get("name");
            String value = (String) diagnosticOption.get("value");
            String origin = (String) diagnosticOption.get("origin");
            // only handle true/false values for now
            if ("true".equals(value) || "false".equals(value)) {
                // filter out seemingly less useful options (keep only HeapDump... and Print...)
                if (name.startsWith("HeapDump") || name.startsWith("Print")) {
                    options.add(new VMOption(name, Boolean.valueOf(value), origin));
                }
            }
        }
        // don't sort these, there are not many and they seem to be sorted in meaningful order
        // already
        return mapper.writeValueAsString(options);
    }

    @JsonServiceMethod
    String updateDiagnosticOptions(String content) throws IOException, JMException {
        logger.debug("updateDiagnosticOptions(): content={}", content);
        ObjectName hotSpotDiagnostic =
                ObjectName.getInstance("com.sun.management:type=HotSpotDiagnostic");
        Map<String, Object> values =
                mapper.readValue(content, new TypeReference<Map<String, Object>>() {});
        for (Entry<String, Object> value : values.entrySet()) {
            ManagementFactory.getPlatformMBeanServer().invoke(hotSpotDiagnostic, "setVMOption",
                    new Object[] {value.getKey(), value.getValue().toString()},
                    new String[] {"java.lang.String", "java.lang.String"});
        }
        return getDiagnosticOptions();
    }

    @JsonServiceMethod
    String getAllOptions() throws IOException, JMException, ClassNotFoundException,
            NoSuchMethodException, SecurityException, IllegalAccessException,
            InvocationTargetException {
        logger.debug("getAllOptions()");
        Class<?> flagClass = Class.forName("sun.management.Flag");
        Method getAllFlagsMethod = flagClass.getDeclaredMethod("getAllFlags");
        getAllFlagsMethod.setAccessible(true);
        List<?> flags = (List<?>) getAllFlagsMethod.invoke(null);

        ObjectName hotSpotDiagnostic =
                ObjectName.getInstance("com.sun.management:type=HotSpotDiagnostic");

        Method getVMOptionMethod = flagClass.getDeclaredMethod("getVMOption");
        getVMOptionMethod.setAccessible(true);
        Class<?> vmOptionClass = Class.forName("com.sun.management.VMOption");
        Method getNameMethod = vmOptionClass.getDeclaredMethod("getName");
        getNameMethod.setAccessible(true);

        List<VMOption> options = Lists.newArrayList();
        for (Object flag : flags) {
            String name = (String) getNameMethod.invoke(getVMOptionMethod.invoke(flag));
            CompositeData option = (CompositeData) ManagementFactory.getPlatformMBeanServer()
                    .invoke(hotSpotDiagnostic, "getVMOption", new Object[] {name},
                            new String[] {"java.lang.String"});

            String value = (String) option.get("value");
            String origin = (String) option.get("origin");
            options.add(new VMOption(name, value, origin));
        }
        return mapper.writeValueAsString(VMOption.orderingByName.sortedCopy(options));
    }

    private boolean isAtLeastJdk6() {
        return !System.getProperty("java.version").startsWith("1.5");
    }

    private static class VMOption {

        private static final Ordering<VMOption> orderingByName = new Ordering<VMOption>() {
            @Override
            public int compare(@Nullable VMOption left, @Nullable VMOption right) {
                assertNonNull(left, "Ordering of non-null elements only");
                assertNonNull(right, "Ordering of non-null elements only");
                return left.name.compareTo(right.name);
            }
        };

        private final String name;
        private final Object value;
        private final String origin;

        private VMOption(String name, Object value, String origin) {
            this.name = name;
            this.value = value;
            this.origin = origin;
        }

        @UsedByReflection
        public String getName() {
            return name;
        }

        @UsedByReflection
        public Object getValue() {
            return value;
        }

        @UsedByReflection
        public String getOrigin() {
            return origin;
        }
    }
}
