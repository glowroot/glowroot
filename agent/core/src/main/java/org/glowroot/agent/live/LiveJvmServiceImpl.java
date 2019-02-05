/*
 * Copyright 2015-2019 the original author or authors.
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

import java.io.File;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.lang.reflect.Array;
import java.lang.reflect.Method;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import javax.management.InstanceNotFoundException;
import javax.management.MBeanAttributeInfo;
import javax.management.MBeanInfo;
import javax.management.MBeanServer;
import javax.management.ObjectName;
import javax.management.QueryExp;
import javax.management.openmbean.CompositeData;
import javax.management.openmbean.CompositeDataSupport;
import javax.management.openmbean.CompositeType;
import javax.management.openmbean.OpenType;
import javax.management.openmbean.TabularData;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Joiner;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Lists;
import com.google.common.collect.Ordering;
import com.google.common.collect.Sets;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.glowroot.agent.config.ConfigService;
import org.glowroot.agent.impl.TraceCollector;
import org.glowroot.agent.impl.TransactionRegistry;
import org.glowroot.agent.util.JavaVersion;
import org.glowroot.agent.util.LazyPlatformMBeanServer;
import org.glowroot.common.live.LiveJvmService;
import org.glowroot.common.util.Clock;
import org.glowroot.common.util.Masking;
import org.glowroot.common.util.Throwables;
import org.glowroot.wire.api.model.DownstreamServiceOuterClass.Availability;
import org.glowroot.wire.api.model.DownstreamServiceOuterClass.Capabilities;
import org.glowroot.wire.api.model.DownstreamServiceOuterClass.HeapDumpFileInfo;
import org.glowroot.wire.api.model.DownstreamServiceOuterClass.HeapHistogram;
import org.glowroot.wire.api.model.DownstreamServiceOuterClass.MBeanDump;
import org.glowroot.wire.api.model.DownstreamServiceOuterClass.MBeanDumpRequest.MBeanDumpKind;
import org.glowroot.wire.api.model.DownstreamServiceOuterClass.MBeanMeta;
import org.glowroot.wire.api.model.DownstreamServiceOuterClass.ThreadDump;

import static com.google.common.base.Preconditions.checkNotNull;

public class LiveJvmServiceImpl implements LiveJvmService {

    private static final Logger logger = LoggerFactory.getLogger(LiveJvmServiceImpl.class);

    private static final String HOT_SPOT_DIAGNOSTIC_MBEAN_NAME =
            "com.sun.management:type=HotSpotDiagnostic";

    private static final @Nullable Long PROCESS_ID =
            parseProcessId(ManagementFactory.getRuntimeMXBean().getName());

    private static final ImmutableSet<String> numericAttributeTypes =
            ImmutableSet.of("long", "int", "double", "float", "java.lang.Long", "java.lang.Integer",
                    "java.lang.Double", "java.lang.Float");

    private final LazyPlatformMBeanServer lazyPlatformMBeanServer;
    private final ThreadDumpService threadDumpService;
    private final Availability threadAllocatedBytesAvailability;
    private final ConfigService configService;
    private final @Nullable File glowrootJarFile;
    private final Clock clock;

    public LiveJvmServiceImpl(LazyPlatformMBeanServer lazyPlatformMBeanServer,
            TransactionRegistry transactionRegistry, TraceCollector traceCollector,
            Availability threadAllocatedBytesAvailability, ConfigService configService,
            @Nullable File glowrootJarFile, Clock clock) {
        this.lazyPlatformMBeanServer = lazyPlatformMBeanServer;
        threadDumpService = new ThreadDumpService(transactionRegistry, traceCollector);
        this.threadAllocatedBytesAvailability = threadAllocatedBytesAvailability;
        this.configService = configService;
        this.glowrootJarFile = glowrootJarFile;
        this.clock = clock;
    }

    @Override
    public boolean isAvailable(String agentId) {
        return true;
    }

    @Override
    public ThreadDump getThreadDump(String agentId) {
        return threadDumpService.getThreadDump();
    }

    @Override
    public String getJstack(String agentId) throws Exception {
        if (JavaVersion.isJ9Jvm()) {
            throw new UnavailableDueToRunningInJ9JvmException();
        }
        if (JavaVersion.isGreaterThanOrEqualToJava8()) {
            return JStackTool.run(lazyPlatformMBeanServer);
        } else {
            long pid = checkNotNull(LiveJvmServiceImpl.getProcessId());
            return JStackTool.runPriorToJava8(pid, allowAttachSelf(), glowrootJarFile);
        }
    }

    @Override
    public long getAvailableDiskSpace(String agentId, String directory)
            throws DirectoryDoesNotExistException {
        File dir = new File(directory);
        if (!dir.exists() || !dir.isDirectory()) {
            throw new DirectoryDoesNotExistException();
        }
        return dir.getFreeSpace();
    }

    @Override
    public HeapDumpFileInfo heapDump(String agentId, String directory) throws Exception {
        File dir = new File(directory);
        if (!dir.exists() || !dir.isDirectory()) {
            throw new DirectoryDoesNotExistException();
        }
        File file;
        if (JavaVersion.isJ9Jvm()) {
            file = j9HeapDump(dir);
        } else {
            file = heapDump(dir);
        }
        return HeapDumpFileInfo.newBuilder()
                .setFilePath(file.getAbsolutePath())
                .setFileSizeBytes(file.length())
                .build();
    }

    @Override
    public HeapHistogram heapHistogram(String agentId) throws Exception {
        if (JavaVersion.isJ9Jvm()) {
            throw new UnavailableDueToRunningInJ9JvmException();
        }
        if (JavaVersion.isGreaterThanOrEqualToJava8()) {
            return HeapHistogramTool.run(lazyPlatformMBeanServer);
        } else {
            long pid = checkNotNull(LiveJvmServiceImpl.getProcessId());
            return HeapHistogramTool.runPriorToJava8(pid, allowAttachSelf(), glowrootJarFile);
        }
    }

    @Override
    public boolean isExplicitGcDisabled(String agentId) throws Exception {
        if (JavaVersion.isGreaterThanOrEqualToJava10()) {
            // in Java 10+, no longer restricted by DisableExplicitGC due to
            // https://bugs.openjdk.java.net/browse/JDK-8186902
            return false;
        }
        boolean disabled = false;
        for (String jvmArg : ManagementFactory.getRuntimeMXBean().getInputArguments()) {
            if (jvmArg.equals("-XX:+DisableExplicitGC")) {
                disabled = true;
            } else if (jvmArg.equals("-XX:-DisableExplicitGC")) {
                disabled = false;
            }
        }
        // last one wins
        return disabled;
    }

    @Override
    public void forceGC(String agentId) throws Exception {
        if (JavaVersion.isGreaterThanOrEqualToJava8()) {
            // this is a new way to force GC in Java 8+, which has benefit that in Java 10+, this is
            // longer restricted by DisableExplicitGC due to
            // https://bugs.openjdk.java.net/browse/JDK-8186902
            ObjectName objectName =
                    ObjectName.getInstance("com.sun.management:type=DiagnosticCommand");
            lazyPlatformMBeanServer.invoke(objectName, "gcRun", new Object[] {null},
                    new String[] {"[Ljava.lang.String;"});
        } else {
            System.gc();
        }
    }

    @Override
    public MBeanDump getMBeanDump(String agentId, MBeanDumpKind mbeanDumpKind,
            List<String> objectNames) throws Exception {
        switch (mbeanDumpKind) {
            case ALL_MBEANS_INCLUDE_ATTRIBUTES:
                throw new UnsupportedOperationException("Not implemented yet");
            case ALL_MBEANS_INCLUDE_ATTRIBUTES_FOR_SOME:
                return MBeanDump.newBuilder()
                        .addAllMbeanInfo(getAllMBeanInfos(objectNames))
                        .build();
            case SOME_MBEANS_INCLUDE_ATTRIBUTES:
                return MBeanDump.newBuilder()
                        .addAllMbeanInfo(getSomeMBeanInfos(objectNames))
                        .build();
            default:
                throw new IllegalStateException("Unexpected mbean dump kind: " + mbeanDumpKind);
        }
    }

    private File heapDump(File directory) throws Exception {
        File file = generateHeapDumpFileName(directory, ".hprof");
        ObjectName objectName = ObjectName.getInstance(HOT_SPOT_DIAGNOSTIC_MBEAN_NAME);
        lazyPlatformMBeanServer.invoke(objectName, "dumpHeap",
                new Object[] {file.getAbsolutePath(), false},
                new String[] {"java.lang.String", "boolean"});
        return file;
    }

    private List<MBeanDump.MBeanInfo> getAllMBeanInfos(List<String> includeAttrsForObjectNames)
            throws Exception {
        List<MBeanServer> mbeanServers = lazyPlatformMBeanServer.findAllMBeanServers();
        Set<ObjectName> objectNames = lazyPlatformMBeanServer.queryNames(null, null, mbeanServers);
        List<MBeanDump.MBeanInfo> mbeanInfos = Lists.newArrayList();
        for (ObjectName objectName : objectNames) {
            String name = objectName.toString();
            if (includeAttrsForObjectNames.contains(name)) {
                mbeanInfos.add(MBeanDump.MBeanInfo.newBuilder()
                        .setObjectName(name)
                        .addAllAttribute(getMBeanAttributes(objectName))
                        .build());
            } else {
                mbeanInfos.add(MBeanDump.MBeanInfo.newBuilder()
                        .setObjectName(name)
                        .build());
            }
        }
        return mbeanInfos;
    }

    private List<MBeanDump.MBeanInfo> getSomeMBeanInfos(List<String> includeObjectNames)
            throws Exception {
        List<MBeanDump.MBeanInfo> mbeanInfos = Lists.newArrayList();
        for (String objectName : includeObjectNames) {
            mbeanInfos.add(MBeanDump.MBeanInfo.newBuilder()
                    .setObjectName(objectName)
                    .addAllAttribute(getMBeanAttributes(new ObjectName(objectName)))
                    .build());
        }
        return mbeanInfos;
    }

    @Override
    public List<String> getMatchingMBeanObjectNames(String agentId, String partialObjectName,
            int limit) throws Exception {
        ObjectNameQueryExp queryExp = new ObjectNameQueryExp(partialObjectName);
        List<MBeanServer> mbeanServers = lazyPlatformMBeanServer.findAllMBeanServers();
        Set<ObjectName> objectNames =
                lazyPlatformMBeanServer.queryNames(null, queryExp, mbeanServers);
        // unfortunately Wildfly returns lots of mbean object names without checking them against
        // the query (see TODO comment in org.jboss.as.jmx.model.ModelControllerMBeanHelper)
        // so must re-filter
        List<String> names = Lists.newArrayList();
        for (ObjectName objectName : objectNames) {
            String objectNameStr = objectName.toString();
            if (queryExp.apply(objectNameStr)) {
                names.add(objectNameStr);
            }
        }
        ImmutableList<String> sortedNames = Ordering.natural().immutableSortedCopy(names);
        if (sortedNames.size() > limit) {
            sortedNames = sortedNames.subList(0, limit);
        }
        return sortedNames;
    }

    @Override
    public MBeanMeta getMBeanMeta(String agentId, String mbeanObjectName) throws Exception {
        Set<ObjectName> objectNames = getObjectNames(mbeanObjectName);
        ImmutableList<String> attributeNames =
                Ordering.natural().immutableSortedCopy(getAttributeNames(objectNames));
        return MBeanMeta.newBuilder()
                .setNoMatchFound(objectNames.isEmpty())
                .addAllAttributeName(attributeNames)
                .build();
    }

    @Override
    public Map<String, String> getSystemProperties(String agentId) {
        Map<String, String> systemProperties =
                ManagementFactory.getRuntimeMXBean().getSystemProperties();
        List<String> maskSystemProperties = configService.getJvmConfig().maskSystemProperties();
        return Masking.maskSystemProperties(systemProperties, maskSystemProperties);
    }

    @Override
    public long getCurrentTime(String agentId) {
        return clock.currentTimeMillis();
    }

    @Override
    public Capabilities getCapabilities(String agentId) {
        return Capabilities.newBuilder()
                .setThreadCpuTime(getThreadCpuTimeAvailability())
                .setThreadContentionTime(getThreadContentionAvailability())
                .setThreadAllocatedBytes(threadAllocatedBytesAvailability)
                .build();
    }

    private List<MBeanDump.MBeanAttribute> getMBeanAttributes(ObjectName objectName)
            throws Exception {
        List<MBeanServer> mbeanServers = lazyPlatformMBeanServer.findAllMBeanServers();
        MBeanInfo mBeanInfo = lazyPlatformMBeanServer.getMBeanInfo(objectName, mbeanServers);
        List<Pattern> maskPatterns =
                Masking.buildPatternList(configService.getJvmConfig().maskMBeanAttributes());
        List<MBeanDump.MBeanAttribute> attributes = Lists.newArrayList();
        for (MBeanAttributeInfo attribute : mBeanInfo.getAttributes()) {
            Object value;
            String attributeName = attribute.getName();
            String fullAttributeName = objectName.toString() + ":" + attribute.getName();
            if (Masking.matchesAny(fullAttributeName, maskPatterns)) {
                value = Masking.MASKED_VALUE;
            } else {
                try {
                    value = lazyPlatformMBeanServer.getAttribute(objectName, attributeName,
                            mbeanServers);
                } catch (Exception e) {
                    // log exception at debug level
                    logger.debug(e.getMessage(), e);
                    value = "<" + Throwables.getBestMessage(e) + ">";
                }
            }
            attributes.add(MBeanDump.MBeanAttribute.newBuilder()
                    .setName(attributeName)
                    .setValue(getMBeanAttributeValue(value))
                    .build());
        }
        return attributes;
    }

    public static @Nullable Long getProcessId() {
        return PROCESS_ID;
    }

    @VisibleForTesting
    static @Nullable Long parseProcessId(String runtimeName) {
        int index = runtimeName.indexOf('@');
        if (index > 0) {
            String pid = runtimeName.substring(0, index);
            try {
                return Long.parseLong(pid);
            } catch (NumberFormatException e) {
                logger.debug(e.getMessage(), e);
                return null;
            }
        } else {
            return null;
        }
    }

    private static boolean allowAttachSelf() {
        if (JavaVersion.isJRockitJvm()) {
            // self attach sporadically logs annoying:
            // [ERROR][attach ] Failed to flush the destination file
            // [ERROR][attach ] OS message: The pipe has been ended (109)
            return false;
        }
        if (JavaVersion.isOSX()) {
            // self attach starts throwing IOException at some point
            // see https://github.com/glowroot/glowroot/issues/424
            return false;
        }
        return !JavaVersion.isGreaterThanOrEqualToJava9()
                || Boolean.getBoolean("jdk.attach.allowAttachSelf");
    }

    private static File j9HeapDump(File directory) throws Exception {
        File file = generateHeapDumpFileName(directory, ".phd");
        Class<?> clazz = Class.forName("com.ibm.jvm.Dump");
        Method method = clazz.getMethod("heapDumpToFile", String.class);
        String actualHeapDumpPath =
                (String) checkNotNull(method.invoke(null, file.getAbsolutePath()));
        return new File(actualHeapDumpPath);
    }

    private static File generateHeapDumpFileName(File dir, String extension) {
        String timestamp = new SimpleDateFormat("yyyyMMdd-HHmmss").format(new Date());
        File file = new File(dir, "heap-dump-" + timestamp + extension);
        int i = 1;
        while (file.exists()) {
            // this seems unlikely now that timestamp is included in file name
            i++;
            file = new File(dir, "heap-dump-" + timestamp + "-" + i + extension);
        }
        return file;
    }

    // see list of allowed attribute value types:
    // http://docs.oracle.com/javase/7/docs/api/javax/management/openmbean/OpenType.html
    // #ALLOWED_CLASSNAMES_LIST
    private static MBeanDump.MBeanValue getMBeanAttributeValue(@Nullable Object value) {
        if (value == null) {
            return MBeanDump.MBeanValue.newBuilder()
                    .setNull(true)
                    .build();
        } else if (value instanceof CompositeData) {
            return getCompositeDataValue((CompositeData) value);
        } else if (value instanceof TabularData) {
            return getTabularDataValue((TabularData) value);
        } else if (value.getClass().isArray()) {
            return getArrayValue(value);
        } else if (value instanceof Boolean) {
            return MBeanDump.MBeanValue.newBuilder()
                    .setBoolean((Boolean) value)
                    .build();
        } else if (value instanceof Long) {
            return MBeanDump.MBeanValue.newBuilder()
                    .setLong((Long) value)
                    .build();
        } else if (value instanceof Integer) {
            return MBeanDump.MBeanValue.newBuilder()
                    .setLong((Integer) value)
                    .build();
        } else if (value instanceof Number) {
            return MBeanDump.MBeanValue.newBuilder()
                    .setDouble(((Number) value).doubleValue())
                    .build();
        } else {
            return MBeanDump.MBeanValue.newBuilder()
                    .setString(checkNotNull(value.toString()))
                    .build();
        }
    }

    private static MBeanDump.MBeanValue getCompositeDataValue(CompositeData compositeData) {
        // linked hash map used to preserve attribute ordering
        List<MBeanDump.MBeanValueMapEntry> entries = Lists.newArrayList();
        for (String key : compositeData.getCompositeType().keySet()) {
            entries.add(MBeanDump.MBeanValueMapEntry.newBuilder()
                    .setKey(key)
                    .setValue(getMBeanAttributeValue(compositeData.get(key)))
                    .build());
        }
        return MBeanDump.MBeanValue.newBuilder()
                .setMap(MBeanDump.MBeanValueMap.newBuilder()
                        .addAllEntry(entries))
                .build();
    }

    private static MBeanDump.MBeanValue getTabularDataValue(TabularData tabularData) {
        // linked hash map used to preserve row ordering
        List<MBeanDump.MBeanValueMapEntry> outerEntries = Lists.newArrayList();
        Set<String> attributeNames = tabularData.getTabularType().getRowType().keySet();
        for (Object key : tabularData.keySet()) {
            // TabularData.keySet() returns "Set<List<?>> but is declared Set<?> for
            // compatibility reasons" (see javadocs) so safe to cast to List<?>
            List<?> keyList = (List<?>) key;
            @SuppressWarnings("argument.type.incompatible")
            String keyString = Joiner.on(", ").join(keyList);
            @SuppressWarnings("argument.type.incompatible")
            CompositeData compositeData = tabularData.get(keyList.toArray());
            // linked hash map used to preserve attribute ordering
            List<MBeanDump.MBeanValueMapEntry> innerEntries = Lists.newArrayList();
            for (String attributeName : attributeNames) {
                innerEntries.add(MBeanDump.MBeanValueMapEntry.newBuilder()
                        .setKey(attributeName)
                        .setValue(getMBeanAttributeValue(compositeData.get(attributeName)))
                        .build());
            }
            outerEntries.add(MBeanDump.MBeanValueMapEntry.newBuilder()
                    .setKey(keyString)
                    .setValue(MBeanDump.MBeanValue.newBuilder()
                            .setMap(MBeanDump.MBeanValueMap.newBuilder()
                                    .addAllEntry(innerEntries))
                            .build())
                    .build());
        }
        return MBeanDump.MBeanValue.newBuilder()
                .setMap(MBeanDump.MBeanValueMap.newBuilder()
                        .addAllEntry(outerEntries))
                .build();
    }

    private static MBeanDump.MBeanValue getArrayValue(Object value) {
        int length = Array.getLength(value);
        List<MBeanDump.MBeanValue> values = Lists.newArrayListWithCapacity(length);
        for (int i = 0; i < length; i++) {
            Object val = Array.get(value, i);
            values.add(getMBeanAttributeValue(val));
        }
        return MBeanDump.MBeanValue.newBuilder()
                .setList(MBeanDump.MBeanValueList.newBuilder()
                        .addAllValue(values))
                .build();
    }

    private Set<ObjectName> getObjectNames(String mbeanObjectName) throws Exception {
        ObjectName objectName = ObjectName.getInstance(mbeanObjectName);
        List<MBeanServer> mbeanServers = lazyPlatformMBeanServer.findAllMBeanServers();
        if (objectName.isPattern()) {
            return lazyPlatformMBeanServer.queryNames(objectName, null, mbeanServers);
        } else {
            try {
                lazyPlatformMBeanServer.getMBeanInfo(objectName, mbeanServers);
            } catch (InstanceNotFoundException e) {
                return ImmutableSet.of();
            }
            return ImmutableSet.of(objectName);
        }
    }

    private Set<String> getAttributeNames(Set<ObjectName> objectNames) throws Exception {
        List<MBeanServer> mbeanServers = lazyPlatformMBeanServer.findAllMBeanServers();
        Set<String> attributeNames = Sets.newHashSet();
        for (ObjectName objectName : objectNames) {
            try {
                MBeanInfo mbeanInfo =
                        lazyPlatformMBeanServer.getMBeanInfo(objectName, mbeanServers);
                attributeNames.addAll(getAttributeNames(mbeanInfo, objectName));
            } catch (Exception e) {
                // log exception at debug level
                logger.debug(e.getMessage(), e);
            }
        }
        return attributeNames;
    }

    private Set<String> getAttributeNames(MBeanInfo mbeanInfo, ObjectName objectName)
            throws Exception {
        List<MBeanServer> mbeanServers = lazyPlatformMBeanServer.findAllMBeanServers();
        Set<String> attributeNames = Sets.newHashSet();
        for (MBeanAttributeInfo attribute : mbeanInfo.getAttributes()) {
            if (attribute.isReadable()) {
                try {
                    Object value = lazyPlatformMBeanServer.getAttribute(objectName,
                            attribute.getName(), mbeanServers);
                    addNumericAttributes(attribute, value, attributeNames);
                } catch (Exception e) {
                    // log exception at debug level
                    logger.debug(e.getMessage(), e);
                }
            }
        }
        return attributeNames;
    }

    private static void addNumericAttributes(MBeanAttributeInfo attribute, Object value,
            Set<String> attributeNames) {
        String attributeType = attribute.getType();
        if (numericAttributeTypes.contains(attributeType)
                || attributeType.equals("java.lang.Object") && value instanceof Number) {
            attributeNames.add(attribute.getName());
        } else if (attributeType.equals("java.lang.String") && value instanceof String) {
            try {
                Double.parseDouble((String) value);
                attributeNames.add(attribute.getName());
            } catch (NumberFormatException e) {
                // log exception at debug level
                logger.debug(e.getMessage(), e);
            }
        } else if (attributeType.equals(CompositeData.class.getName())) {
            Object openType = attribute.getDescriptor().getFieldValue("openType");
            CompositeType compositeType = null;
            if (openType instanceof CompositeType) {
                compositeType = (CompositeType) openType;
            } else if (openType == null && value instanceof CompositeDataSupport) {
                compositeType = ((CompositeDataSupport) value).getCompositeType();
            }
            if (compositeType != null) {
                attributeNames
                        .addAll(getCompositeTypeAttributeNames(attribute, value, compositeType));
            }
        }
    }

    private static List<String> getCompositeTypeAttributeNames(MBeanAttributeInfo attribute,
            Object compositeData, CompositeType compositeType) {
        List<String> attributeNames = Lists.newArrayList();
        for (String itemName : compositeType.keySet()) {
            OpenType<?> itemType = compositeType.getType(itemName);
            if (itemType == null) {
                continue;
            }
            String className = itemType.getClassName();
            Class<?> clazz;
            try {
                clazz = Class.forName(className);
            } catch (ClassNotFoundException e) {
                logger.warn(e.getMessage(), e);
                continue;
            }
            if (Number.class.isAssignableFrom(clazz)) {
                attributeNames.add(attribute.getName() + '.' + itemName);
            } else if (clazz == String.class && compositeData instanceof CompositeData) {
                Object val = ((CompositeData) compositeData).get(itemName);
                if (val instanceof String) {
                    try {
                        Double.parseDouble((String) val);
                        attributeNames.add(attribute.getName() + '.' + itemName);
                    } catch (NumberFormatException e) {
                        // log exception at debug level
                        logger.debug(e.getMessage(), e);
                    }
                }
            }
        }
        return attributeNames;
    }

    private static Availability getThreadCpuTimeAvailability() {
        ThreadMXBean threadMXBean = ManagementFactory.getThreadMXBean();
        if (!threadMXBean.isThreadCpuTimeSupported()) {
            return Availability.newBuilder()
                    .setAvailable(false)
                    .setReason("java.lang.management.ThreadMXBean"
                            + ".isThreadCpuTimeSupported() returned false")
                    .build();
        }
        if (!threadMXBean.isThreadCpuTimeEnabled()) {
            return Availability.newBuilder()
                    .setAvailable(false)
                    .setReason("java.lang.management.ThreadMXBean"
                            + ".isThreadCpuTimeEnabled() returned false")
                    .build();
        }
        return Availability.newBuilder().setAvailable(true).build();
    }

    private static Availability getThreadContentionAvailability() {
        ThreadMXBean threadMXBean = ManagementFactory.getThreadMXBean();
        if (!threadMXBean.isThreadContentionMonitoringSupported()) {
            return Availability.newBuilder()
                    .setAvailable(false)
                    .setReason("java.lang.management.ThreadMXBean"
                            + ".isThreadContentionMonitoringSupported() returned false")
                    .build();
        }
        if (!threadMXBean.isThreadContentionMonitoringEnabled()) {
            return Availability.newBuilder()
                    .setAvailable(false)
                    .setReason("java.lang.management.ThreadMXBean"
                            + ".isThreadContentionMonitoringEnabled() returned false")
                    .build();
        }
        return Availability.newBuilder().setAvailable(true).build();
    }

    @SuppressWarnings("serial")
    private static class ObjectNameQueryExp implements QueryExp {

        private final String textUpper;

        private ObjectNameQueryExp(String text) {
            this.textUpper = text.toUpperCase(Locale.ENGLISH);
        }

        @Override
        public boolean apply(ObjectName name) {
            return apply(name.toString());
        }

        private boolean apply(String nameStr) {
            return nameStr.toUpperCase(Locale.ENGLISH).contains(textUpper);
        }

        @Override
        public void setMBeanServer(MBeanServer s) {}
    }
}
