/*
 * Copyright 2013-2016 the original author or authors.
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
package org.glowroot.ui;

import java.util.List;
import java.util.Map;
import java.util.Set;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;
import com.google.common.io.CharStreams;
import org.immutables.value.Value;

import org.glowroot.common.util.ObjectMappers;
import org.glowroot.common.util.Versions;
import org.glowroot.storage.config.UserInterfaceConfig;
import org.glowroot.storage.config.UserInterfaceConfig.AnonymousAccess;
import org.glowroot.storage.repo.AgentRepository;
import org.glowroot.storage.repo.AgentRepository.AgentRollup;
import org.glowroot.storage.repo.ConfigRepository;
import org.glowroot.storage.repo.ConfigRepository.RollupConfig;
import org.glowroot.storage.repo.TransactionTypeRepository;

import static java.util.concurrent.TimeUnit.HOURS;

class LayoutService {

    private static final String AGENT_ID = "";

    private static final JsonFactory jsonFactory = new JsonFactory();
    private static final ObjectMapper mapper = ObjectMappers.create();

    private final boolean fat;
    private final String version;
    private final ConfigRepository configRepository;
    private final AgentRepository agentRepository;
    private final TransactionTypeRepository transactionTypeRepository;

    LayoutService(boolean fat, String version, ConfigRepository configRepository,
            AgentRepository agentRepository,
            TransactionTypeRepository transactionTypeRepository) {
        this.fat = fat;
        this.version = version;
        this.configRepository = configRepository;
        this.agentRepository = agentRepository;
        this.transactionTypeRepository = transactionTypeRepository;
    }

    String getLayout() throws Exception {
        Layout layout = buildLayout();
        return mapper.writeValueAsString(layout);
    }

    String getLayoutVersion() throws Exception {
        Layout layout = buildLayout();
        return layout.version();
    }

    String getNeedsAuthenticationLayout() throws Exception {
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

    private Layout buildLayout() throws Exception {
        List<Long> rollupExpirationMillis = Lists.newArrayList();
        for (long hours : configRepository.getStorageConfig().rollupExpirationHours()) {
            rollupExpirationMillis.add(HOURS.toMillis(hours));
        }
        UserInterfaceConfig userInterfaceConfig = configRepository.getUserInterfaceConfig();

        String defaultDisplayedTransactionType =
                userInterfaceConfig.defaultDisplayedTransactionType();

        // linked hash map to preserve ordering
        Map<String, AgentRollupLayout> agentRollups = Maps.newLinkedHashMap();
        Map<String, List<String>> transactionTypesMap =
                transactionTypeRepository.readTransactionTypes();
        if (fat) {
            // a couple of special cases for fat agent
            Set<String> transactionTypes = Sets.newHashSet();
            List<String> storedTransactionTypes = transactionTypesMap.get(AGENT_ID);
            if (storedTransactionTypes != null) {
                transactionTypes.addAll(storedTransactionTypes);
            }
            transactionTypes.add(defaultDisplayedTransactionType);
            agentRollups.put(AGENT_ID, ImmutableAgentRollupLayout.builder()
                    .leaf(true)
                    .addAllTransactionTypes(transactionTypes)
                    .build());
        } else {
            for (AgentRollup agentRollup : agentRepository.readAgentRollups()) {
                ImmutableAgentRollupLayout.Builder builder = ImmutableAgentRollupLayout.builder()
                        .leaf(agentRollup.leaf());
                List<String> transactionTypes = transactionTypesMap.get(agentRollup.name());
                if (transactionTypes != null) {
                    builder.addAllTransactionTypes(transactionTypes);
                }
                agentRollups.put(agentRollup.name(), builder.build());
            }
        }
        return ImmutableLayout.builder()
                .fat(fat)
                .footerMessage("Glowroot version " + version)
                .adminPasswordEnabled(userInterfaceConfig.adminPasswordEnabled())
                .readOnlyPasswordEnabled(userInterfaceConfig.readOnlyPasswordEnabled())
                .anonymousAccess(userInterfaceConfig.anonymousAccess())
                .addAllRollupConfigs(configRepository.getRollupConfigs())
                .addAllRollupExpirationMillis(rollupExpirationMillis)
                .gaugeCollectionIntervalMillis(configRepository.getGaugeCollectionIntervalMillis())
                .defaultTransactionType(defaultDisplayedTransactionType)
                .addAllDefaultPercentiles(userInterfaceConfig.defaultDisplayedPercentiles())
                .agentRollups(agentRollups)
                .build();
    }

    @Value.Immutable
    abstract static class Layout {

        abstract boolean fat();
        abstract String footerMessage();
        abstract boolean adminPasswordEnabled();
        abstract boolean readOnlyPasswordEnabled();
        abstract AnonymousAccess anonymousAccess();
        abstract ImmutableList<RollupConfig> rollupConfigs();
        abstract ImmutableList<Long> rollupExpirationMillis();
        abstract long gaugeCollectionIntervalMillis();

        abstract String defaultTransactionType();
        abstract ImmutableList<Double> defaultPercentiles();
        abstract ImmutableMap<String, AgentRollupLayout> agentRollups();

        @Value.Derived
        public String version() {
            return Versions.getJsonVersion(this);
        }
    }

    @Value.Immutable
    interface AgentRollupLayout {
        boolean leaf();
        List<String> transactionTypes();
    }
}
