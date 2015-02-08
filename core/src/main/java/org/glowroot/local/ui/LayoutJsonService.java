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
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;
import com.google.common.collect.Ordering;
import com.google.common.collect.Sets;
import com.google.common.io.CharStreams;

import org.glowroot.api.PluginServices.ConfigListener;
import org.glowroot.common.Marshaling2;
import org.glowroot.config.ConfigService;
import org.glowroot.config.PluginDescriptor;
import org.glowroot.config.UserInterfaceConfig;
import org.glowroot.jvm.HeapDumps;
import org.glowroot.jvm.OptionalService;

class LayoutJsonService {

    private static final JsonFactory jsonFactory = new JsonFactory();

    private final String version;
    private final ConfigService configService;
    private final ImmutableList<PluginDescriptor> pluginDescriptors;
    private final OptionalService<HeapDumps> heapDumps;
    private final long fixedAggregateIntervalSeconds;
    private final long fixedAggregateRollupSeconds;
    private final long fixedGaugeIntervalSeconds;
    private final long fixedGaugeRollupSeconds;

    private volatile @Nullable Layout layout;

    LayoutJsonService(String version, ConfigService configService,
            List<PluginDescriptor> pluginDescriptors, OptionalService<HeapDumps> heapDumps,
            long fixedAggregateIntervalSeconds, long fixedAggregateRollupSeconds,
            long fixedGaugeIntervalSeconds, long fixedGaugeRollupSeconds) {
        this.version = version;
        this.configService = configService;
        this.pluginDescriptors = ImmutableList.copyOf(pluginDescriptors);
        this.heapDumps = heapDumps;
        this.fixedAggregateIntervalSeconds = fixedAggregateIntervalSeconds;
        this.fixedAggregateRollupSeconds = fixedAggregateRollupSeconds;
        this.fixedGaugeIntervalSeconds = fixedGaugeIntervalSeconds;
        this.fixedGaugeRollupSeconds = fixedGaugeRollupSeconds;
        configService.addConfigListener(new ConfigListener() {
            @Override
            public void onChange() {
                layout = null;
            }
        });
    }

    String getLayout() throws IOException {
        Layout localLayout = layout;
        if (localLayout == null) {
            localLayout = buildLayout(version, configService, pluginDescriptors,
                    heapDumps.getService(), fixedAggregateIntervalSeconds,
                    fixedAggregateRollupSeconds, fixedGaugeIntervalSeconds,
                    fixedGaugeRollupSeconds);
            layout = localLayout;
        }
        return Marshaling2.toJson(localLayout);
    }

    String getLayoutVersion() {
        Layout localLayout = layout;
        if (localLayout == null) {
            localLayout = buildLayout(version, configService, pluginDescriptors,
                    heapDumps.getService(), fixedAggregateIntervalSeconds,
                    fixedAggregateRollupSeconds, fixedGaugeIntervalSeconds,
                    fixedGaugeRollupSeconds);
            layout = localLayout;
        }
        return localLayout.version();
    }

    String getNeedsAuthenticationLayout() throws IOException {
        StringBuilder sb = new StringBuilder();
        JsonGenerator jg = jsonFactory.createGenerator(CharStreams.asWriter(sb));
        jg.writeStartObject();
        jg.writeBooleanField("needsAuthentication", true);
        jg.writeStringField("footerMessage", "version " + version);
        jg.writeEndObject();
        jg.close();
        return sb.toString();
    }

    private static Layout buildLayout(String version, ConfigService configService,
            List<PluginDescriptor> pluginDescriptors, @Nullable HeapDumps heapDumps,
            long fixedAggregateIntervalSeconds, long fixedAggregateRollupSeconds,
            long fixedGaugeIntervalSeconds, long fixedGaugeRollupSeconds) {
        // use linked hash set to maintain ordering in case there is no default transaction type
        List<String> transactionTypes = Lists.newArrayList(configService.getAllTransactionTypes());
        String defaultTransactionType = configService.getDefaultTransactionType();
        List<String> orderedTransactionTypes = Lists.newArrayList();
        if (defaultTransactionType.isEmpty()) {
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
        orderedTransactionTypes.addAll(
                Ordering.from(String.CASE_INSENSITIVE_ORDER).sortedCopy(transactionTypes));
        Set<String> transactionCustomAttributes = Sets.newTreeSet();
        for (PluginDescriptor pluginDescriptor : pluginDescriptors) {
            transactionCustomAttributes.addAll(pluginDescriptor.transactionCustomAttributes());
        }
        UserInterfaceConfig userInterfaceConfig = configService.getUserInterfaceConfig();
        return ImmutableLayout.builder()
                .jvmHeapDump(heapDumps != null)
                .footerMessage("version " + version)
                .adminPasswordEnabled(userInterfaceConfig.adminPasswordEnabled())
                .readOnlyPasswordEnabled(userInterfaceConfig.readOnlyPasswordEnabled())
                .anonymousAccess(userInterfaceConfig.anonymousAccess())
                .addAllTransactionTypes(orderedTransactionTypes)
                .defaultTransactionType(defaultTransactionType)
                .addAllTransactionCustomAttributes(transactionCustomAttributes)
                .fixedAggregateIntervalSeconds(fixedAggregateIntervalSeconds)
                .fixedAggregateRollupSeconds(fixedAggregateRollupSeconds)
                .fixedGaugeIntervalSeconds(fixedGaugeIntervalSeconds)
                .fixedGaugeRollupSeconds(fixedGaugeRollupSeconds)
                .build();
    }
}
