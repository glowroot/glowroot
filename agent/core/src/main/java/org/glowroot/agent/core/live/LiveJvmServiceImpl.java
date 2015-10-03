/*
 * Copyright 2015 the original author or authors.
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
package org.glowroot.agent.core.live;

import java.io.File;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.lang.management.RuntimeMXBean;
import java.lang.management.ThreadMXBean;
import java.lang.reflect.Array;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Enumeration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.TreeMap;

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

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Joiner;
import com.google.common.base.MoreObjects;
import com.google.common.base.Splitter;
import com.google.common.base.StandardSystemProperty;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.Ordering;
import com.google.common.collect.Sets;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.glowroot.agent.core.util.LazyPlatformMBeanServer;
import org.glowroot.agent.core.util.OptionalService;
import org.glowroot.common.live.ImmutableAvailability;
import org.glowroot.common.live.ImmutableCapabilities;
import org.glowroot.common.live.ImmutableHeapFile;
import org.glowroot.common.live.ImmutableMBeanMeta;
import org.glowroot.common.live.ImmutableProcessInfo;
import org.glowroot.common.live.LiveJvmService;
import org.glowroot.common.util.PatternObjectNameQueryExp;

import static com.google.common.base.Preconditions.checkNotNull;

public class LiveJvmServiceImpl implements LiveJvmService {

    private static final Logger logger = LoggerFactory.getLogger(LiveJvmServiceImpl.class);

    private static final ImmutableSet<String> numericAttributeTypes =
            ImmutableSet.of("long", "int", "double", "float", "java.lang.Long", "java.lang.Integer",
                    "java.lang.Double", "java.lang.Float");

    private final LazyPlatformMBeanServer lazyPlatformMBeanServer;
    private final Availability threadAllocatedBytesAvailability;
    private final OptionalService<HeapDumps> heapDumps;
    private final String processId;

    public LiveJvmServiceImpl(LazyPlatformMBeanServer lazyPlatformMBeanServer,
            Availability threadAllocatedBytesAvailability) {
        this.lazyPlatformMBeanServer = lazyPlatformMBeanServer;
        this.threadAllocatedBytesAvailability = threadAllocatedBytesAvailability;
        this.heapDumps = HeapDumps.create(lazyPlatformMBeanServer);
        this.processId = parseProcessId(ManagementFactory.getRuntimeMXBean().getName());
    }

    @Override
    public Map<String, MBeanTreeInnerNode> getMBeanTree(MBeanTreeRequest request) throws Exception {
        Set<ObjectName> objectNames = lazyPlatformMBeanServer.queryNames(null, null);
        // can't use Maps.newTreeMap() because of OpenJDK6 type inference bug
        // see https://code.google.com/p/guava-libraries/issues/detail?id=635
        Map<String, MBeanTreeInnerNode> sortedRootNodes =
                new TreeMap<String, MBeanTreeInnerNode>(String.CASE_INSENSITIVE_ORDER);
        for (ObjectName objectName : objectNames) {
            String domain = objectName.getDomain();
            MBeanTreeInnerNode node = sortedRootNodes.get(domain);
            if (node == null) {
                node = new MBeanTreeInnerNode(domain);
                sortedRootNodes.put(domain, node);
            }
            List<String> propertyValues = ObjectNames.getPropertyValues(objectName);
            for (int i = 0; i < propertyValues.size() - 1; i++) {
                node = node.getOrCreateNode(propertyValues.get(i));
            }
            String name = objectName.toString();
            String value = propertyValues.get(propertyValues.size() - 1);
            if (request.expanded().contains(name)) {
                Map<String, /*@Nullable*/Object> sortedAttributeMap =
                        getMBeanSortedAttributeMap(objectName);
                node.addLeafNode(new MBeanTreeLeafNode(value, name, true, sortedAttributeMap));
            } else {
                node.addLeafNode(new MBeanTreeLeafNode(value, name, false, null));
            }
        }
        return sortedRootNodes;
    }

    @Override
    public Map<String, /*@Nullable*/Object> getMBeanSortedAttributeMap(String server,
            String objectName) throws Exception {
        return getMBeanSortedAttributeMap(ObjectName.getInstance(objectName));
    }

    @Override
    public List<String> getMatchingMBeanObjectNames(String server, String partialMBeanObjectName,
            int limit) throws InterruptedException {
        Set<ObjectName> objectNames = lazyPlatformMBeanServer.queryNames(null,
                new ObjectNameQueryExp(partialMBeanObjectName));
        List<String> names = Lists.newArrayList();
        for (ObjectName objectName : objectNames) {
            names.add(objectName.toString());
        }
        ImmutableList<String> sortedNames =
                Ordering.from(String.CASE_INSENSITIVE_ORDER).immutableSortedCopy(names);
        if (sortedNames.size() > limit) {
            sortedNames = sortedNames.subList(0, limit);
        }
        return sortedNames;
    }

    @Override
    public MBeanMeta getMBeanMeta(String server, String mbeanObjectName) throws Exception {
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
    public String getHeapDumpDefaultDirectory(String server) {
        String heapDumpPath = getHeapDumpPathFromCommandLine();
        if (heapDumpPath == null) {
            String javaTempDir =
                    MoreObjects.firstNonNull(StandardSystemProperty.JAVA_IO_TMPDIR.value(), ".");
            heapDumpPath = new File(javaTempDir).getAbsolutePath();
        }
        return heapDumpPath;
    }

    @Override
    public long getAvailableDiskSpace(String server, String directory) throws IOException {
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
    public HeapFile dumpHeap(String server, String directory) throws Exception {
        // this command is filtered out of the UI when service is null
        HeapDumps service = checkNotNull(heapDumps.getService(),
                "Heap dump service is not available: %s", heapDumps.getAvailability().getReason());
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
            // this seems unlikely now that timestamp is included in filename
            i++;
            file = new File(dir, "heap-dump-" + timestamp + "-" + i + ".hprof");
        }
        service.dumpHeap(file.getAbsolutePath());
        return ImmutableHeapFile.of(file.getAbsolutePath(), file.length());
    }

    @Override
    public ProcessInfo getProcessInfo(String server) {
        String command = System.getProperty("sun.java.command");
        String mainClass = "";
        List<String> arguments = ImmutableList.of();
        if (command != null) {
            int index = command.indexOf(' ');
            if (index == -1) {
                mainClass = command;
            } else {
                mainClass = command.substring(0, index);
                arguments =
                        Lists.newArrayList(Splitter.on(' ').split(command.substring(index + 1)));
            }
        }
        RuntimeMXBean runtimeMXBean = ManagementFactory.getRuntimeMXBean();
        String jvm = "";
        String javaVmName = StandardSystemProperty.JAVA_VM_NAME.value();
        if (javaVmName != null) {
            jvm = javaVmName + " (" + StandardSystemProperty.JAVA_VM_VERSION.value() + ", "
                    + System.getProperty("java.vm.info") + ")";
        }
        String java = "";
        String javaVersion = StandardSystemProperty.JAVA_VERSION.value();
        if (javaVersion != null) {
            java = "version " + javaVersion + ", vendor "
                    + StandardSystemProperty.JAVA_VM_VENDOR.value();
        }
        String javaHome = MoreObjects.firstNonNull(StandardSystemProperty.JAVA_HOME.value(), "");

        return ImmutableProcessInfo.builder()
                .startTime(runtimeMXBean.getStartTime())
                .uptime(runtimeMXBean.getUptime())
                .pid(processId)
                .mainClass(mainClass)
                .mainClassArguments(arguments)
                .jvm(jvm)
                .java(java)
                .javaHome(javaHome)
                .jvmArguments(runtimeMXBean.getInputArguments())
                .build();
    }

    @Override
    public Map<String, String> getSystemProperties(String server) {
        Properties properties = System.getProperties();
        Map<String, String> map = Maps.newHashMap();
        for (Enumeration<?> e = properties.propertyNames(); e.hasMoreElements();) {
            Object obj = e.nextElement();
            if (obj instanceof String) {
                String propertyName = (String) obj;
                String propertyValue = properties.getProperty(propertyName);
                if (propertyValue != null) {
                    map.put(propertyName, propertyValue);
                }
            }
        }
        return map;
    }

    @Override
    public Capabilities getCapabilities(String server) {
        return ImmutableCapabilities.builder()
                .threadCpuTime(getThreadCpuTimeAvailability())
                .threadContentionTime(getThreadContentionAvailability())
                .threadAllocatedBytes(threadAllocatedBytesAvailability)
                .heapDump(heapDumps.getAvailability())
                .build();
    }

    private Map<String, /*@Nullable*/Object> getMBeanSortedAttributeMap(ObjectName objectName)
            throws Exception {
        MBeanInfo mBeanInfo = lazyPlatformMBeanServer.getMBeanInfo(objectName);
        // can't use Maps.newTreeMap() because of OpenJDK6 type inference bug
        // see https://code.google.com/p/guava-libraries/issues/detail?id=635
        Map<String, /*@Nullable*/Object> sortedAttributeMap =
                new TreeMap<String, /*@Nullable*/Object>(String.CASE_INSENSITIVE_ORDER);
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
            sortedAttributeMap.put(attribute.getName(), getMBeanAttributeValue(value));
        }
        return sortedAttributeMap;
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
    private static @Nullable Object getMBeanAttributeValue(@Nullable Object value) {
        if (value == null) {
            return null;
        } else if (value instanceof CompositeData) {
            return getCompositeDataValue((CompositeData) value);
        } else if (value instanceof TabularData) {
            return getTabularDataValue((TabularData) value);
        } else if (value.getClass().isArray()) {
            return getArrayValue(value);
        } else if (value instanceof Number) {
            return value;
        } else {
            return value.toString();
        }
    }

    private static Object getCompositeDataValue(CompositeData compositeData) {
        // linked hash map used to preserve attribute ordering
        Map<String, /*@Nullable*/Object> valueMap = Maps.newLinkedHashMap();
        for (String key : compositeData.getCompositeType().keySet()) {
            valueMap.put(key, getMBeanAttributeValue(compositeData.get(key)));
        }
        return valueMap;
    }

    private static Object getTabularDataValue(TabularData tabularData) {
        // linked hash map used to preserve row ordering
        Map<String, Map<String, /*@Nullable*/Object>> rowMap = Maps.newLinkedHashMap();
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
            Map<String, /*@Nullable*/Object> valueMap = Maps.newLinkedHashMap();
            for (String attributeName : attributeNames) {
                valueMap.put(attributeName,
                        getMBeanAttributeValue(compositeData.get(attributeName)));
            }
            rowMap.put(keyString, valueMap);
        }
        return rowMap;
    }

    private static Object getArrayValue(Object value) {
        int length = Array.getLength(value);
        List</*@Nullable*/Object> valueList = Lists.newArrayListWithCapacity(length);
        for (int i = 0; i < length; i++) {
            Object val = Array.get(value, i);
            valueList.add(getMBeanAttributeValue(val));
        }
        return valueList;
    }

    private Set<ObjectName> getObjectNames(String objectName) throws Exception {
        if (objectName.contains("*")) {
            return lazyPlatformMBeanServer.queryNames(null,
                    new PatternObjectNameQueryExp(objectName));
        } else {
            return ImmutableSet.of(ObjectName.getInstance(objectName));
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

    private static @Nullable String getHeapDumpPathFromCommandLine() {
        RuntimeMXBean runtimeMXBean = ManagementFactory.getRuntimeMXBean();
        for (String arg : runtimeMXBean.getInputArguments()) {
            if (arg.startsWith("-XX:HeapDumpPath=")) {
                return arg.substring("-XX:HeapDumpPath=".length());
            }
        }
        return null;
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

    @VisibleForTesting
    static String parseProcessId(String runtimeName) {
        int index = runtimeName.indexOf('@');
        if (index > 0) {
            String pid = runtimeName.substring(0, index);
            try {
                Long.parseLong(pid);
                return pid;
            } catch (NumberFormatException e) {
                logger.debug(e.getMessage(), e);
                return "";
            }
        } else {
            return "";
        }
    }

    @SuppressWarnings("serial")
    private static class ObjectNameQueryExp implements QueryExp {

        private final String textUpper;

        private ObjectNameQueryExp(String text) {
            this.textUpper = text.toUpperCase(Locale.ENGLISH);
        }

        @Override
        public boolean apply(ObjectName name) {
            return name.toString().toUpperCase(Locale.ENGLISH).contains(textUpper);
        }

        @Override
        public void setMBeanServer(MBeanServer s) {}
    }
}
