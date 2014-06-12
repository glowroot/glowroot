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
package org.glowroot.local.ui;

import java.io.File;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.lang.management.RuntimeMXBean;
import java.lang.management.ThreadInfo;
import java.lang.management.ThreadMXBean;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Enumeration;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Properties;

import javax.management.JMException;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.base.Objects;
import com.google.common.base.Splitter;
import com.google.common.base.Strings;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.Ordering;
import com.google.common.io.CharStreams;
import com.google.common.primitives.Ints;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.glowroot.common.ObjectMappers;
import org.glowroot.jvm.HeapDumps;
import org.glowroot.jvm.HeapHistograms;
import org.glowroot.jvm.HeapHistograms.HeapHistogramException;
import org.glowroot.jvm.OptionalService;
import org.glowroot.jvm.OptionalService.Availability;
import org.glowroot.jvm.ProcessId;
import org.glowroot.jvm.ThreadAllocatedBytes;
import org.glowroot.markers.Singleton;
import org.glowroot.markers.UsedByJsonBinding;

import static com.google.common.base.Preconditions.checkNotNull;
import static org.glowroot.common.ObjectMappers.checkRequiredProperty;

/**
 * Json service to read jvm info.
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
            checkNotNull(left);
            checkNotNull(right);
            return Ints.compare(right.getStackTrace().length, left.getStackTrace().length);
        }
    };

    private final OptionalService<ThreadAllocatedBytes> threadAllocatedBytes;
    private final OptionalService<HeapHistograms> heapHistograms;
    private final OptionalService<HeapDumps> heapDumps;

    JvmJsonService(OptionalService<ThreadAllocatedBytes> threadAllocatedBytes,
            OptionalService<HeapHistograms> heapHistograms, OptionalService<HeapDumps> heapDumps) {
        this.threadAllocatedBytes = threadAllocatedBytes;
        this.heapHistograms = heapHistograms;
        this.heapDumps = heapDumps;
    }

    @GET("/backend/jvm/process")
    String getProcess() throws IOException, JMException {
        logger.debug("getProcess()");
        String pid = ProcessId.getPid();
        String command = System.getProperty("sun.java.command");
        String mainClass = null;
        List<String> arguments = ImmutableList.of();
        if (command != null) {
            int index = command.indexOf(' ');
            if (index > 0) {
                mainClass = command.substring(0, index);
                arguments =
                        Lists.newArrayList(Splitter.on(' ').split(command.substring(index + 1)));
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
        jg.writeStartObject();
        jg.writeNumberField("startTime", runtimeMXBean.getStartTime());
        jg.writeNumberField("uptime", runtimeMXBean.getUptime());
        jg.writeStringField("pid", Objects.firstNonNull(pid, "<unknown>"));
        jg.writeStringField("mainClass", mainClass);
        jg.writeFieldName("mainClassArguments");
        mapper.writeValue(jg, arguments);
        jg.writeStringField("jvm", jvm);
        jg.writeStringField("java", java);
        jg.writeStringField("javaHome", javaHome);
        jg.writeFieldName("jvmArguments");
        mapper.writeValue(jg, runtimeMXBean.getInputArguments());
        jg.writeEndObject();
        jg.close();
        return sb.toString();
    }

    @GET("/backend/jvm/system-properties")
    String getSystemProperties() throws IOException {
        logger.debug("getSystemProperties()");
        Properties properties = System.getProperties();
        Map<String, String> sortedProperties =
                Maps.<String, String, String>newTreeMap(String.CASE_INSENSITIVE_ORDER);
        for (Enumeration<?> e = properties.propertyNames(); e.hasMoreElements();) {
            Object obj = e.nextElement();
            if (obj instanceof String) {
                String propertyName = (String) obj;
                String propertyValue = properties.getProperty(propertyName);
                if (propertyValue != null) {
                    sortedProperties.put(propertyName, propertyValue);
                }
            }
        }
        StringBuilder sb = new StringBuilder();
        JsonGenerator jg = mapper.getFactory().createGenerator(CharStreams.asWriter(sb));
        jg.writeStartArray();
        for (Entry<String, String> entry : sortedProperties.entrySet()) {
            jg.writeStartObject();
            jg.writeStringField("name", entry.getKey());
            jg.writeStringField("value", entry.getValue());
            jg.writeEndObject();
        }
        jg.writeEndArray();
        jg.close();
        return sb.toString();
    }

    @POST("/backend/jvm/perform-gc")
    void performGC() throws IOException {
        logger.debug("performGC()");
        // using MemoryMXBean.gc() instead of System.gc() in hope that it will someday bypass
        // -XX:+DisableExplicitGC (see https://bugs.openjdk.java.net/browse/JDK-6396411)
        ManagementFactory.getMemoryMXBean().gc();
    }

    @GET("/backend/jvm/thread-dump")
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
            jg.writeStringField("state", threadInfo.getThreadState().name());
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

    @GET("/backend/jvm/heap-histogram")
    String getHeapHistogram() throws HeapHistogramException {
        logger.debug("getHeapHistogram()");
        HeapHistograms service = OptionalJsonServices.validateAvailability(heapHistograms);
        return service.heapHistogramJson();
    }

    @GET("/backend/jvm/heap-dump-defaults")
    String getHeapDumpDefaults() throws IOException, JMException {
        logger.debug("getHeapDumpDefaults()");
        String heapDumpPath = getHeapDumpPathFromCommandLine();
        if (Strings.isNullOrEmpty(heapDumpPath)) {
            heapDumpPath = new File(System.getProperty("java.io.tmpdir")).getAbsolutePath();
        }
        StringBuilder sb = new StringBuilder();
        JsonGenerator jg = mapper.getFactory().createGenerator(CharStreams.asWriter(sb));
        jg.writeStartObject();
        jg.writeStringField("directory", heapDumpPath);
        jg.writeEndObject();
        jg.close();
        return sb.toString();
    }

    @POST("/backend/jvm/check-disk-space")
    String checkDiskSpace(String content) throws IOException {
        logger.debug("checkDiskSpace(): content={}", content);
        RequestWithDirectory request =
                ObjectMappers.readRequiredValue(mapper, content, RequestWithDirectory.class);
        File dir = new File(request.getDirectory());
        if (!dir.exists()) {
            return "{\"error\": \"Directory doesn't exist\"}";
        }
        if (!dir.isDirectory()) {
            return "{\"error\": \"Path is not a directory\"}";
        }
        long diskSpace = new File(request.getDirectory()).getFreeSpace();
        return Long.toString(diskSpace);
    }

    @POST("/backend/jvm/dump-heap")
    String dumpHeap(String content) throws IOException, JMException {
        logger.debug("dumpHeap(): content={}", content);
        HeapDumps service = OptionalJsonServices.validateAvailability(heapDumps);
        RequestWithDirectory request =
                ObjectMappers.readRequiredValue(mapper, content, RequestWithDirectory.class);
        File dir = new File(request.getDirectory());
        if (!dir.exists()) {
            return "{\"error\": \"Directory doesn't exist\"}";
        }
        if (!dir.isDirectory()) {
            return "{\"error\": \"Path is not a directory\"}";
        }
        String timestamp = new SimpleDateFormat("yyyyMMdd-HHmmss").format(new Date());
        File file = new File(dir, "heap-dump-" + timestamp + ".hprof");
        int i = 1;
        while (file.exists()) {
            // this seems unlikely now that timestamp is included in filename
            i++;
            file = new File(dir, "heap-dump-" + timestamp + "-" + i + ".hprof");
        }
        service.dumpHeap(file.getAbsolutePath());

        StringBuilder sb = new StringBuilder();
        JsonGenerator jg = mapper.getFactory().createGenerator(CharStreams.asWriter(sb));
        jg.writeStartObject();
        jg.writeStringField("filename", file.getAbsolutePath());
        jg.writeNumberField("size", file.length());
        jg.writeEndObject();
        jg.close();
        return sb.toString();
    }

    @GET("/backend/jvm/capabilities")
    String getCapabilities() throws IOException {
        logger.debug("getCapabilities()");
        StringBuilder sb = new StringBuilder();
        JsonGenerator jg = mapper.getFactory().createGenerator(CharStreams.asWriter(sb));
        jg.writeStartObject();
        jg.writeFieldName("threadCpuTime");
        mapper.writeValue(jg, getThreadCpuTimeAvailability());
        jg.writeFieldName("threadContentionTime");
        mapper.writeValue(jg, getThreadContentionAvailability());
        jg.writeFieldName("threadAllocatedBytes");
        mapper.writeValue(jg, threadAllocatedBytes.getAvailability());
        jg.writeFieldName("heapHistogram");
        mapper.writeValue(jg, heapHistograms.getAvailability());
        jg.writeFieldName("heapDump");
        mapper.writeValue(jg, heapDumps.getAvailability());
        jg.writeEndObject();
        jg.close();
        return sb.toString();
    }

    private static Availability getThreadCpuTimeAvailability() {
        ThreadMXBean threadMXBean = ManagementFactory.getThreadMXBean();
        if (!threadMXBean.isThreadCpuTimeSupported()) {
            return new Availability(false, "java.lang.management.ThreadMXBean"
                    + ".isThreadCpuTimeSupported() returned false");
        }
        if (!threadMXBean.isThreadCpuTimeEnabled()) {
            return new Availability(false, "java.lang.management.ThreadMXBean"
                    + ".isThreadCpuTimeEnabled() returned false");
        }
        return new Availability(true, "");
    }

    private static Availability getThreadContentionAvailability() {
        ThreadMXBean threadMXBean = ManagementFactory.getThreadMXBean();
        if (!threadMXBean.isThreadContentionMonitoringSupported()) {
            return new Availability(false, "java.lang.management.ThreadMXBean"
                    + ".isThreadContentionMonitoringSupported() returned false");
        }
        if (!threadMXBean.isThreadContentionMonitoringEnabled()) {
            return new Availability(false, "java.lang.management.ThreadMXBean"
                    + ".isThreadContentionMonitoringEnabled() returned false");
        }
        return new Availability(true, "");
    }

    @Nullable
    private static String getHeapDumpPathFromCommandLine() {
        RuntimeMXBean runtimeMXBean = ManagementFactory.getRuntimeMXBean();
        for (String arg : runtimeMXBean.getInputArguments()) {
            if (arg.startsWith("-XX:HeapDumpPath=")) {
                return arg.substring("-XX:HeapDumpPath=".length());
            }
        }
        return null;
    }

    @UsedByJsonBinding
    static class RequestWithDirectory {

        private final String directory;

        @JsonCreator
        RequestWithDirectory(@JsonProperty("directory") @Nullable String directory)
                throws JsonMappingException {
            checkRequiredProperty(directory, "directory");
            this.directory = directory;
        }

        private String getDirectory() {
            return directory;
        }
    }
}
