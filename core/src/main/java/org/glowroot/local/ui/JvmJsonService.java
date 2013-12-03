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
package org.glowroot.local.ui;

import java.io.File;
import java.io.IOException;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.MemoryPoolMXBean;
import java.lang.management.MemoryType;
import java.lang.management.MemoryUsage;
import java.lang.management.RuntimeMXBean;
import java.lang.management.ThreadInfo;
import java.lang.management.ThreadMXBean;
import java.lang.reflect.InvocationTargetException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Enumeration;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Properties;

import javax.management.JMException;

import checkers.nullness.quals.Nullable;
import com.fasterxml.jackson.core.JsonGenerationException;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.common.base.Objects;
import com.google.common.base.Splitter;
import com.google.common.base.Strings;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.Ordering;
import com.google.common.io.CharStreams;
import com.google.common.primitives.Ints;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.glowroot.common.ObjectMappers;
import org.glowroot.jvm.Availability;
import org.glowroot.jvm.Flags;
import org.glowroot.jvm.HeapHistograms;
import org.glowroot.jvm.HotSpotDiagnostic;
import org.glowroot.jvm.HotSpotDiagnostic.VMOption;
import org.glowroot.jvm.JDK6;
import org.glowroot.jvm.ProcessId;
import org.glowroot.jvm.ThreadAllocatedBytes;
import org.glowroot.jvm.ThreadContentionTime;
import org.glowroot.jvm.ThreadCpuTime;
import org.glowroot.markers.Singleton;

import static org.glowroot.common.Nullness.assertNonNull;

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
            return Ints.compare(right.getStackTrace().length, left.getStackTrace().length);
        }
    };

    @GET("/backend/jvm/general")
    String getGeneralInfo() throws IOException, JMException {
        logger.debug("getGeneralInfo()");
        String pid = ProcessId.getPid();
        String command = System.getProperty("sun.java.command");
        String mainClass = null;
        List<String> arguments = ImmutableList.of();
        if (command != null) {
            int index = command.indexOf(' ');
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
        // explicit generic signature Maps.<String, String, String>newTreeMap(...) is needed to work
        // around an OpenJDK 6 type inference bug
        // see https://code.google.com/p/guava-libraries/issues/detail?id=635
        Map<String, String> sortedProperties =
                Maps.<String, String, String>newTreeMap(String.CASE_INSENSITIVE_ORDER);
        for (Enumeration<?> e = properties.propertyNames(); e.hasMoreElements();) {
            Object obj = e.nextElement();
            if (obj instanceof String) {
                String propertyName = (String) obj;
                sortedProperties.put(propertyName, properties.getProperty(propertyName));
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

    @GET("/backend/jvm/memory-overview")
    String getMemoryOverview() throws IOException {
        logger.debug("getMemoryOverview()");
        MemoryMXBean memoryMXBean = ManagementFactory.getMemoryMXBean();
        MemoryUsage heapMemoryUsage = memoryMXBean.getHeapMemoryUsage();
        MemoryUsage nonHeapMemoryUsage = memoryMXBean.getNonHeapMemoryUsage();
        List<MemoryPoolMXBean> memoryPoolMXBeans = ManagementFactory.getMemoryPoolMXBeans();
        List<GarbageCollectorMXBean> garbageCollectorMXBeans =
                ManagementFactory.getGarbageCollectorMXBeans();

        StringBuilder sb = new StringBuilder();
        JsonGenerator jg = mapper.getFactory().createGenerator(CharStreams.asWriter(sb));
        jg.writeStartObject();

        jg.writeFieldName("heapUsage");
        writeMemoryUsage(jg, heapMemoryUsage);
        jg.writeFieldName("nonHeapUsage");
        writeMemoryUsage(jg, nonHeapMemoryUsage);

        jg.writeArrayFieldStart("heapMemoryPools");
        for (MemoryPoolMXBean memoryPoolMXBean : memoryPoolMXBeans) {
            if (memoryPoolMXBean.getType() == MemoryType.HEAP) {
                writeMemoryPool(jg, memoryPoolMXBean);
            }
        }
        jg.writeEndArray();

        jg.writeArrayFieldStart("nonHeapMemoryPools");
        for (MemoryPoolMXBean memoryPoolMXBean : memoryPoolMXBeans) {
            if (memoryPoolMXBean.getType() == MemoryType.NON_HEAP) {
                writeMemoryPool(jg, memoryPoolMXBean);
            }
        }
        jg.writeEndArray();

        jg.writeArrayFieldStart("garbageCollectors");
        for (GarbageCollectorMXBean garbageCollectorMXBean : garbageCollectorMXBeans) {
            jg.writeStartObject();
            jg.writeStringField("name", garbageCollectorMXBean.getName());
            jg.writeNumberField("collectionCount", garbageCollectorMXBean.getCollectionCount());
            jg.writeNumberField("collectionTime", garbageCollectorMXBean.getCollectionTime());
            jg.writeArrayFieldStart("memoryPoolNames");
            for (String name : garbageCollectorMXBean.getMemoryPoolNames()) {
                jg.writeString(name);
            }
            jg.writeEndArray();
            jg.writeEndObject();
        }
        jg.writeEndArray();

        jg.writeEndObject();
        jg.close();
        return sb.toString();
    }

    @POST("/backend/jvm/perform-gc")
    String performGC() throws IOException {
        logger.debug("performGC()");
        System.gc();
        return getMemoryOverview();
    }

    @POST("/backend/jvm/reset-peak-memory-usage")
    String resetPeakMemoryUsage() throws IOException {
        logger.debug("resetPeakMemoryUsage()");
        for (MemoryPoolMXBean memoryPoolMXBean : ManagementFactory.getMemoryPoolMXBeans()) {
            memoryPoolMXBean.resetPeakUsage();
        }
        return getMemoryOverview();
    }

    @GET("/backend/jvm/heap-histogram")
    String getHeapHistogram() throws IOException, SecurityException, NoSuchMethodException,
            IllegalAccessException, InvocationTargetException {
        logger.debug("getHeapHistogram()");
        return HeapHistograms.heapHistogramJson();
    }

    @GET("/backend/jvm/heap-dump-defaults")
    String getHeapDumpDefaults() throws IOException, JMException {
        logger.debug("getHeapDumpDefaults()");
        String heapDumpPath = HotSpotDiagnostic.getVMOption("HeapDumpPath").getValue();
        if (Strings.isNullOrEmpty(heapDumpPath)) {
            heapDumpPath = new File(System.getProperty("java.io.tmpdir")).getAbsolutePath();
        }
        StringBuilder sb = new StringBuilder();
        JsonGenerator jg = mapper.getFactory().createGenerator(CharStreams.asWriter(sb));
        jg.writeStartObject();
        jg.writeStringField("directory", heapDumpPath);
        jg.writeBooleanField("checkDiskSpaceSupported", JDK6.isSupported());
        jg.writeEndObject();
        jg.close();
        return sb.toString();
    }

    @POST("/backend/jvm/check-disk-space")
    String checkDiskSpace(String content) throws IOException, JMException,
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
        long diskSpace = JDK6.getFreeSpace(new File(directory));
        return Long.toString(diskSpace);
    }

    @POST("/backend/jvm/dump-heap")
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
        HotSpotDiagnostic.dumpHeap(file.getAbsolutePath());

        StringBuilder sb = new StringBuilder();
        JsonGenerator jg = mapper.getFactory().createGenerator(CharStreams.asWriter(sb));
        jg.writeStartObject();
        jg.writeStringField("filename", file.getAbsolutePath());
        jg.writeNumberField("size", file.length());
        jg.writeEndObject();
        jg.close();
        return sb.toString();
    }

    @GET("/backend/jvm/manageable-flags")
    String getManageableFlags() throws IOException, JMException {
        logger.debug("getManageableFlags()");
        StringBuilder sb = new StringBuilder();
        JsonGenerator jg = mapper.getFactory().createGenerator(CharStreams.asWriter(sb));
        jg.writeStartArray();
        // don't sort these, there are not many and they seem to be sorted in meaningful order
        // already
        for (VMOption option : HotSpotDiagnostic.getDiagnosticOptions()) {
            // only handle true/false values for now
            if ("true".equals(option.getValue()) || "false".equals(option.getValue())) {
                // filter out seemingly less useful options (keep only HeapDump... and Print...)
                if (option.getName().startsWith("HeapDump")
                        || option.getName().startsWith("Print")) {
                    jg.writeStartObject();
                    jg.writeStringField("name", option.getName());
                    jg.writeBooleanField("value", Boolean.parseBoolean(option.getValue()));
                    jg.writeStringField("origin", option.getOrigin());
                    jg.writeEndObject();
                }
            }
        }
        jg.writeEndArray();
        jg.close();
        return sb.toString();
    }

    @POST("/backend/jvm/update-manageable-flags")
    String updateManageableFlags(String content) throws IOException, JMException {
        logger.debug("updateManageableFlags(): content={}", content);
        Map<String, Object> values =
                mapper.readValue(content, new TypeReference<Map<String, Object>>() {});
        for (Entry<String, Object> value : values.entrySet()) {
            HotSpotDiagnostic.setVMOption(value.getKey(), value.getValue().toString());
        }
        return getManageableFlags();
    }

    @GET("/backend/jvm/all-flags")
    String getAllFlags() throws IOException, JMException, ClassNotFoundException,
            NoSuchMethodException, SecurityException, IllegalAccessException,
            InvocationTargetException {

        logger.debug("getAllFlags()");
        List<VMOption> options = Lists.newArrayList();
        for (String name : Flags.getFlagNames()) {
            options.add(HotSpotDiagnostic.getVMOption(name));
        }
        return mapper.writeValueAsString(VMOption.orderingByName.sortedCopy(options));
    }

    @GET("/backend/jvm/capabilities")
    String getCapabilities() throws JsonGenerationException, IOException {
        logger.debug("getCapabilities()");
        Availability hotSpotDiagnosticAvailability = HotSpotDiagnostic.getAvailability();
        Availability allFlagsAvailability;
        if (!hotSpotDiagnosticAvailability.isAvailable()) {
            allFlagsAvailability = hotSpotDiagnosticAvailability;
        } else {
            allFlagsAvailability = Flags.getAvailability();
        }
        StringBuilder sb = new StringBuilder();
        JsonGenerator jg = mapper.getFactory().createGenerator(CharStreams.asWriter(sb));
        jg.writeStartObject();
        jg.writeFieldName("threadCpuTime");
        mapper.writeValue(jg, ThreadCpuTime.getAvailability());
        jg.writeFieldName("threadContentionTime");
        mapper.writeValue(jg, ThreadContentionTime.getAvailability());
        jg.writeFieldName("threadAllocatedBytes");
        mapper.writeValue(jg, ThreadAllocatedBytes.getAvailability());
        jg.writeFieldName("heapHistogram");
        mapper.writeValue(jg, HeapHistograms.getAvailability());
        jg.writeFieldName("heapDump");
        mapper.writeValue(jg, hotSpotDiagnosticAvailability);
        jg.writeFieldName("manageableFlags");
        mapper.writeValue(jg, hotSpotDiagnosticAvailability);
        jg.writeFieldName("allFlags");
        mapper.writeValue(jg, allFlagsAvailability);
        jg.writeEndObject();
        jg.close();
        return sb.toString();
    }

    private void writeMemoryUsage(JsonGenerator jg, MemoryUsage memoryUsage)
            throws JsonGenerationException, IOException {
        jg.writeStartObject();
        jg.writeNumberField("used", memoryUsage.getUsed());
        jg.writeNumberField("committed", memoryUsage.getCommitted());
        jg.writeNumberField("max", memoryUsage.getMax());
        jg.writeEndObject();
    }

    private void writeMemoryPool(JsonGenerator jg, MemoryPoolMXBean memoryPoolMXBean)
            throws IOException, JsonGenerationException {
        jg.writeStartObject();
        jg.writeStringField("name", memoryPoolMXBean.getName());
        jg.writeStringField("type", memoryPoolMXBean.getType().toString());
        jg.writeFieldName("usage");
        writeMemoryUsage(jg, memoryPoolMXBean.getUsage());
        jg.writeFieldName("peakUsage");
        writeMemoryUsage(jg, memoryPoolMXBean.getPeakUsage());
        MemoryUsage collectionUsage = memoryPoolMXBean.getCollectionUsage();
        if (collectionUsage == null) {
            jg.writeBooleanField("unsupported", true);
        } else {
            jg.writeFieldName("collectionUsage");
            writeMemoryUsage(jg, collectionUsage);
        }
        jg.writeArrayFieldStart("memoryManagerNames");
        for (String name : memoryPoolMXBean.getMemoryManagerNames()) {
            jg.writeString(name);
        }
        jg.writeEndArray();
        jg.writeEndObject();
    }
}
