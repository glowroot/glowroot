/*
 * Copyright 2013-2015 the original author or authors.
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
package org.glowroot.agent.config;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.Map;

import javax.annotation.Nullable;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.util.DefaultIndenter;
import com.fasterxml.jackson.core.util.DefaultPrettyPrinter;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.base.Charsets;
import com.google.common.base.StandardSystemProperty;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.Ordering;
import com.google.common.io.CharStreams;
import com.google.common.io.Files;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.glowroot.common.config.Config;
import org.glowroot.common.config.GaugeConfig;
import org.glowroot.common.config.ImmutableConfig;
import org.glowroot.common.config.ImmutableGaugeConfig;
import org.glowroot.common.config.ImmutableMBeanAttribute;
import org.glowroot.common.config.ImmutablePluginConfig;
import org.glowroot.common.config.ImmutableStorageConfig;
import org.glowroot.common.config.ImmutableTransactionConfig;
import org.glowroot.common.config.InstrumentationConfig;
import org.glowroot.common.config.JacksonModule;
import org.glowroot.common.config.PluginConfig;
import org.glowroot.common.config.PluginDescriptor;
import org.glowroot.common.config.PropertyDescriptor;
import org.glowroot.common.config.PropertyValue;
import org.glowroot.common.config.StorageConfig;
import org.glowroot.common.config.TransactionConfig;
import org.glowroot.common.util.JavaVersion;
import org.glowroot.common.util.ObjectMappers;
import org.glowroot.markers.OnlyUsedByTests;

import static com.google.common.base.Preconditions.checkNotNull;

class ConfigFile {

    private static final Logger logger = LoggerFactory.getLogger(ConfigFile.class);

    private static final String NEWLINE;

    private static final ObjectMapper mapper = ObjectMappers.create(JacksonModule.create());

    static {
        String newline = StandardSystemProperty.LINE_SEPARATOR.value();
        if (newline == null) {
            NEWLINE = "\n";
        } else {
            NEWLINE = newline;
        }
    }

    private final File file;
    private final ImmutableList<PluginDescriptor> pluginDescriptors;

    ConfigFile(File file, List<PluginDescriptor> pluginDescriptors) {
        this.file = file;
        // sorted by id for writing to config file
        this.pluginDescriptors =
                new PluginDescriptorOrdering().immutableSortedCopy(pluginDescriptors);
    }

    private Config readValue(String content) throws IOException {
        Config config = mapper.readValue(content, ImmutableConfig.class);
        // validation/auto-correction of storage config arrays
        StorageConfig storageConfig = config.storageConfig();
        if (storageConfig.hasListIssues()) {
            storageConfig = withCorrectedLists(storageConfig);
            config = ImmutableConfig.builder().copyFrom(config).storageConfig(storageConfig)
                    .build();
        }
        TransactionConfig transactionConfig = config.transactionConfig();
        if (transactionConfig.defaultDisplayedTransactionType().isEmpty()) {
            transactionConfig = ImmutableTransactionConfig.builder().copyFrom(transactionConfig)
                    .defaultDisplayedTransactionType(
                            getDefaultDisplayedTransactionType(config.instrumentationConfigs()))
                    .build();
            config = ImmutableConfig.builder().copyFrom(config).transactionConfig(transactionConfig)
                    .build();
        }
        if (!mapper.readTree(content).has("gauges")) {
            List<GaugeConfig> defaultGauges = getDefaultGaugeConfigs();
            config = ImmutableConfig.builder().copyFrom(config).gaugeConfigs(defaultGauges).build();
        }
        Map<String, PluginConfig> filePluginConfigs = Maps.newHashMap();
        for (PluginConfig pluginConfig : config.pluginConfigs()) {
            filePluginConfigs.put(pluginConfig.id(), pluginConfig);
        }
        List<PluginConfig> pluginConfigs = Lists.newArrayList();
        for (PluginDescriptor pluginDescriptor : pluginDescriptors) {
            PluginConfig filePluginConfig = filePluginConfigs.get(pluginDescriptor.id());
            ImmutablePluginConfig.Builder builder =
                    ImmutablePluginConfig.builder().id(pluginDescriptor.id());
            if (filePluginConfig != null) {
                builder.enabled(filePluginConfig.enabled());
            }
            for (PropertyDescriptor propertyDescriptor : pluginDescriptor.properties()) {
                String propertyName = propertyDescriptor.name();
                PropertyValue filePropertyValue = null;
                if (filePluginConfig != null) {
                    filePropertyValue = filePluginConfig.getValidatedPropertyValue(propertyName,
                            propertyDescriptor.type());
                }
                if (filePropertyValue == null) {
                    builder.putProperties(propertyName,
                            propertyDescriptor.getValidatedNonNullDefaultValue());
                } else {
                    builder.putProperties(propertyName, filePropertyValue);
                }
            }
            pluginConfigs.add(builder.build());
        }
        return ImmutableConfig.builder().copyFrom(config).pluginConfigs(pluginConfigs).build();
    }

    Config loadConfig() throws IOException {
        if (!file.exists()) {
            Config config = getDefaultConfig();
            write(config);
            return config;
        }
        String content = Files.toString(file, Charsets.UTF_8);
        Config config;
        String warningMessage = null;
        try {
            // handling upgrade from 0.8 to 0.8.1
            content = content.replace("\"defaultTransactionType\"",
                    "\"defaultDisplayedTransactionType\"");
            // handling upgrade from 0.8.3 to 0.8.4
            content = content.replace("\"cappedDatabaseSizeMb\"", "\"traceCappedDatabaseSizeMb\"");
            // this will rename "general/traceStoreThresholdMillis" in addition to intended
            // "instrumentation/traceStoreThresholdMillis", but general config is lost in upgrade
            // to 0.8.4 anyways with rename to transaction
            content = content.replace("\"traceStoreThresholdMillis\"",
                    "\"transactionSlowThresholdMillis\"");
            // handling upgrade from 0.8.4 to 0.8.5
            content = content.replace("\"captureGcInfo\"", "\"captureGcActivity\"");
            content = content.replace("\"everIncreasing\"", "\"counter\"");
            config = readValue(content);
        } catch (Exception e) {
            // immutables json processing wraps IOExceptions inside RuntimeExceptions so can't rely
            // on just catching IOException here
            logger.warn("error processing config file: {}", file.getAbsolutePath(), e);
            File backupFile = new File(file.getParentFile(), file.getName() + ".invalid-orig");
            config = getDefaultConfig();
            try {
                Files.copy(file, backupFile);
                warningMessage = "due to an error in the config file, it has been backed up to"
                        + " extension '.invalid-orig' and overwritten with the default config";
            } catch (IOException f) {
                logger.warn("error making a copy of the invalid config file before overwriting it",
                        f);
                warningMessage = "due to an error in the config file, it has been overwritten with"
                        + " the default config";
            }
        }
        // it's nice to update config.json on startup if it is missing some/all config
        // properties so that the file contents can be reviewed/updated/copied if desired
        writeToFileIfNeeded(config, content);
        if (warningMessage != null) {
            logger.warn(warningMessage);
        }
        return config;
    }

    String getDefaultDisplayedTransactionType(List<InstrumentationConfig> configs) {
        for (PluginDescriptor descriptor : pluginDescriptors) {
            if (!descriptor.transactionTypes().isEmpty()) {
                return descriptor.transactionTypes().get(0);
            }
        }
        for (InstrumentationConfig config : configs) {
            if (!config.transactionType().isEmpty()) {
                return config.transactionType();
            }
        }
        return "";
    }

    private void writeToFileIfNeeded(Config config, String existingContent) throws IOException {
        String content = ConfigFile.writeValueAsString(config);
        if (content.equals(existingContent)) {
            // it's nice to preserve the correct modification stamp on the file to track when it was
            // last really changed
            return;
        }
        Files.write(content, file, Charsets.UTF_8);
    }

    Config getDefaultConfig() {
        return ImmutableConfig.builder()
                .addAllPluginConfigs(getDefaultPluginConfigs(pluginDescriptors))
                .addAllGaugeConfigs(getDefaultGaugeConfigs())
                .build();
    }

    void write(Config config) throws IOException {
        Files.write(writeValueAsString(config), file, Charsets.UTF_8);
    }

    @OnlyUsedByTests
    void delete() throws IOException {
        if (!file.delete()) {
            throw new IOException("Could not delete file: " + file.getCanonicalPath());
        }
    }

    private static List<GaugeConfig> getDefaultGaugeConfigs() {
        List<GaugeConfig> defaultGaugeConfigs = Lists.newArrayList();
        defaultGaugeConfigs.add(ImmutableGaugeConfig.builder()
                .mbeanObjectName("java.lang:type=Memory")
                .addMbeanAttributes(ImmutableMBeanAttribute.of("HeapMemoryUsage/used", false))
                .build());
        defaultGaugeConfigs.add(ImmutableGaugeConfig.builder()
                .mbeanObjectName("java.lang:type=GarbageCollector,name=*")
                .addMbeanAttributes(ImmutableMBeanAttribute.of("CollectionCount", true))
                .addMbeanAttributes(ImmutableMBeanAttribute.of("CollectionTime", true))
                .build());
        defaultGaugeConfigs.add(ImmutableGaugeConfig.builder()
                .mbeanObjectName("java.lang:type=MemoryPool,name=*")
                .addMbeanAttributes(ImmutableMBeanAttribute.of("Usage/used", false))
                .build());
        ImmutableGaugeConfig.Builder operatingSystemMBean = ImmutableGaugeConfig.builder()
                .mbeanObjectName("java.lang:type=OperatingSystem")
                .addMbeanAttributes(ImmutableMBeanAttribute.of("FreePhysicalMemorySize", false));
        if (!JavaVersion.isJava6()) {
            // these are only available since 1.7
            operatingSystemMBean
                    .addMbeanAttributes(ImmutableMBeanAttribute.of("ProcessCpuLoad", false));
            operatingSystemMBean
                    .addMbeanAttributes(ImmutableMBeanAttribute.of("SystemCpuLoad", false));
        }
        defaultGaugeConfigs.add(operatingSystemMBean.build());
        return defaultGaugeConfigs;
    }

    private static String writeValueAsString(Config config) throws IOException {
        CustomPrettyPrinter prettyPrinter = new CustomPrettyPrinter();
        prettyPrinter.indentArraysWith(DefaultIndenter.SYSTEM_LINEFEED_INSTANCE);
        StringBuilder sb = new StringBuilder();
        JsonGenerator jg = mapper.getFactory().createGenerator(CharStreams.asWriter(sb))
                .setPrettyPrinter(prettyPrinter);
        jg.writeObject(config);
        jg.close();
        // newline is not required, just a personal preference
        return sb.toString() + NEWLINE;
    }

    private static List<PluginConfig> getDefaultPluginConfigs(
            List<PluginDescriptor> pluginDescriptors) {
        List<PluginConfig> pluginConfigs = Lists.newArrayList();
        for (PluginDescriptor pluginDescriptor : pluginDescriptors) {
            ImmutablePluginConfig.Builder builder = ImmutablePluginConfig.builder()
                    .id(pluginDescriptor.id())
                    .enabled(true);
            for (PropertyDescriptor propertyDescriptor : pluginDescriptor.properties()) {
                PropertyValue defaultValue = propertyDescriptor.getValidatedNonNullDefaultValue();
                builder.putProperties(propertyDescriptor.name(), defaultValue);
            }
            pluginConfigs.add(builder.build());
        }
        return pluginConfigs;
    }

    public StorageConfig withCorrectedLists(StorageConfig storageConfig) {
        StorageConfig defaultConfig = ImmutableStorageConfig.builder().build();
        ImmutableList<Integer> rollupExpirationHours =
                fix(storageConfig.rollupExpirationHours(), defaultConfig.rollupExpirationHours());
        ImmutableList<Integer> rollupCappedDatabaseSizesMb =
                fix(storageConfig.rollupCappedDatabaseSizesMb(),
                        defaultConfig.rollupCappedDatabaseSizesMb());
        return ImmutableStorageConfig.builder()
                .copyFrom(storageConfig)
                .rollupExpirationHours(rollupExpirationHours)
                .rollupCappedDatabaseSizesMb(rollupCappedDatabaseSizesMb)
                .build();
    }

    private ImmutableList<Integer> fix(ImmutableList<Integer> thisList, List<Integer> defaultList) {
        if (thisList.size() >= defaultList.size()) {
            return thisList.subList(0, defaultList.size());
        }
        List<Integer> correctedList = Lists.newArrayList(thisList);
        for (int i = thisList.size(); i < defaultList.size(); i++) {
            correctedList.add(defaultList.get(i));
        }
        return ImmutableList.copyOf(correctedList);
    }

    @SuppressWarnings("serial")
    private static class CustomPrettyPrinter extends DefaultPrettyPrinter {

        @Override
        public void writeObjectFieldValueSeparator(JsonGenerator jg) throws IOException {
            jg.writeRaw(": ");
        }
    }

    private static final class PluginDescriptorOrdering extends Ordering<PluginDescriptor> {
        @Override
        public int compare(@Nullable PluginDescriptor left, @Nullable PluginDescriptor right) {
            checkNotNull(left);
            checkNotNull(right);
            return left.id().compareToIgnoreCase(right.id());
        }
    }
}
