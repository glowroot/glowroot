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
package org.glowroot.common2.repo.util;

import java.util.List;
import java.util.regex.Pattern;

import com.google.common.base.Joiner;
import com.google.common.base.Splitter;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.glowroot.common2.repo.GaugeValueRepository.Gauge;
import org.glowroot.common2.repo.ImmutableGauge;

public class Gauges {

    public static final String DISPLAY_PARTS_SEPARATOR = " / ";

    private static final Logger logger = LoggerFactory.getLogger(Gauges.class);

    private static final ImmutableList<UnitPattern> unitPatterns;

    private static final String GROUPING_PREFIX = "grouping-";

    static {
        List<UnitPattern> patterns = Lists.newArrayList();
        patterns.add(new UnitPattern(
                "java.lang:type=Memory:(Non)?HeapMemoryUsage\\.(init|used|committed|max)",
                "bytes"));
        patterns.add(new UnitPattern(
                "java.lang:type=OperatingSystem:(Free|Total)(Physical|Swap)MemorySize", "bytes"));
        patterns.add(
                new UnitPattern("java.lang:type=OperatingSystem:(ProcessCpuLoad|SystemCpuLoad)",
                        GROUPING_PREFIX + "cpu-load"));
        patterns.add(new UnitPattern("java.lang:type=Runtime:Uptime", "milliseconds"));
        patterns.add(new UnitPattern("java.lang:type=Threading:CurrentThread(Cpu|User)Time",
                "nanoseconds"));
        patterns.add(new UnitPattern("java.lang:type=MemoryPool,name=.*:(Peak)?Usage"
                + "\\.(init|used|committed|max)", "bytes"));
        patterns.add(new UnitPattern("java.lang:type=GarbageCollector,name=.*:LastGcInfo"
                + "\\.duration", "milliseconds"));
        patterns.add(new UnitPattern("java.lang:type=GarbageCollector,name=.*:CollectionTime",
                "milliseconds"));
        patterns.add(new UnitPattern("java.lang:type=GarbageCollector,name=.*:CollectionCount",
                GROUPING_PREFIX + "collection-count"));
        patterns.add(
                new UnitPattern("java.lang:type=Compilation:TotalCompilationTime", "milliseconds"));
        patterns.add(new UnitPattern("java.lang:type=ClassLoading:(Loaded|TotalLoaded|Unloaded)"
                + "ClassCount", GROUPING_PREFIX + "class-count"));
        patterns.add(new UnitPattern("sun.management:type=HotspotClassLoading"
                + ":InitializedClassCount", GROUPING_PREFIX + "class-count"));
        patterns.add(new UnitPattern("sun.management:type=HotspotClassLoading:(LoadedClassSize"
                + "|MethodDataSize|UnloadedClassSize)", "bytes"));
        patterns.add(new UnitPattern("sun.management:type=HotspotClassLoading:Class"
                + "(InitializationTime|LoadingTime|VerificationTime)", "milliseconds"));
        patterns.add(new UnitPattern("sun.management:type=HotspotRuntime:"
                + "(SafepointSync|TotalSafepoint)Time", "milliseconds"));
        patterns.add(new UnitPattern("org.glowroot:type=FileSystem,name=.*:(Total|Free)Space",
                "bytes"));
        patterns.add(
                new UnitPattern("org.glowroot:type=FileSystem,name=.*:PercentFull", "percent"));
        patterns.add(new UnitPattern("org.apache.cassandra.metrics:type=ColumnFamily,"
                + "keyspace=[^,]+,scope=[^,]+,name=LiveDiskSpaceUsed:Count", "bytes"));
        patterns.add(new UnitPattern("org.apache.cassandra.metrics:type=ColumnFamily,"
                + "keyspace=[^,]+,scope=[^,]+,name=TotalDiskSpaceUsed:Count", "bytes"));
        patterns.add(new UnitPattern("Catalina:type=ThreadPool,name=.*:(currentThreadCount"
                + "|currentThreadsBusy|maxThreads)", GROUPING_PREFIX + "thread-count"));
        patterns.add(new UnitPattern("Catalina:type=Executor,name=.*:(activeCount|poolSize"
                + "|maxThreads)", GROUPING_PREFIX + "thread-count"));
        patterns.add(new UnitPattern("com.mchange.v2.c3p0:type=PooledDataSource,"
                + "(identityToken|name)=.*:threadPoolSize", GROUPING_PREFIX + "thread-count"));
        unitPatterns = ImmutableList.copyOf(patterns);
    }

    private Gauges() {}

    public static Gauge getGauge(String gaugeName) {
        int index = gaugeName.lastIndexOf(':');
        String mbeanObjectName = gaugeName.substring(0, index);
        String mbeanAttributeName = gaugeName.substring(index + 1);
        boolean counter = mbeanAttributeName.endsWith("[counter]");
        if (counter) {
            mbeanAttributeName = mbeanAttributeName.substring(0,
                    mbeanAttributeName.length() - "[counter]".length());
        }
        mbeanAttributeName = mbeanAttributeName.replaceAll("\\.", DISPLAY_PARTS_SEPARATOR);
        List<String> displayParts = getDisplayParts(mbeanObjectName);
        displayParts.addAll(Splitter.on('.').splitToList(mbeanAttributeName));
        String display = Joiner.on(DISPLAY_PARTS_SEPARATOR).join(displayParts);
        String unit = unit(gaugeName);
        ImmutableGauge.Builder gauge = ImmutableGauge.builder()
                .name(gaugeName)
                .display(display)
                .displayParts(displayParts)
                .counter(counter)
                .grouping(unit);
        if (unit.startsWith(GROUPING_PREFIX)) {
            if (unit.endsWith(" per second")) {
                return gauge.unit("per second").build();
            } else {
                return gauge.unit("").build();
            }
        } else {
            return gauge.unit(unit).build();
        }
    }

    public static List<String> getDisplayParts(String mbeanObjectName) {
        // e.g. java.lang:name=PS Eden Space,type=MemoryPool
        List<String> displayParts = Lists.newArrayList();
        int index = mbeanObjectName.indexOf(':');
        String domain = mbeanObjectName.substring(0, index);
        displayParts.add(domain);
        index++;
        while (index < mbeanObjectName.length()) {
            index = processNextKeyValue(mbeanObjectName, index, displayParts);
            if (index == -1) {
                logger.warn("unexpected mbean object name: {}", mbeanObjectName);
                displayParts.clear();
                displayParts.add(mbeanObjectName);
                return displayParts;
            }
        }
        return displayParts;
    }

    private static int processNextKeyValue(String mbeanObjectName, int fromIndex,
            List<String> displayParts) {
        int index = mbeanObjectName.indexOf('=', fromIndex);
        if (index == -1) {
            // this is unexpected
            return -1;
        }
        index++;
        char c = mbeanObjectName.charAt(index);
        if (c == '"') {
            // in quoted value
            index++;
            StringBuilder sb = new StringBuilder();
            boolean quoteTerminated = false;
            while (index < mbeanObjectName.length()) {
                c = mbeanObjectName.charAt(index++);
                if (c == '\\') {
                    c = mbeanObjectName.charAt(index++);
                    if (c == 'n') {
                        sb.append('\n');
                    } else {
                        sb.append(c);
                    }
                } else if (c == '"') {
                    quoteTerminated = true;
                    break;
                } else {
                    sb.append(c);
                }
            }
            if (!quoteTerminated) {
                // this is unexpected
                return -1;
            }
            displayParts.add(sb.toString());
            return index;
        } else {
            int next = mbeanObjectName.indexOf(',', index);
            if (next == -1) {
                displayParts.add(mbeanObjectName.substring(index));
                return mbeanObjectName.length();
            } else {
                displayParts.add(mbeanObjectName.substring(index, next));
                return next + 1;
            }
        }
    }

    private static String unit(String gaugeName) {
        if (gaugeName.endsWith("[counter]")) {
            String baseUnit =
                    getBaseUnit(gaugeName.substring(0, gaugeName.length() - "[counter]".length()));
            if (baseUnit.isEmpty()) {
                return "per second";
            } else {
                return baseUnit + " per second";
            }
        } else {
            return getBaseUnit(gaugeName);
        }
    }

    private static String getBaseUnit(String gaugeName) {
        for (UnitPattern unitPattern : unitPatterns) {
            if (unitPattern.pattern.matcher(gaugeName).matches()) {
                return unitPattern.unit;
            }
        }
        return "";
    }

    private static class UnitPattern {

        private final Pattern pattern;
        private final String unit;

        private UnitPattern(String pattern, String unit) {
            this.pattern = Pattern.compile(pattern);
            this.unit = unit;
        }
    }
}
