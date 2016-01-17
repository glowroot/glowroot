/*
 * Copyright 2015-2016 the original author or authors.
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
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.lang.reflect.Array;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import javax.annotation.Nullable;
import javax.management.Descriptor;
import javax.management.MBeanAttributeInfo;
import javax.management.MBeanInfo;
import javax.management.MBeanServer;
import javax.management.ObjectName;
import javax.management.QueryExp;
import javax.management.openmbean.CompositeData;
import javax.management.openmbean.CompositeType;
import javax.management.openmbean.OpenType;
import javax.management.openmbean.TabularData;

import com.google.common.base.Joiner;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Lists;
import com.google.common.collect.Ordering;
import com.google.common.collect.Sets;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.glowroot.agent.impl.TransactionCollector;
import org.glowroot.agent.impl.TransactionRegistry;
import org.glowroot.agent.util.LazyPlatformMBeanServer;
import org.glowroot.common.live.ImmutableAvailability;
import org.glowroot.common.live.ImmutableCapabilities;
import org.glowroot.common.live.ImmutableMBeanMeta;
import org.glowroot.common.live.LiveJvmService;
import org.glowroot.wire.api.model.DownstreamServiceOuterClass.HeapDumpFileInfo;
import org.glowroot.wire.api.model.DownstreamServiceOuterClass.MBeanDump;
import org.glowroot.wire.api.model.DownstreamServiceOuterClass.MBeanDumpRequest;
import org.glowroot.wire.api.model.DownstreamServiceOuterClass.ThreadDump;

public class LiveJvmServiceImpl implements LiveJvmService {

    private static final Logger logger = LoggerFactory.getLogger(LiveJvmServiceImpl.class);

    private static final String HOT_SPOT_DIAGNOSTIC_MBEAN_NAME =
            "com.sun.management:type=HotSpotDiagnostic";

    private static final ImmutableSet<String> numericAttributeTypes =
            ImmutableSet.of("long", "int", "double", "float", "java.lang.Long", "java.lang.Integer",
                    "java.lang.Double", "java.lang.Float");

    private final LazyPlatformMBeanServer lazyPlatformMBeanServer;
    private final ThreadDumpService threadDumpService;
    private final Availability threadAllocatedBytesAvailability;

    public LiveJvmServiceImpl(LazyPlatformMBeanServer lazyPlatformMBeanServer,
            TransactionRegistry transactionRegistry, TransactionCollector transactionCollector,
            Availability threadAllocatedBytesAvailability) {
        this.lazyPlatformMBeanServer = lazyPlatformMBeanServer;
        threadDumpService =
                new ThreadDumpService(transactionRegistry, transactionCollector);
        this.threadAllocatedBytesAvailability = threadAllocatedBytesAvailability;
    }

    @Override
    public ThreadDump getThreadDump(String serverId) {
        return threadDumpService.getThreadDump();
    }

    @Override
    public long getAvailableDiskSpace(String serverId, String directory) throws IOException {
        File dir = new File(directory);
        if (!dir.exists()) {
            throw new IOException("Directory doesn't exist");
        }
        if (!dir.isDirectory()) {
            throw new IOException("Path is not a directory");
        }
        return dir.getFreeSpace();
    }

    @Override
    public HeapDumpFileInfo heapDump(String serverId, String directory) throws Exception {
        File dir = new File(directory);
        if (!dir.exists()) {
            throw new IOException("Directory doesn't exist");
        }
        if (!dir.isDirectory()) {
            throw new IOException("Path is not a directory");
        }
        String timestamp = new SimpleDateFormat("yyyyMMdd-HHmmss").format(new Date());
        File file = new File(dir, "heap-dump-" + timestamp + ".hprof");
        int i = 1;
        while (file.exists()) {
            // this seems unlikely now that timestamp is included in file name
            i++;
            file = new File(dir, "heap-dump-" + timestamp + "-" + i + ".hprof");
        }
        ObjectName objectName = ObjectName.getInstance(HOT_SPOT_DIAGNOSTIC_MBEAN_NAME);
        // logging before and after heap dump in case it crashes the JVM
        logger.warn("heap dump requested...");
        lazyPlatformMBeanServer.invoke(objectName, "dumpHeap",
                new Object[] {file.getAbsolutePath(), false},
                new String[] {"java.lang.String", "boolean"});
        logger.warn(
                "heap dump completed: " + file.getAbsolutePath() + "(" + file.length() + " bytes)");
        return HeapDumpFileInfo.newBuilder()
                .setFilePath(file.getAbsolutePath())
                .setFileSizeBytes(file.length())
                .build();
    }

    @Override
    public void gc(String serverId) {
        // using MemoryMXBean.gc() instead of System.gc() in hope that it will someday bypass
        // -XX:+DisableExplicitGC (see https://bugs.openjdk.java.net/browse/JDK-6396411)
        ManagementFactory.getMemoryMXBean().gc();
    }

    @Override
    public MBeanDump getMBeanDump(String serverId, MBeanDumpRequest request) throws Exception {
        switch (request.getKind()) {
            case ALL_MBEANS_INCLUDE_ATTRIBUTES:
                throw new UnsupportedOperationException("Not implemented yet");
            case ALL_MBEANS_INCLUDE_ATTRIBUTES_FOR_SOME:
                return MBeanDump.newBuilder()
                        .addAllMbeanInfo(getAllMBeanInfos(request.getObjectNameList()))
                        .build();
            case SOME_MBEANS_INCLUDE_ATTRIBUTES:
                return MBeanDump.newBuilder()
                        .addAllMbeanInfo(getSomeMBeanInfos(request.getObjectNameList()))
                        .build();
            default:
                throw new IllegalStateException("Unexpected mbean dump kind: " + request.getKind());
        }
    }

    private List<MBeanDump.MBeanInfo> getAllMBeanInfos(List<String> includeAttrsForObjectNames)
            throws Exception {
        Set<ObjectName> objectNames = lazyPlatformMBeanServer.queryNames(null, null);
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
    public List<String> getMatchingMBeanObjectNames(String serverId, String partialMBeanObjectName,
            int limit) throws InterruptedException {
        ObjectNameQueryExp queryExp = new ObjectNameQueryExp(partialMBeanObjectName);
        Set<ObjectName> objectNames = lazyPlatformMBeanServer.queryNames(null, queryExp);
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
        ImmutableList<String> sortedNames =
                Ordering.from(String.CASE_INSENSITIVE_ORDER).immutableSortedCopy(names);
        if (sortedNames.size() > limit) {
            sortedNames = sortedNames.subList(0, limit);
        }
        return sortedNames;
    }

    @Override
    public MBeanMeta getMBeanMeta(String serverId, String mbeanObjectName) throws Exception {
        Set<ObjectName> objectNames = getObjectNames(mbeanObjectName);
        ImmutableList<String> attributeNames = Ordering.from(String.CASE_INSENSITIVE_ORDER)
                .immutableSortedCopy(getAttributeNames(objectNames));
        boolean pattern = mbeanObjectName.contains("*");
        return ImmutableMBeanMeta.builder()
                .unmatched(objectNames.isEmpty() && pattern)
                .unavailable(objectNames.isEmpty() && !pattern)
                .addAllAttributeNames(attributeNames)
                .build();
    }

    @Override
    public Capabilities getCapabilities(String serverId) {
        return ImmutableCapabilities.builder()
                .threadCpuTime(getThreadCpuTimeAvailability())
                .threadContentionTime(getThreadContentionAvailability())
                .threadAllocatedBytes(threadAllocatedBytesAvailability)
                .build();
    }

    private List<MBeanDump.MBeanAttribute> getMBeanAttributes(ObjectName objectName)
            throws Exception {
        MBeanInfo mBeanInfo = lazyPlatformMBeanServer.getMBeanInfo(objectName);
        List<MBeanDump.MBeanAttribute> attributes = Lists.newArrayList();
        for (MBeanAttributeInfo attribute : mBeanInfo.getAttributes()) {
            Object value;
            try {
                value = lazyPlatformMBeanServer.getAttribute(objectName, attribute.getName());
            } catch (Exception e) {
                // log exception at debug level
                logger.debug(e.getMessage(), e);
                Throwable rootCause = getRootCause(e);
                value = "<" + rootCause.getClass().getName() + ": " + rootCause.getMessage() + ">";
            }
            attributes.add(MBeanDump.MBeanAttribute.newBuilder()
                    .setName(attribute.getName())
                    .setValue(getMBeanAttributeValue(value))
                    .build());
        }
        return attributes;
    }

    private static Throwable getRootCause(Throwable t) {
        Throwable cause = t.getCause();
        if (cause == null) {
            return t;
        } else {
            return getRootCause(cause);
        }
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
                    .setString(value.toString())
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
        if (objectName.isPattern()) {
            return lazyPlatformMBeanServer.queryNames(objectName, null);
        } else {
            return ImmutableSet.of(objectName);
        }
    }

    private Set<String> getAttributeNames(Set<ObjectName> objectNames) {
        Set<String> attributeNames = Sets.newHashSet();
        for (ObjectName objectName : objectNames) {
            try {
                MBeanInfo mbeanInfo = lazyPlatformMBeanServer.getMBeanInfo(objectName);
                attributeNames.addAll(getAttributeNames(mbeanInfo, objectName));
            } catch (Exception e) {
                // log exception at debug level
                logger.debug(e.getMessage(), e);
            }
        }
        return attributeNames;
    }

    private Set<String> getAttributeNames(MBeanInfo mbeanInfo, ObjectName objectName) {
        Set<String> attributeNames = Sets.newHashSet();
        for (MBeanAttributeInfo attribute : mbeanInfo.getAttributes()) {
            if (attribute.isReadable()) {
                try {
                    Object value =
                            lazyPlatformMBeanServer.getAttribute(objectName, attribute.getName());
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
        if (numericAttributeTypes.contains(attributeType)) {
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
            Descriptor descriptor = attribute.getDescriptor();
            Object descriptorFieldValue = descriptor.getFieldValue("openType");
            if (descriptorFieldValue instanceof CompositeType) {
                CompositeType compositeType = (CompositeType) descriptorFieldValue;
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
                attributeNames.add(attribute.getName() + '/' + itemName);
            } else if (clazz == String.class && compositeData instanceof CompositeData) {
                Object val = ((CompositeData) compositeData).get(itemName);
                if (val instanceof String) {
                    try {
                        Double.parseDouble((String) val);
                        attributeNames.add(attribute.getName() + '/' + itemName);
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
            return ImmutableAvailability.of(false, "java.lang.management.ThreadMXBean"
                    + ".isThreadCpuTimeSupported() returned false");
        }
        if (!threadMXBean.isThreadCpuTimeEnabled()) {
            return ImmutableAvailability.of(false, "java.lang.management.ThreadMXBean"
                    + ".isThreadCpuTimeEnabled() returned false");
        }
        return ImmutableAvailability.of(true, "");
    }

    private static Availability getThreadContentionAvailability() {
        ThreadMXBean threadMXBean = ManagementFactory.getThreadMXBean();
        if (!threadMXBean.isThreadContentionMonitoringSupported()) {
            return ImmutableAvailability.of(false, "java.lang.management.ThreadMXBean"
                    + ".isThreadContentionMonitoringSupported() returned false");
        }
        if (!threadMXBean.isThreadContentionMonitoringEnabled()) {
            return ImmutableAvailability.of(false, "java.lang.management.ThreadMXBean"
                    + ".isThreadContentionMonitoringEnabled() returned false");
        }
        return ImmutableAvailability.of(true, "");
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
