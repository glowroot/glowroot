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
package org.glowroot.local.ui;

import java.io.IOException;
import java.util.List;
import java.util.Set;

import javax.annotation.Nullable;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;
import com.google.common.collect.Ordering;
import com.google.common.collect.Sets;
import com.google.common.io.CharStreams;

import org.glowroot.api.PluginServices.ConfigListener;
import org.glowroot.common.ObjectMappers;
import org.glowroot.config.ConfigService;
import org.glowroot.config.PluginDescriptor;
import org.glowroot.config.UserInterfaceConfig;
import org.glowroot.jvm.HeapDumps;
import org.glowroot.jvm.OptionalService;

import static java.util.concurrent.TimeUnit.HOURS;

class LayoutService {

    private static final JsonFactory jsonFactory = new JsonFactory();
    private static final ObjectMapper mapper = ObjectMappers.create();

    private final String version;
    private final ConfigService configService;
    private final ImmutableList<PluginDescriptor> pluginDescriptors;
    private final OptionalService<HeapDumps> heapDumps;
    private final long gaugeCollectionIntervalMillis;

    private volatile @Nullable Layout layout;

    LayoutService(String version, ConfigService configService,
            List<PluginDescriptor> pluginDescriptors, OptionalService<HeapDumps> heapDumps,
            long gaugeCollectionIntervalMillis) {
        this.version = version;
        this.configService = configService;
        this.pluginDescriptors = ImmutableList.copyOf(pluginDescriptors);
        this.heapDumps = heapDumps;
        this.gaugeCollectionIntervalMillis = gaugeCollectionIntervalMillis;
        ConfigListener listener = new ConfigListener() {
            @Override
            public void onChange() {
                layout = null;
            }
        };
        for (PluginDescriptor pluginDescriptor : pluginDescriptors) {
            configService.addPluginConfigListener(pluginDescriptor.id(), listener);
        }
        configService.addConfigListener(listener);
    }

    String getLayout() throws IOException {
        Layout localLayout = layout;
        if (localLayout == null) {
            localLayout = buildLayout(version, configService, pluginDescriptors,
                    heapDumps.getService(), gaugeCollectionIntervalMillis);
            layout = localLayout;
        }
        return mapper.writeValueAsString(localLayout);
    }

    String getLayoutVersion() {
        Layout localLayout = layout;
        if (localLayout == null) {
            localLayout = buildLayout(version, configService, pluginDescriptors,
                    heapDumps.getService(), gaugeCollectionIntervalMillis);
            layout = localLayout;
        }
        return localLayout.version();
    }

    String getNeedsAuthenticationLayout() throws IOException {
        UserInterfaceConfig userInterfaceConfig = configService.getUserInterfaceConfig();
        StringBuilder sb = new StringBuilder();
        JsonGenerator jg = jsonFactory.createGenerator(CharStreams.asWriter(sb));
        jg.writeStartObject();
        jg.writeBooleanField("needsAuthentication", true);
        jg.writeBooleanField("readOnlyPasswordEnabled",
                userInterfaceConfig.readOnlyPasswordEnabled());
        jg.writeStringField("footerMessage", "Glowroot version " + version);
        jg.writeEndObject();
        jg.close();
        return sb.toString();
    }

    private static Layout buildLayout(String version, ConfigService configService,
            List<PluginDescriptor> pluginDescriptors, @Nullable HeapDumps heapDumps,
            long gaugeCollectionIntervalMillis) {
        // use linked hash set to maintain ordering in case there is no default transaction type
        List<String> transactionTypes = Lists.newArrayList(configService.getAllTransactionTypes());
        String defaultDisplayedTransactionType = configService.getDefaultDisplayedTransactionType();
        List<String> orderedTransactionTypes = Lists.newArrayList();
        if (transactionTypes.isEmpty()) {
            defaultDisplayedTransactionType = "NO TRANSACTION TYPES DEFINED";
        } else {
            if (!transactionTypes.contains(defaultDisplayedTransactionType)) {
                defaultDisplayedTransactionType = transactionTypes.iterator().next();
            }
            transactionTypes.remove(defaultDisplayedTransactionType);
        }
        // add default transaction type first
        orderedTransactionTypes.add(defaultDisplayedTransactionType);
        // add the rest alphabetical
        orderedTransactionTypes.addAll(
                Ordering.from(String.CASE_INSENSITIVE_ORDER).sortedCopy(transactionTypes));
        Set<String> transactionCustomAttributes = Sets.newTreeSet();
        for (PluginDescriptor pluginDescriptor : pluginDescriptors) {
            transactionCustomAttributes.addAll(pluginDescriptor.transactionCustomAttributes());
        }
        List<Long> rollupExpirationMillis = Lists.newArrayList();
        for (long hours : configService.getStorageConfig().rollupExpirationHours()) {
            rollupExpirationMillis.add(HOURS.toMillis(hours));
        }
        UserInterfaceConfig userInterfaceConfig = configService.getUserInterfaceConfig();
        return Layout.builder()
                .jvmHeapDump(heapDumps != null)
                .footerMessage("Glowroot version " + version)
                .adminPasswordEnabled(userInterfaceConfig.adminPasswordEnabled())
                .readOnlyPasswordEnabled(userInterfaceConfig.readOnlyPasswordEnabled())
                .anonymousAccess(userInterfaceConfig.anonymousAccess())
                .addAllTransactionTypes(orderedTransactionTypes)
                .defaultTransactionType(defaultDisplayedTransactionType)
                .addAllDefaultPercentiles(
                        configService.getTransactionConfig().defaultDisplayedPercentiles())
                .addAllTransactionCustomAttributes(transactionCustomAttributes)
                .addAllRollupConfigs(configService.getRollupConfigs())
                .addAllRollupExpirationMillis(rollupExpirationMillis)
                .gaugeCollectionIntervalMillis(gaugeCollectionIntervalMillis)
                .build();
    }
}
