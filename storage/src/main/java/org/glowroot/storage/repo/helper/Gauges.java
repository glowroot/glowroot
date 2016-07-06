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
package org.glowroot.storage.repo.helper;

import java.util.List;
import java.util.regex.Pattern;

import com.google.common.base.CharMatcher;
import com.google.common.base.Joiner;
import com.google.common.base.Splitter;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;

import org.glowroot.storage.repo.GaugeValueRepository.Gauge;
import org.glowroot.storage.repo.ImmutableGauge;

public class Gauges {

    public static final String DISPLAY_PATH_SEPARATOR = " / ";

    private static final ImmutableList<UnitPattern> unitPatterns;

    private static final String GROUPING_PREFIX = "grouping-";

    static {
        List<UnitPattern> patterns = Lists.newArrayList();
        patterns.add(new UnitPattern(
                "java.lang:type=Memory:(Non)?HeapMemoryUsage\\/(init|used|committed|max)",
                "bytes"));
        patterns.add(new UnitPattern(
                "java.lang:type=OperatingSystem:(Free|Total)(Physical|Swap)MemorySize", "bytes"));
        patterns.add(
                new UnitPattern("java.lang:type=OperatingSystem:(ProcessCpuLoad|SystemCpuLoad)",
                        GROUPING_PREFIX + "1"));
        patterns.add(new UnitPattern("java.lang:type=Runtime:Uptime", "milliseconds"));
        patterns.add(new UnitPattern("java.lang:type=Threading:CurrentThread(Cpu|User)Time",
                "nanoseconds"));
        patterns.add(new UnitPattern("java.lang:type=MemoryPool,name=[a-zA-Z0-9 ]+:(Peak)?Usage"
                + "\\/(init|used|committed|max)", "bytes"));
        patterns.add(new UnitPattern(
                "java.lang:type=GarbageCollector,name=[a-zA-Z0-9 ]+:LastGcInfo\\/duration",
                "milliseconds"));
        patterns.add(
                new UnitPattern("java.lang:type=GarbageCollector,name=[a-zA-Z0-9 ]+:CollectionTime",
                        "milliseconds"));
        patterns.add(new UnitPattern(
                "java.lang:type=GarbageCollector,name=[a-zA-Z0-9 ]+:CollectionCount",
                GROUPING_PREFIX + "2"));
        patterns.add(
                new UnitPattern("java.lang:type=Compilation:TotalCompilationTime", "milliseconds"));
        patterns.add(new UnitPattern("sun.management:type=HotspotRuntime:SafepointSyncTime",
                "milliseconds"));
        patterns.add(new UnitPattern("sun.management:type=HotspotRuntime:TotalSafepointTime",
                "milliseconds"));
        patterns.add(
                new UnitPattern("org.glowroot:type=FileSystem,name=.*:(Total|Free)Space", "bytes"));
        patterns.add(
                new UnitPattern("org.glowroot:type=FileSystem,name=.*:PercentFull", "percent"));
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
        mbeanAttributeName = mbeanAttributeName.replaceAll("/", DISPLAY_PATH_SEPARATOR);
        List<String> displayPath = displayPath(mbeanObjectName);
        displayPath.addAll(Splitter.on('/').splitToList(mbeanAttributeName));
        String display = Joiner.on(DISPLAY_PATH_SEPARATOR).join(displayPath);
        String unit = unit(gaugeName);
        ImmutableGauge.Builder gauge = ImmutableGauge.builder()
                .name(gaugeName)
                .display(display)
                .displayPath(displayPath)
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

    public static List<String> displayPath(String mbeanObjectName) {
        // e.g. java.lang:name=PS Eden Space,type=MemoryPool
        List<String> parts = Splitter.on(CharMatcher.anyOf(":,")).splitToList(mbeanObjectName);
        List<String> display = Lists.newArrayList();
        display.add(parts.get(0));
        for (int i = 1; i < parts.size(); i++) {
            String part = parts.get(i).split("=")[1];
            if (part.startsWith("\"") && part.endsWith("\"")) {
                part = part.substring(1, part.length() - 1);
            }
            if (part.contains("/")) {
                // special case since this is also the display path separator
                display.add("\"" + part + "\"");
            } else {
                display.add(part);
            }
        }
        return display;
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
