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

import java.io.IOException;
import java.util.List;
import java.util.Set;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;
import com.google.common.collect.Ordering;
import com.google.common.collect.Sets;
import com.google.common.io.CharStreams;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.glowroot.api.PluginServices.ConfigListener;
import org.glowroot.common.ObjectMappers;
import org.glowroot.config.ConfigService;
import org.glowroot.config.PluginDescriptor;
import org.glowroot.config.PluginDescriptorCache;
import org.glowroot.config.PointcutConfig;
import org.glowroot.jvm.HeapDumps;
import org.glowroot.jvm.HeapHistograms;
import org.glowroot.jvm.OptionalService;
import org.glowroot.local.ui.Layout.LayoutPlugin;
import org.glowroot.markers.Singleton;

/**
 * Service to read basic ui layout info.
 * 
 * @author Trask Stalnaker
 * @since 0.5
 */
@Singleton
@JsonService
class LayoutJsonService {

    private static final Logger logger = LoggerFactory.getLogger(LayoutJsonService.class);
    private static final ObjectMapper mapper = ObjectMappers.create();

    private final String version;
    private final ConfigService configService;
    private final PluginDescriptorCache pluginDescriptorCache;
    private final OptionalService<HeapHistograms> heapHistograms;
    private final OptionalService<HeapDumps> heapDumps;
    private final long fixedTransactionPointIntervalSeconds;

    @Nullable
    private volatile Layout layout;

    LayoutJsonService(String version, ConfigService configService,
            PluginDescriptorCache pluginDescriptorCache,
            OptionalService<HeapHistograms> heapHistograms, OptionalService<HeapDumps> heapDumps,
            long fixedTransactionPointIntervalSeconds) {
        this.version = version;
        this.configService = configService;
        this.pluginDescriptorCache = pluginDescriptorCache;
        this.heapHistograms = heapHistograms;
        this.heapDumps = heapDumps;
        this.fixedTransactionPointIntervalSeconds = fixedTransactionPointIntervalSeconds;
        configService.addConfigListener(new ConfigListener() {
            @Override
            public void onChange() {
                layout = null;
            }
        });
    }

    // this is only accessed from the url when running under 'grunt server' and is just to get back
    // layout data (or find out login is needed if required)
    @GET("/backend/layout")
    String getLayout() throws IOException {
        logger.debug("getLayout()");
        Layout localLayout = layout;
        if (localLayout == null) {
            localLayout = buildLayout(version, configService, pluginDescriptorCache,
                    heapHistograms.getService(), heapDumps.getService(),
                    fixedTransactionPointIntervalSeconds);
            layout = localLayout;
        }
        return mapper.writeValueAsString(localLayout);
    }

    String getLayoutVersion() {
        Layout localLayout = layout;
        if (localLayout == null) {
            localLayout = buildLayout(version, configService, pluginDescriptorCache,
                    heapHistograms.getService(), heapDumps.getService(),
                    fixedTransactionPointIntervalSeconds);
            layout = localLayout;
        }
        return localLayout.getVersion();
    }

    String getUnauthenticatedLayout() throws IOException {
        logger.debug("getUnauthenticatedLayout()");
        StringBuilder sb = new StringBuilder();
        JsonGenerator jg = mapper.getFactory().createGenerator(CharStreams.asWriter(sb));
        jg.writeStartObject();
        jg.writeBooleanField("needsAuthentication", true);
        jg.writeStringField("footerMessage", "version " + version);
        jg.writeEndObject();
        jg.close();
        return sb.toString();
    }

    private static Layout buildLayout(String version, ConfigService configService,
            PluginDescriptorCache pluginDescriptorCache, @Nullable HeapHistograms heapHistograms,
            @Nullable HeapDumps heapDumps, long fixedTransactionPointIntervalSeconds) {
        List<LayoutPlugin> plugins = Lists.newArrayList();
        for (PluginDescriptor pluginDescriptor : pluginDescriptorCache.getPluginDescriptors()) {
            String id = pluginDescriptor.getId();
            String name = pluginDescriptor.getName();
            // by convention, strip off trailing " Plugin"
            if (name.endsWith(" Plugin")) {
                name = name.substring(0, name.lastIndexOf(" Plugin"));
            }
            plugins.add(new LayoutPlugin(id, name));
        }
        // use linked hash set to maintain ordering in case there is no default transaction type
        Set<String> transactionTypes = Sets.newLinkedHashSet();
        for (PluginDescriptor pluginDescriptor : pluginDescriptorCache.getPluginDescriptors()) {
            transactionTypes.addAll(pluginDescriptor.getTransactionTypes());
        }
        for (PointcutConfig pointcutConfig : configService.getPointcutConfigs()) {
            String transactionType = pointcutConfig.getTransactionType();
            if (!transactionType.isEmpty()) {
                transactionTypes.add(transactionType);
            }
        }
        String defaultTransactionType =
                configService.getUserInterfaceConfig().getDefaultTransactionType();
        List<String> orderedTransactionTypes = Lists.newArrayList();
        if (transactionTypes.isEmpty()) {
            defaultTransactionType = "<no transaction types defined>";
        } else {
            if (!transactionTypes.contains(defaultTransactionType)) {
                defaultTransactionType = transactionTypes.iterator().next();
            }
            transactionTypes.remove(defaultTransactionType);
        }
        // add default transaction type first
        orderedTransactionTypes.add(defaultTransactionType);
        // add the rest alphabetical
        orderedTransactionTypes.addAll(Ordering.from(String.CASE_INSENSITIVE_ORDER)
                .sortedCopy(transactionTypes));
        Set<String> traceCustomAttributes = Sets.newTreeSet();
        for (PluginDescriptor pluginDescriptor : pluginDescriptorCache.getPluginDescriptors()) {
            traceCustomAttributes.addAll(pluginDescriptor.getTraceCustomAttributes());
        }
        return Layout.builder()
                .jvmHeapHistogram(heapHistograms != null)
                .jvmHeapDump(heapDumps != null)
                .footerMessage("version " + version)
                .passwordEnabled(configService.getUserInterfaceConfig().isPasswordEnabled())
                .plugins(plugins)
                .transactionTypes(orderedTransactionTypes)
                .defaultTransactionType(defaultTransactionType)
                .traceCustomAttributes(ImmutableList.copyOf(traceCustomAttributes))
                .fixedTransactionPointIntervalSeconds(fixedTransactionPointIntervalSeconds)
                .build();
    }
}
