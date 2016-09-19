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

import org.glowroot.common.repo.AgentRepository;
import org.glowroot.common.repo.AgentRepository.AgentRollup;
import org.glowroot.common.repo.ConfigRepository;
import org.glowroot.common.repo.ConfigRepository.RollupConfig;
import org.glowroot.common.repo.TransactionTypeRepository;
import org.glowroot.common.util.ObjectMappers;
import org.glowroot.common.util.Versions;
import org.glowroot.ui.HttpSessionManager.Authentication;
import org.glowroot.wire.api.model.AgentConfigOuterClass.AgentConfig.UiConfig;

import static com.google.common.base.Preconditions.checkNotNull;
import static java.util.concurrent.TimeUnit.HOURS;

class LayoutService {

    private static final String AGENT_ID = "";

    private static final ObjectMapper mapper = ObjectMappers.create();

    private final boolean fat;
    private final boolean offlineViewer;
    private final String version;
    private final ConfigRepository configRepository;
    private final AgentRepository agentRepository;
    private final TransactionTypeRepository transactionTypeRepository;

    LayoutService(boolean fat, boolean offlineViewer, String version,
            ConfigRepository configRepository, AgentRepository agentRepository,
            TransactionTypeRepository transactionTypeRepository) {
        this.fat = fat;
        this.offlineViewer = offlineViewer;
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
        Map<String, List<String>> transactionTypesMap = transactionTypeRepository.read();
        boolean hasSomeAccess = false;
        boolean showNavbarTransaction = false;
        boolean showNavbarError = false;
        boolean showNavbarJvm = false;
        boolean showNavbarConfig = false;
        if (fat) {
            Permissions permissions = getPermissions(authentication, AGENT_ID);
            hasSomeAccess =
                    permissions.hasSomeAccess() || authentication.isAdminPermitted("admin:view");
            showNavbarTransaction = permissions.transaction().hasSomeAccess();
            showNavbarError = permissions.error().hasSomeAccess();
            showNavbarJvm = permissions.jvm().hasSomeAccess();
            showNavbarConfig = permissions.config().view();
            if (hasSomeAccess) {
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

                agentRollups.put(AGENT_ID, ImmutableAgentRollupLayout.builder()
                        .leaf(true)
                        .permissions(permissions)
                        .addAllTransactionTypes(transactionTypes)
                        .defaultDisplayedTransactionType(defaultDisplayedTransactionType)
                        .defaultDisplayedPercentiles(uiConfig.getDefaultDisplayedPercentileList())
                        .build());
                showNavbarConfig = checkNotNull(permissions.config()).view();
            }
        } else if (!fat) {
            // "*" is to check permissions for "all agents"
            Permissions permissions = getPermissions(authentication, "*");
            hasSomeAccess =
                    permissions.hasSomeAccess() || authentication.isAdminPermitted("admin:view");
            showNavbarTransaction = permissions.transaction().hasSomeAccess();
            showNavbarError = permissions.error().hasSomeAccess();
            showNavbarJvm = permissions.jvm().hasSomeAccess();
            showNavbarConfig = permissions.config().view();
            for (AgentRollup agentRollup : agentRepository.readAgentRollups()) {
                permissions = getPermissions(authentication, agentRollup.name());
                if (!permissions.hasSomeAccess()) {
                    continue;
                }
                hasSomeAccess = true;
                showNavbarTransaction =
                        showNavbarTransaction || permissions.transaction().hasSomeAccess();
                showNavbarError = showNavbarError || permissions.error().hasSomeAccess();
                showNavbarJvm = showNavbarJvm || permissions.jvm().hasSomeAccess();
                showNavbarConfig = showNavbarConfig || permissions.config().view();
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
                    builder.permissions(permissions);
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
                    .offlineViewer(offlineViewer)
                    .footerMessage("Glowroot version " + version)
                    .loginEnabled(offlineViewer ? false
                            : configRepository.namedUsersExist()
                                    || !configRepository.getLdapConfig().host().isEmpty())
                    .addAllRollupConfigs(configRepository.getRollupConfigs())
                    .addAllRollupExpirationMillis(rollupExpirationMillis)
                    .gaugeCollectionIntervalMillis(
                            configRepository.getGaugeCollectionIntervalMillis())
                    .agentRollups(agentRollups)
                    .showNavbarTransaction(showNavbarTransaction)
                    .showNavbarError(showNavbarError)
                    .showNavbarJvm(showNavbarJvm)
                    .showNavbarConfig(showNavbarConfig)
                    .adminView(authentication.isAdminPermitted("admin:view"))
                    .adminEdit(authentication.isAdminPermitted("admin:edit"))
                    .loggedIn(!authentication.anonymous())
                    .ldap(authentication.ldap())
                    .redirectToLogin(false)
                    .build();
        } else {
            return ImmutableLayout.builder()
                    .fat(fat)
                    .offlineViewer(offlineViewer)
                    .footerMessage("Glowroot version " + version)
                    .loginEnabled(true)
                    .gaugeCollectionIntervalMillis(0)
                    .showNavbarTransaction(false)
                    .showNavbarError(false)
                    .showNavbarJvm(false)
                    .showNavbarConfig(false)
                    .adminView(false)
                    .adminEdit(false)
                    .loggedIn(!authentication.anonymous())
                    .ldap(authentication.ldap())
                    .redirectToLogin(true)
                    .build();
        }
    }

    private static Permissions getPermissions(Authentication authentication, String agentRollup) {
        return ImmutablePermissions.builder()
                .transaction(ImmutableTransactionPermissions.builder()
                        .overview(authentication.isAgentPermitted(agentRollup,
                                "agent:transaction:overview"))
                        .traces(authentication.isAgentPermitted(agentRollup,
                                "agent:transaction:traces"))
                        .queries(authentication.isAgentPermitted(agentRollup,
                                "agent:transaction:queries"))
                        .serviceCalls(authentication.isAgentPermitted(agentRollup,
                                "agent:transaction:serviceCalls"))
                        .profile(authentication.isAgentPermitted(agentRollup,
                                "agent:transaction:profile"))
                        .build())
                .error(ImmutableErrorPermissions.builder()
                        .overview(authentication.isAgentPermitted(agentRollup,
                                "agent:error:overview"))
                        .traces(authentication.isAgentPermitted(agentRollup, "agent:error:traces"))
                        .build())
                .jvm(ImmutableJvmPermissions.builder()
                        .gauges(authentication.isAgentPermitted(agentRollup, "agent:jvm:gauges"))
                        .threadDump(authentication.isAgentPermitted(agentRollup,
                                "agent:jvm:threadDump"))
                        .heapDump(
                                authentication.isAgentPermitted(agentRollup, "agent:jvm:heapDump"))
                        .heapHistogram(authentication.isAgentPermitted(agentRollup,
                                "agent:jvm:heapHistogram"))
                        .gc(authentication.isAgentPermitted(agentRollup, "agent:jvm:gc"))
                        .mbeanTree(
                                authentication.isAgentPermitted(agentRollup, "agent:jvm:mbeanTree"))
                        .systemProperties(authentication.isAgentPermitted(agentRollup,
                                "agent:jvm:systemProperties"))
                        .environment(authentication.isAgentPermitted(agentRollup,
                                "agent:jvm:environment"))
                        .capabilities(authentication.isAgentPermitted(agentRollup,
                                "agent:jvm:capabilities"))
                        .build())
                .config(ImmutableConfigPermissions.builder()
                        .view(authentication.isAgentPermitted(agentRollup, "agent:config:view"))
                        .edit(ImmutableEditConfigPermissions.builder()
                                .transaction(authentication.isAgentPermitted(agentRollup,
                                        "agent:config:edit:transaction"))
                                .gauge(authentication.isAgentPermitted(agentRollup,
                                        "agent:config:edit:gauge"))
                                .alert(authentication.isAgentPermitted(agentRollup,
                                        "agent:config:edit:alert"))
                                .ui(authentication.isAgentPermitted(agentRollup,
                                        "agent:config:edit:ui"))
                                .plugin(authentication.isAgentPermitted(agentRollup,
                                        "agent:config:edit:plugin"))
                                .instrumentation(authentication.isAgentPermitted(agentRollup,
                                        "agent:config:edit:instrumentation"))
                                .advanced(authentication.isAgentPermitted(agentRollup,
                                        "agent:config:edit:advanced"))
                                .userRecording(authentication.isAgentPermitted(agentRollup,
                                        "agent:config:edit:userRecording"))
                                .build())
                        .build())
                .build();
    }

    @Value.Immutable
    abstract static class Layout {

        abstract boolean fat();
        abstract boolean offlineViewer();
        abstract String footerMessage();
        abstract boolean loginEnabled();
        abstract ImmutableList<RollupConfig> rollupConfigs();
        abstract ImmutableList<Long> rollupExpirationMillis();
        abstract long gaugeCollectionIntervalMillis();
        abstract ImmutableMap<String, AgentRollupLayout> agentRollups();
        abstract boolean showNavbarTransaction();
        abstract boolean showNavbarError();
        abstract boolean showNavbarJvm();
        abstract boolean showNavbarConfig();
        abstract boolean adminView();
        abstract boolean adminEdit();
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
    static abstract class Permissions {

        abstract TransactionPermissions transaction();
        abstract ErrorPermissions error();
        abstract JvmPermissions jvm();
        abstract ConfigPermissions config();

        private boolean hasSomeAccess() {
            return transaction().hasSomeAccess() || error().hasSomeAccess() || jvm().hasSomeAccess()
                    || config().view();
        }
    }

    @Value.Immutable
    static abstract class TransactionPermissions {

        abstract boolean overview();
        abstract boolean traces();
        abstract boolean queries();
        abstract boolean serviceCalls();
        abstract boolean profile();

        private boolean hasSomeAccess() {
            return overview() || traces() || queries() || serviceCalls() || profile();
        }
    }

    @Value.Immutable
    static abstract class ErrorPermissions {

        abstract boolean overview();
        abstract boolean traces();

        private boolean hasSomeAccess() {
            return overview() || traces();
        }
    }

    @Value.Immutable
    static abstract class JvmPermissions {

        abstract boolean gauges();
        abstract boolean threadDump();
        abstract boolean heapDump();
        abstract boolean heapHistogram();
        abstract boolean gc();
        abstract boolean mbeanTree();
        abstract boolean systemProperties();
        abstract boolean environment();
        abstract boolean capabilities();

        private boolean hasSomeAccess() {
            // capabilities is not in sidebar, so not included here
            return gauges() || threadDump() || heapDump() || heapHistogram() || gc() || mbeanTree()
                    || systemProperties() || environment();
        }
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
