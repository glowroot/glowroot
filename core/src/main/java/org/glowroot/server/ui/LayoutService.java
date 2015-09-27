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
package org.glowroot.server.ui;

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
import org.immutables.value.Value;

import org.glowroot.common.config.PluginDescriptor;
import org.glowroot.common.config.Versions;
import org.glowroot.common.util.ObjectMappers;
import org.glowroot.plugin.api.config.ConfigListener;
import org.glowroot.server.repo.ConfigRepository;
import org.glowroot.server.repo.ConfigRepository.RollupConfig;
import org.glowroot.server.repo.config.UserInterfaceConfig;
import org.glowroot.server.repo.config.UserInterfaceConfig.AnonymousAccess;

import static java.util.concurrent.TimeUnit.HOURS;

class LayoutService {

    private static final JsonFactory jsonFactory = new JsonFactory();
    private static final ObjectMapper mapper = ObjectMappers.create();

    private final String version;
    private final ConfigRepository configRepository;
    private final ImmutableList<PluginDescriptor> pluginDescriptors;

    private volatile @Nullable Layout layout;

    LayoutService(String version, ConfigRepository configRepository,
            List<PluginDescriptor> pluginDescriptors) {
        this.version = version;
        this.configRepository = configRepository;
        this.pluginDescriptors = ImmutableList.copyOf(pluginDescriptors);
        ConfigListener listener = new ConfigListener() {
            @Override
            public void onChange() {
                layout = null;
            }
        };
        configRepository.addListener(listener);
    }

    String getLayout() throws IOException {
        Layout localLayout = layout;
        if (localLayout == null) {
            localLayout = buildLayout(version, configRepository, pluginDescriptors);
            layout = localLayout;
        }
        return mapper.writeValueAsString(localLayout);
    }

    String getLayoutVersion() {
        Layout localLayout = layout;
        if (localLayout == null) {
            localLayout = buildLayout(version, configRepository, pluginDescriptors);
            layout = localLayout;
        }
        return localLayout.version();
    }

    String getNeedsAuthenticationLayout() throws IOException {
        UserInterfaceConfig userInterfaceConfig = configRepository.getUserInterfaceConfig();
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

    private static Layout buildLayout(String version, ConfigRepository configRepository,
            List<PluginDescriptor> pluginDescriptors) {

        // FIXME
        long serverId = 0;

        // use linked hash set to maintain ordering in case there is no default transaction type
        List<String> transactionTypes =
                Lists.newArrayList(configRepository.getAllTransactionTypes(serverId));
        String defaultDisplayedTransactionType =
                configRepository.getDefaultDisplayedTransactionType(serverId);
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
        orderedTransactionTypes
                .addAll(Ordering.from(String.CASE_INSENSITIVE_ORDER).sortedCopy(transactionTypes));
        Set<String> transactionAttributes = Sets.newTreeSet();
        for (PluginDescriptor pluginDescriptor : pluginDescriptors) {
            transactionAttributes.addAll(pluginDescriptor.transactionAttributes());
        }
        List<Long> rollupExpirationMillis = Lists.newArrayList();
        for (long hours : configRepository.getStorageConfig().rollupExpirationHours()) {
            rollupExpirationMillis.add(HOURS.toMillis(hours));
        }
        UserInterfaceConfig userInterfaceConfig = configRepository.getUserInterfaceConfig();
        return ImmutableLayout.builder()
                .footerMessage("Glowroot version " + version)
                .adminPasswordEnabled(userInterfaceConfig.adminPasswordEnabled())
                .readOnlyPasswordEnabled(userInterfaceConfig.readOnlyPasswordEnabled())
                .anonymousAccess(userInterfaceConfig.anonymousAccess())
                .addAllTransactionTypes(orderedTransactionTypes)
                .defaultTransactionType(defaultDisplayedTransactionType)
                .addAllDefaultPercentiles(userInterfaceConfig.defaultDisplayedPercentiles())
                .addAllTransactionAttributes(transactionAttributes)
                .addAllRollupConfigs(configRepository.getRollupConfigs())
                .addAllRollupExpirationMillis(rollupExpirationMillis)
                .gaugeCollectionIntervalMillis(configRepository.getGaugeCollectionIntervalMillis())
                .build();
    }

    @Value.Immutable
    abstract static class Layout {

        abstract String footerMessage();
        abstract boolean adminPasswordEnabled();
        abstract boolean readOnlyPasswordEnabled();
        abstract AnonymousAccess anonymousAccess();
        abstract ImmutableList<String> transactionTypes();
        abstract String defaultTransactionType();
        abstract ImmutableList<Double> defaultPercentiles();
        abstract ImmutableList<String> transactionAttributes();
        abstract ImmutableList<RollupConfig> rollupConfigs();
        abstract ImmutableList<Long> rollupExpirationMillis();
        abstract long gaugeCollectionIntervalMillis();

        @Value.Derived
        public String version() {
            return Versions.getVersion(this);
        }
    }
}
