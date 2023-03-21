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
package org.glowroot.agent;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintWriter;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Joiner;
import com.google.common.base.Splitter;
import com.google.common.base.Strings;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Lists;
import com.google.common.io.Files;

import static com.google.common.base.Charsets.ISO_8859_1;

class PropertiesFiles {

    private PropertiesFiles() {}

    static void upgradeIfNeededAndLoadInto(File confDir, Map<String, String> properties)
            throws IOException {
        File propFile = new File(confDir, "glowroot.properties");
        if (!propFile.exists()) {
            return;
        }
        // upgrade from 0.9.6 to 0.9.7
        org.glowroot.common.util.PropertiesFiles.upgradeIfNeeded(propFile,
                ImmutableMap.of("agent.rollup=", "agent.rollup.id="));
        // upgrade from 0.9.13 to 0.9.14
        upgradeToCollectorAddressIfNeeded(propFile);
        // upgrade from 0.9.26 to 0.9.27
        addSchemeToCollectorAddressIfNeeded(propFile);
        // upgrade from 0.9.28 to 0.10.0
        prependAgentRollupToAgentIdIfNeeded(propFile);

        Properties props = new Properties();
        try (InputStream in = new FileInputStream(propFile)) {
            props.load(in);
        }
        for (String key : props.stringPropertyNames()) {
            String value = props.getProperty(key);
            if (value != null && !value.isEmpty()) {
                // need to trim trailing spaces (leading spaces are already trimmed during load)
                properties.put("glowroot." + key, value.trim());
            }
        }
    }

    private static void upgradeToCollectorAddressIfNeeded(File propFile) throws IOException {
        List<String> lines = readPropertiesFile(propFile);
        List<String> newLines = upgradeToCollectorAddressIfNeeded(lines);
        if (!newLines.equals(lines)) {
            writePropertiesFile(propFile, newLines);
        }
    }

    @VisibleForTesting
    static List<String> upgradeToCollectorAddressIfNeeded(List<String> lines) {
        List<String> newLines = Lists.newArrayList();
        String host = null;
        String port = null;
        int indexForAddress = -1;
        for (String line : lines) {
            if (line.startsWith("collector.host=")) {
                host = line.substring("collector.host=".length());
                if (indexForAddress == -1) {
                    indexForAddress = newLines.size();
                }
            } else if (line.startsWith("collector.port=")) {
                port = line.substring("collector.port=".length());
                if (indexForAddress == -1) {
                    indexForAddress = newLines.size();
                }
            } else if (line.startsWith("collector.address=")) {
                return lines;
            } else {
                newLines.add(line);
            }
        }
        if (indexForAddress == -1) {
            return newLines;
        }
        if (host == null) {
            return newLines;
        }
        if (host.isEmpty()) {
            newLines.add(indexForAddress, "collector.address=");
            return newLines;
        }
        if (port == null || port.isEmpty()) {
            port = "8181";
        }
        newLines.add(indexForAddress, "collector.address=" + host + ":" + port);
        return newLines;
    }

    private static void addSchemeToCollectorAddressIfNeeded(File propFile) throws IOException {
        List<String> lines = readPropertiesFile(propFile);
        List<String> newLines = addSchemeToCollectorAddressIfNeeded(lines);
        if (!newLines.equals(lines)) {
            writePropertiesFile(propFile, newLines);
        }
    }

    private static List<String> addSchemeToCollectorAddressIfNeeded(List<String> lines) {
        List<String> newLines = Lists.newArrayList();
        for (String line : lines) {
            if (line.startsWith("collector.address=")) {
                String collectorAddress = line.substring("collector.address=".length());
                List<String> addrs = Lists.newArrayList();
                boolean modified = false;
                for (String addr : Splitter.on(',').trimResults().omitEmptyStrings()
                        .split(collectorAddress)) {
                    // need to check for "http\://" and "https\://" since those are allowed and
                    // interpreted by Properties.load() as "http://" and "https://"
                    if (addr.startsWith("http://") || addr.startsWith("https://")
                            || addr.startsWith("http\\://") || addr.startsWith("https\\://")) {
                        addrs.add(addr);
                    } else {
                        addrs.add("http://" + addr);
                        modified = true;
                    }
                }
                if (modified) {
                    newLines.add("collector.address=" + Joiner.on(',').join(addrs));
                } else {
                    newLines.add(line);
                }
            } else {
                newLines.add(line);
            }
        }
        return newLines;
    }

    private static void prependAgentRollupToAgentIdIfNeeded(File propFile) throws IOException {
        List<String> lines = readPropertiesFile(propFile);
        List<String> newLines = prependAgentRollupToAgentIdIfNeeded(lines);
        if (!newLines.equals(lines)) {
            writePropertiesFile(propFile, newLines);
        }
    }

    private static List<String> prependAgentRollupToAgentIdIfNeeded(List<String> lines) {
        List<String> newLines = Lists.newArrayList();
        String agentId = null;
        String agentRollupId = null;
        int agentIdLineIndex = -1;
        for (String line : lines) {
            if (line.startsWith("agent.id=")) {
                agentId = line.substring("agent.id=".length());
                agentIdLineIndex = newLines.size();
                newLines.add(line);
            } else if (line.startsWith("agent.rollup.id=")) {
                agentRollupId = line.substring("agent.rollup.id=".length());
            } else {
                newLines.add(line);
            }
        }
        if (agentIdLineIndex != -1 && !Strings.isNullOrEmpty(agentRollupId)) {
            newLines.set(agentIdLineIndex,
                    "agent.id=" + agentRollupId.replace("/", "::") + "::" + agentId);
        }
        return newLines;
    }

    private static List<String> readPropertiesFile(File propFile) throws IOException {
        // properties files must be ISO_8859_1
        return Files.readLines(propFile, ISO_8859_1);
    }

    private static void writePropertiesFile(File propFile, List<String> newLines)
            throws FileNotFoundException {
        // properties files must be ISO_8859_1
        try (PrintWriter out = new PrintWriter(Files.newWriter(propFile, ISO_8859_1))) {
            for (String newLine : newLines) {
                out.println(newLine);
            }
        }
    }
}
