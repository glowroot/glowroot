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
package org.glowroot.config;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.core.JsonGenerationException;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.util.DefaultPrettyPrinter;
import com.fasterxml.jackson.core.util.DefaultPrettyPrinter.Lf2SpacesIndenter;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectWriter;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.common.base.Charsets;
import com.google.common.base.StandardSystemProperty;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.io.CharStreams;
import com.google.common.io.Files;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.glowroot.common.ObjectMappers;
import org.glowroot.config.JsonViews.FileView;
import org.glowroot.markers.Immutable;

/**
 * @author Trask Stalnaker
 * @since 0.5
 */
@Immutable
class ConfigMapper {

    private static final String NEWLINE;

    private static final Logger logger = LoggerFactory.getLogger(ConfigMapper.class);
    private static final ObjectMapper mapper = ObjectMappers.create();

    private static final String TRACES = "traces";
    private static final String PROFILING = "profiling";
    private static final String USER_RECORDING = "userRecording";
    private static final String STORAGE = "storage";
    private static final String UI = "ui";
    private static final String ADVANCED = "advanced";
    private static final String PLUGINS = "plugins";
    private static final String MBEAN_GAUGES = "mbeanGauges";
    private static final String CAPTURE_POINTS = "capturePoints";

    static {
        String newline = StandardSystemProperty.LINE_SEPARATOR.value();
        if (newline == null) {
            NEWLINE = "\n";
        } else {
            NEWLINE = newline;
        }
    }

    private final ImmutableList<PluginDescriptor> pluginDescriptors;

    ConfigMapper(ImmutableList<PluginDescriptor> pluginDescriptors) {
        this.pluginDescriptors = pluginDescriptors;
    }

    Config readValue(String content) throws IOException {
        ObjectNode rootNode = (ObjectNode) mapper.readTree(content);
        TraceConfig traceConfig = readTracesNode(rootNode);
        ProfilingConfig profilingConfig = readProfilingNode(rootNode);
        UserRecordingConfig userRecordingConfig = readUserRecordingNode(rootNode);
        StorageConfig storageConfig = readStorageNode(rootNode);
        UserInterfaceConfig userInterfaceConfig =
                readUserInterfaceNode(rootNode, pluginDescriptors);
        ImmutableList<MBeanGauge> mbeanGauges = createMBeanGauges(rootNode);
        AdvancedConfig advancedConfig = readAdvancedNode(rootNode);
        ImmutableMap<String, ObjectNode> pluginNodes = createPluginNodes(rootNode);
        ImmutableList<PluginConfig> pluginConfigs =
                createPluginConfigs(pluginNodes, pluginDescriptors);
        ImmutableList<CapturePoint> capturePoints = createCapturePoints(rootNode);
        return new Config(traceConfig, profilingConfig, userRecordingConfig, storageConfig,
                userInterfaceConfig, advancedConfig, pluginConfigs, mbeanGauges, capturePoints);
    }

    static void writeValue(File configFile, Config config) throws IOException {
        Files.write(writeValueAsString(config), configFile, Charsets.UTF_8);
    }

    static String writeValueAsString(Config config) throws IOException {
        CustomPrettyPrinter prettyPrinter = new CustomPrettyPrinter();
        prettyPrinter.indentArraysWith(Lf2SpacesIndenter.instance);
        StringBuilder sb = new StringBuilder();
        JsonGenerator jg = mapper.getFactory().createGenerator(CharStreams.asWriter(sb))
                .setPrettyPrinter(prettyPrinter);
        // this view will exclude version properties
        ObjectWriter writer = mapper.writerWithView(FileView.class);
        jg.writeStartObject();
        jg.writeFieldName(TRACES);
        writer.writeValue(jg, config.getTraceConfig());
        jg.writeFieldName(PROFILING);
        writer.writeValue(jg, config.getProfilingConfig());
        jg.writeFieldName(USER_RECORDING);
        writer.writeValue(jg, config.getUserRecordingConfig());
        jg.writeFieldName(STORAGE);
        writer.writeValue(jg, config.getStorageConfig());
        jg.writeFieldName(UI);
        writer.writeValue(jg, config.getUserInterfaceConfig());
        jg.writeFieldName(ADVANCED);
        writer.writeValue(jg, config.getAdvancedConfig());
        jg.writeArrayFieldStart(PLUGINS);
        // write out plugin ordered by plugin id
        List<PluginConfig> orderedPluginConfigs =
                PluginConfig.orderingByName.sortedCopy(config.getPluginConfigs());
        for (PluginConfig pluginConfig : orderedPluginConfigs) {
            writer.writeValue(jg, pluginConfig);
        }
        jg.writeEndArray();
        jg.writeArrayFieldStart(CAPTURE_POINTS);
        for (CapturePoint capturePoint : config.getCapturePoints()) {
            writer.writeValue(jg, capturePoint);
        }
        jg.writeEndArray();
        jg.writeArrayFieldStart(MBEAN_GAUGES);
        for (MBeanGauge mbeanGauge : config.getMBeanGauges()) {
            writer.writeValue(jg, mbeanGauge);
        }
        jg.writeEndArray();
        jg.writeEndObject();
        jg.close();
        // newline is not required, just a personal preference
        return sb.toString() + NEWLINE;
    }

    private static TraceConfig readTracesNode(ObjectNode rootNode) throws IOException {
        ObjectNode configNode = (ObjectNode) rootNode.get(TRACES);
        TraceConfig defaultConfig = TraceConfig.getDefault();
        if (configNode == null) {
            return defaultConfig;
        } else {
            TraceConfig.Overlay overlay = TraceConfig.overlay(defaultConfig);
            mapper.readerForUpdating(overlay).readValue(configNode);
            return overlay.build();
        }
    }

    private static ProfilingConfig readProfilingNode(ObjectNode rootNode) throws IOException {
        ObjectNode configNode = (ObjectNode) rootNode.get(PROFILING);
        ProfilingConfig defaultConfig = ProfilingConfig.getDefault();
        if (configNode == null) {
            return defaultConfig;
        } else {
            ProfilingConfig.Overlay overlay = ProfilingConfig.overlay(defaultConfig);
            mapper.readerForUpdating(overlay).readValue(configNode);
            return overlay.build();
        }
    }

    private static UserRecordingConfig readUserRecordingNode(ObjectNode rootNode)
            throws IOException {
        ObjectNode configNode = (ObjectNode) rootNode.get(USER_RECORDING);
        UserRecordingConfig defaultConfig = UserRecordingConfig.getDefault();
        if (configNode == null) {
            return defaultConfig;
        } else {
            UserRecordingConfig.Overlay overlay = UserRecordingConfig.overlay(defaultConfig);
            mapper.readerForUpdating(overlay).readValue(configNode);
            return overlay.build();
        }
    }

    private static StorageConfig readStorageNode(ObjectNode rootNode) throws IOException {
        ObjectNode configNode = (ObjectNode) rootNode.get(STORAGE);
        StorageConfig defaultConfig = StorageConfig.getDefault();
        if (configNode == null) {
            return defaultConfig;
        } else {
            StorageConfig.Overlay overlay = StorageConfig.overlay(defaultConfig);
            mapper.readerForUpdating(overlay).readValue(configNode);
            return overlay.build();
        }
    }

    private static UserInterfaceConfig readUserInterfaceNode(ObjectNode rootNode,
            ImmutableList<PluginDescriptor> pluginDescriptors) throws IOException {
        ObjectNode configNode = (ObjectNode) rootNode.get(UI);
        UserInterfaceConfig defaultConfig = UserInterfaceConfig.getDefault(pluginDescriptors);
        if (configNode == null) {
            return defaultConfig;
        } else {
            UserInterfaceConfig.FileOverlay overlay =
                    UserInterfaceConfig.fileOverlay(defaultConfig);
            mapper.readerForUpdating(overlay).readValue(configNode);
            return overlay.build();
        }
    }

    private static AdvancedConfig readAdvancedNode(ObjectNode rootNode) throws IOException {
        ObjectNode configNode = (ObjectNode) rootNode.get(ADVANCED);
        AdvancedConfig defaultConfig = AdvancedConfig.getDefault();
        if (configNode == null) {
            return defaultConfig;
        } else {
            AdvancedConfig.Overlay overlay = AdvancedConfig.overlay(defaultConfig);
            mapper.readerForUpdating(overlay).readValue(configNode);
            return overlay.build();
        }
    }

    private static ImmutableMap<String, ObjectNode> createPluginNodes(ObjectNode rootNode) {
        ArrayNode pluginsNode = (ArrayNode) rootNode.get(PLUGINS);
        if (pluginsNode == null) {
            return ImmutableMap.of();
        }
        Map<String, ObjectNode> pluginNodes = Maps.newHashMap();
        for (JsonNode pluginNode : pluginsNode) {
            ObjectNode pluginObjectNode = (ObjectNode) pluginNode;
            JsonNode id = pluginObjectNode.get("id");
            if (id == null) {
                logger.warn("error in config.json file, id is missing");
                continue;
            }
            if (!id.isTextual()) {
                logger.warn("error in config.json file, id is not a string");
                continue;
            }
            pluginNodes.put(id.asText(), pluginObjectNode);
        }
        return ImmutableMap.copyOf(pluginNodes);
    }

    private static ImmutableList<PluginConfig> createPluginConfigs(
            ImmutableMap<String, ObjectNode> pluginNodes,
            ImmutableList<PluginDescriptor> pluginDescriptors) throws JsonMappingException {
        List<PluginConfig> pluginConfigs = Lists.newArrayList();
        for (PluginDescriptor pluginDescriptor : pluginDescriptors) {
            ObjectNode pluginConfigNode = pluginNodes.get(pluginDescriptor.getId());
            if (pluginConfigNode == null) {
                pluginConfigs.add(PluginConfig.getDefault(pluginDescriptor));
            } else {
                PluginConfig.Builder builder = new PluginConfig.Builder(pluginDescriptor);
                builder.overlay(pluginConfigNode, true);
                pluginConfigs.add(builder.build());
            }
        }
        return ImmutableList.copyOf(pluginConfigs);
    }

    private static ImmutableList<CapturePoint> createCapturePoints(ObjectNode rootNode)
            throws JsonProcessingException {
        JsonNode pointcutsNode = rootNode.get(CAPTURE_POINTS);
        if (pointcutsNode == null) {
            return ImmutableList.of();
        }
        List<CapturePoint> capturePoints = Lists.newArrayList();
        for (JsonNode pointcutNode : pointcutsNode) {
            CapturePoint capturePoint =
                    ObjectMappers.treeToRequiredValue(mapper, pointcutNode, CapturePoint.class);
            capturePoints.add(capturePoint);
        }
        return ImmutableList.copyOf(capturePoints);
    }

    @SuppressWarnings("serial")
    private static class CustomPrettyPrinter extends DefaultPrettyPrinter {

        @Override
        public void writeObjectFieldValueSeparator(JsonGenerator jg) throws IOException,
                JsonGenerationException {
            jg.writeRaw(": ");
        }
    }

    private static ImmutableList<MBeanGauge> createMBeanGauges(ObjectNode rootNode)
            throws JsonProcessingException {
        JsonNode gaugesNode = rootNode.get(MBEAN_GAUGES);
        if (gaugesNode == null) {
            return ImmutableList.of();
        }
        List<MBeanGauge> mbeanGauges = Lists.newArrayList();
        for (JsonNode gaugeNode : gaugesNode) {
            MBeanGauge mbeanGauge =
                    ObjectMappers.treeToRequiredValue(mapper, gaugeNode, MBeanGauge.class);
            mbeanGauges.add(mbeanGauge);
        }
        return ImmutableList.copyOf(mbeanGauges);
    }
}
