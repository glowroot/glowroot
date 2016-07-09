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

import javax.annotation.Nullable;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;
import org.immutables.value.Value;

import org.glowroot.common.util.ObjectMappers;
import org.glowroot.common.util.Versions;
import org.glowroot.storage.repo.AgentRepository;
import org.glowroot.storage.repo.AgentRepository.AgentRollup;
import org.glowroot.storage.repo.ConfigRepository;
import org.glowroot.storage.repo.ConfigRepository.RollupConfig;
import org.glowroot.storage.repo.TransactionTypeRepository;
import org.glowroot.ui.HttpSessionManager.Authentication;
import org.glowroot.wire.api.model.AgentConfigOuterClass.AgentConfig.UiConfig;

import static com.google.common.base.Preconditions.checkNotNull;
import static java.util.concurrent.TimeUnit.HOURS;

class LayoutService {

    private static final String AGENT_ID = "";

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

    String getLayout(Authentication authentication) throws Exception {
        Layout layout = buildLayout(authentication);
        return mapper.writeValueAsString(layout);
    }

    String getLayoutVersion(Authentication authentication) throws Exception {
        Layout layout = buildLayout(authentication);
        return layout.version();
    }

    private Layout buildLayout(Authentication authentication) throws Exception {
        // linked hash map to preserve ordering
        Map<String, AgentRollupLayout> agentRollups = Maps.newLinkedHashMap();
        Map<String, List<String>> transactionTypesMap =
                transactionTypeRepository.readTransactionTypes();
        boolean showNavbarConfig = false;
        boolean hasSomeAccess = false;
        if (fat && authentication.isPermitted(AGENT_ID, "agent:view")) {
            hasSomeAccess = true;
            // a couple of special cases for fat agent
            UiConfig uiConfig = checkNotNull(configRepository.getUiConfig(AGENT_ID));
            String defaultDisplayedTransactionType =
                    uiConfig.getDefaultDisplayedTransactionType();
            Set<String> transactionTypes = Sets.newHashSet();
            List<String> storedTransactionTypes = transactionTypesMap.get(AGENT_ID);
            if (storedTransactionTypes != null) {
                transactionTypes.addAll(storedTransactionTypes);
            }
            transactionTypes.add(defaultDisplayedTransactionType);

            Permissions permissions = getPermissions(authentication, AGENT_ID);
            agentRollups.put(AGENT_ID, ImmutableAgentRollupLayout.builder()
                    .leaf(true)
                    .permissions(permissions)
                    .addAllTransactionTypes(transactionTypes)
                    .defaultDisplayedTransactionType(defaultDisplayedTransactionType)
                    .defaultDisplayedPercentiles(uiConfig.getDefaultDisplayedPercentileList())
                    .build());
            showNavbarConfig = checkNotNull(permissions.config()).view();
        } else if (!fat) {
            hasSomeAccess = authentication.isPermitted("admin");
            for (AgentRollup agentRollup : agentRepository.readAgentRollups()) {
                if (!authentication.isPermitted(agentRollup.name(), "agent:view")) {
                    continue;
                }
                hasSomeAccess = true;
                UiConfig uiConfig = configRepository.getUiConfig(agentRollup.name());
                String defaultDisplayedTransactionType;
                List<Double> defaultDisplayedPercentiles;
                if (uiConfig == null) {
                    // TODO these defaults should be shared with UiConfig defaults
                    defaultDisplayedTransactionType = "Web";
                    defaultDisplayedPercentiles = ImmutableList.of(50.0, 95.0, 99.0);
                } else {
                    defaultDisplayedTransactionType = uiConfig.getDefaultDisplayedTransactionType();
                    defaultDisplayedPercentiles = uiConfig.getDefaultDisplayedPercentileList();
                }
                Set<String> transactionTypes = Sets.newHashSet();
                boolean leaf = agentRollup.leaf();
                ImmutableAgentRollupLayout.Builder builder = ImmutableAgentRollupLayout.builder()
                        .leaf(leaf);
                if (leaf) {
                    Permissions permissions = getPermissions(authentication, agentRollup.name());
                    builder.permissions(permissions);
                    showNavbarConfig = showNavbarConfig || permissions.config().view();
                }
                List<String> storedTransactionTypes = transactionTypesMap.get(agentRollup.name());
                if (storedTransactionTypes != null) {
                    transactionTypes.addAll(storedTransactionTypes);
                }
                transactionTypes.add(defaultDisplayedTransactionType);
                builder.addAllTransactionTypes(transactionTypes);
                builder.defaultDisplayedTransactionType(defaultDisplayedTransactionType);
                builder.defaultDisplayedPercentiles(defaultDisplayedPercentiles);
                agentRollups.put(agentRollup.name(), builder.build());
            }
        }
        if (hasSomeAccess) {
            List<Long> rollupExpirationMillis = Lists.newArrayList();
            for (long hours : configRepository.getStorageConfig().rollupExpirationHours()) {
                rollupExpirationMillis.add(HOURS.toMillis(hours));
            }
            return ImmutableLayout.builder()
                    .fat(fat)
                    .footerMessage("Glowroot version " + version)
                    .hideLogin(!configRepository.namedUsersExist())
                    .addAllRollupConfigs(configRepository.getRollupConfigs())
                    .addAllRollupExpirationMillis(rollupExpirationMillis)
                    .gaugeCollectionIntervalMillis(
                            configRepository.getGaugeCollectionIntervalMillis())
                    .agentRollups(agentRollups)
                    .showNavbarConfig(showNavbarConfig)
                    .admin(authentication.isPermitted("admin"))
                    .loggedIn(!authentication.anonymous())
                    .ldap(authentication.ldap())
                    .redirectToLogin(false)
                    .build();
        } else {
            return ImmutableLayout.builder()
                    .fat(fat)
                    .footerMessage("Glowroot version " + version)
                    .hideLogin(false)
                    .gaugeCollectionIntervalMillis(0)
                    .showNavbarConfig(false)
                    .admin(false)
                    .loggedIn(!authentication.anonymous())
                    .ldap(authentication.ldap())
                    .redirectToLogin(true)
                    .build();
        }
    }

    private Permissions getPermissions(Authentication authentication, String agentRollup) {
        return ImmutablePermissions.builder()
                .tool(ImmutableToolPermissions.builder()
                        .threadDump(
                                authentication.isPermitted(agentRollup, "agent:tool:threadDump"))
                        .heapDump(authentication.isPermitted(agentRollup, "agent:tool:heapDump"))
                        .gc(authentication.isPermitted(agentRollup, "agent:tool:gc"))
                        .mbeanTree(authentication.isPermitted(agentRollup, "agent:tool:mbeanTree"))
                        .capabilities(
                                authentication.isPermitted(agentRollup, "agent:tool:capabilities"))
                        .build())
                .config(ImmutableConfigPermissions.builder()
                        .view(authentication.isPermitted(agentRollup, "agent:config:view"))
                        .edit(ImmutableEditConfigPermissions.builder()
                                .transaction(authentication.isPermitted(agentRollup,
                                        "agent:config:edit:transaction"))
                                .gauge(authentication.isPermitted(agentRollup,
                                        "agent:config:edit:gauge"))
                                .alert(authentication.isPermitted(agentRollup,
                                        "agent:config:edit:alert"))
                                .ui(authentication.isPermitted(agentRollup, "agent:config:edit:ui"))
                                .plugin(authentication.isPermitted(agentRollup,
                                        "agent:config:edit:plugin"))
                                .instrumentation(authentication.isPermitted(agentRollup,
                                        "agent:config:edit:instrumentation"))
                                .advanced(authentication.isPermitted(agentRollup,
                                        "agent:config:edit:advanced"))
                                .userRecording(authentication.isPermitted(agentRollup,
                                        "agent:config:edit:userRecording"))
                                .build())
                        .build())
                .build();
    }

    @Value.Immutable
    abstract static class Layout {

        abstract boolean fat();
        abstract String footerMessage();
        abstract boolean hideLogin();
        abstract ImmutableList<RollupConfig> rollupConfigs();
        abstract ImmutableList<Long> rollupExpirationMillis();
        abstract long gaugeCollectionIntervalMillis();
        abstract ImmutableMap<String, AgentRollupLayout> agentRollups();
        abstract boolean showNavbarConfig();
        abstract boolean admin();
        abstract boolean loggedIn();
        abstract boolean ldap();
        abstract boolean redirectToLogin();

        @Value.Derived
        public String version() {
            return Versions.getJsonVersion(this);
        }
    }

    @Value.Immutable
    interface AgentRollupLayout {
        boolean leaf();
        @Nullable
        Permissions permissions(); // null for non-leaf agent rollup
        List<String> transactionTypes();
        String defaultDisplayedTransactionType();
        List<Double> defaultDisplayedPercentiles();
    }

    @Value.Immutable
    interface Permissions {
        ToolPermissions tool();
        ConfigPermissions config();
    }

    @Value.Immutable
    interface ToolPermissions {
        boolean threadDump();
        boolean heapDump();
        boolean gc();
        boolean mbeanTree();
        boolean capabilities();
    }

    @Value.Immutable
    interface ConfigPermissions {
        boolean view();
        EditConfigPermissions edit();
    }

    @Value.Immutable
    interface EditConfigPermissions {
        boolean transaction();
        boolean gauge();
        boolean alert();
        boolean ui();
        boolean plugin();
        boolean instrumentation();
        boolean userRecording();
        boolean advanced();
    }
}
