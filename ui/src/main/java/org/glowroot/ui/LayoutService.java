/*
 * Copyright 2013-2017 the original author or authors.
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

import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TimeZone;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.Ordering;
import com.google.common.collect.Sets;
import org.immutables.value.Value;

import org.glowroot.common.repo.AgentRepository;
import org.glowroot.common.repo.AgentRepository.AgentRollup;
import org.glowroot.common.repo.ConfigRepository;
import org.glowroot.common.repo.ConfigRepository.RollupConfig;
import org.glowroot.common.repo.TraceAttributeNameRepository;
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

    private final boolean central;
    private final boolean offline;
    private final String version;
    private final ConfigRepository configRepository;
    private final AgentRepository agentRepository;
    private final TransactionTypeRepository transactionTypeRepository;
    private final TraceAttributeNameRepository traceAttributeNameRepository;

    LayoutService(boolean central, boolean offline, String version,
            ConfigRepository configRepository, AgentRepository agentRepository,
            TransactionTypeRepository transactionTypeRepository,
            TraceAttributeNameRepository traceAttributeNameRepository) {
        this.central = central;
        this.offline = offline;
        this.version = version;
        this.configRepository = configRepository;
        this.agentRepository = agentRepository;
        this.transactionTypeRepository = transactionTypeRepository;
        this.traceAttributeNameRepository = traceAttributeNameRepository;
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
        if (central) {
            return buildLayoutCentral(authentication);
        } else {
            return buildLayoutEmbedded(authentication);
        }
    }

    private Layout buildLayoutEmbedded(Authentication authentication) throws Exception {
        Permissions permissions = getPermissions(authentication, AGENT_ID, true);
        boolean hasSomeAccess =
                permissions.hasSomeAccess()
                        || authentication.isAdminPermitted("admin:view");
        if (!hasSomeAccess) {
            return createNoAccessLayout(authentication);
        }
        boolean showNavbarTransaction = permissions.transaction().hasSomeAccess();
        boolean showNavbarError = permissions.error().hasSomeAccess();
        boolean showNavbarJvm = permissions.jvm().hasSomeAccess();
        // for now (for simplicity) reporting requires permission for ALL reportable metrics
        // (currently transaction:overview and jvm:gauges)
        boolean showNavbarReport = permissions.transaction().overview()
                && permissions.jvm().gauges();
        boolean showNavbarConfig = permissions.config().view();
        // a couple of special cases for embedded ui
        UiConfig uiConfig = checkNotNull(configRepository.getUiConfig(AGENT_ID));
        String defaultDisplayedTransactionType =
                uiConfig.getDefaultDisplayedTransactionType();
        Set<String> transactionTypes = Sets.newTreeSet(String.CASE_INSENSITIVE_ORDER);
        List<String> storedTransactionTypes = transactionTypeRepository.read().get(AGENT_ID);
        if (storedTransactionTypes != null) {
            transactionTypes.addAll(storedTransactionTypes);
        }
        transactionTypes.add(defaultDisplayedTransactionType);

        Map<String, List<String>> traceAttributeNames =
                traceAttributeNameRepository.read().get(AGENT_ID);
        if (traceAttributeNames == null) {
            traceAttributeNames = ImmutableMap.of();
        }

        Map<String, AgentRollupLayout> agentRollups = Maps.newLinkedHashMap();
        agentRollups.put(AGENT_ID, ImmutableAgentRollupLayout.builder()
                .display(AGENT_ID)
                .depth(0)
                .agent(true)
                .permissions(permissions)
                .addAllTransactionTypes(transactionTypes)
                .putAllTraceAttributeNames(traceAttributeNames)
                .defaultDisplayedTransactionType(defaultDisplayedTransactionType)
                .defaultDisplayedPercentiles(uiConfig.getDefaultDisplayedPercentileList())
                .build());

        return createLayout(authentication, agentRollups, showNavbarTransaction, showNavbarError,
                showNavbarJvm, showNavbarReport, showNavbarConfig);
    }

    private Layout buildLayoutCentral(Authentication authentication) throws Exception {
        List<FilteredAgentRollup> agentRollups =
                filter(agentRepository.readAgentRollups(), authentication);
        CentralLayoutBuilder centralLayoutBuilder = new CentralLayoutBuilder(authentication);
        for (FilteredAgentRollup agentRollup : agentRollups) {
            centralLayoutBuilder.process(agentRollup, 0);
        }
        return centralLayoutBuilder.build(authentication);
    }

    private ImmutableLayout createNoAccessLayout(Authentication authentication) {
        return ImmutableLayout.builder()
                .central(central)
                .offline(offline)
                .footerMessage("Glowroot version " + version)
                .loginEnabled(true)
                .gaugeCollectionIntervalMillis(0)
                .showNavbarTransaction(false)
                .showNavbarError(false)
                .showNavbarJvm(false)
                .showNavbarReport(false)
                .showNavbarConfig(false)
                .adminView(false)
                .adminEdit(false)
                .loggedIn(!authentication.anonymous())
                .ldap(authentication.ldap())
                .redirectToLogin(true)
                .defaultTimeZoneId(TimeZone.getDefault().getID())
                .build();
    }

    private ImmutableLayout createLayout(Authentication authentication,
            Map<String, AgentRollupLayout> agentRollups, boolean showNavbarTransaction,
            boolean showNavbarError, boolean showNavbarJvm, boolean showNavbarReport,
            boolean showNavbarConfig) {
        List<Long> rollupExpirationMillis = Lists.newArrayList();
        for (long hours : configRepository.getStorageConfig().rollupExpirationHours()) {
            rollupExpirationMillis.add(HOURS.toMillis(hours));
        }
        return ImmutableLayout.builder()
                .central(central)
                .offline(offline)
                .footerMessage("Glowroot version " + version)
                .loginEnabled(offline ? false
                        : configRepository.namedUsersExist()
                                || !configRepository.getLdapConfig().host().isEmpty())
                .addAllRollupConfigs(configRepository.getRollupConfigs())
                .addAllRollupExpirationMillis(rollupExpirationMillis)
                .gaugeCollectionIntervalMillis(configRepository.getGaugeCollectionIntervalMillis())
                .agentRollups(agentRollups)
                .showNavbarTransaction(showNavbarTransaction)
                .showNavbarError(showNavbarError)
                .showNavbarJvm(showNavbarJvm)
                .showNavbarReport(showNavbarReport)
                .showNavbarConfig(showNavbarConfig)
                .adminView(authentication.isAdminPermitted("admin:view"))
                .adminEdit(authentication.isAdminPermitted("admin:edit"))
                .loggedIn(!authentication.anonymous())
                .ldap(authentication.ldap())
                .redirectToLogin(false)
                .defaultTimeZoneId(TimeZone.getDefault().getID())
                .addAllTimeZoneIds(Arrays.asList(TimeZone.getAvailableIDs()))
                .build();
    }

    // need to filter out agent rollups with no access rights, and move children up if needed
    private List<FilteredAgentRollup> filter(List<AgentRollup> agentRollups,
            Authentication authentication) {
        List<FilteredAgentRollup> filtered = Lists.newArrayList();
        for (AgentRollup agentRollup : agentRollups) {
            Permissions permissions =
                    getPermissions(authentication, agentRollup.id(), agentRollup.agent());
            if (permissions.hasSomeAccess()) {
                filtered.add(ImmutableFilteredAgentRollup.builder()
                        .id(agentRollup.id())
                        .display(agentRollup.display())
                        .agent(agentRollup.agent())
                        .addAllChildren(filter(agentRollup.children(), authentication))
                        .permissions(permissions)
                        .build());
            } else {
                // move children (if they are accessible themselves) up to this level
                filtered.addAll(filter(agentRollup.children(), authentication));
            }
        }
        // re-sort in case any children were moved up to this level
        return new FilteredAgentRollupOrdering().sortedCopy(filtered);
    }

    private static Permissions getPermissions(Authentication authentication, String agentRollupId,
            boolean agent) {
        return ImmutablePermissions.builder()
                .transaction(ImmutableTransactionPermissions.builder()
                        .overview(authentication.isAgentPermitted(agentRollupId,
                                "agent:transaction:overview"))
                        .traces(authentication.isAgentPermitted(agentRollupId,
                                "agent:transaction:traces"))
                        .queries(authentication.isAgentPermitted(agentRollupId,
                                "agent:transaction:queries"))
                        .serviceCalls(authentication.isAgentPermitted(agentRollupId,
                                "agent:transaction:serviceCalls"))
                        .profile(authentication.isAgentPermitted(agentRollupId,
                                "agent:transaction:profile"))
                        .build())
                .error(ImmutableErrorPermissions.builder()
                        .overview(authentication.isAgentPermitted(agentRollupId,
                                "agent:error:overview"))
                        .traces(authentication.isAgentPermitted(agentRollupId,
                                "agent:error:traces"))
                        .build())
                .jvm(ImmutableJvmPermissions.builder()
                        .gauges(authentication.isAgentPermitted(agentRollupId, "agent:jvm:gauges"))
                        .threadDump(agent && authentication.isAgentPermitted(agentRollupId,
                                "agent:jvm:threadDump"))
                        .heapDump(agent && authentication.isAgentPermitted(agentRollupId,
                                "agent:jvm:heapDump"))
                        .heapHistogram(agent && authentication.isAgentPermitted(agentRollupId,
                                "agent:jvm:heapHistogram"))
                        .gc(agent && authentication.isAgentPermitted(agentRollupId, "agent:jvm:gc"))
                        .mbeanTree(agent && authentication.isAgentPermitted(agentRollupId,
                                "agent:jvm:mbeanTree"))
                        .systemProperties(agent && authentication.isAgentPermitted(agentRollupId,
                                "agent:jvm:systemProperties"))
                        .environment(agent && authentication.isAgentPermitted(agentRollupId,
                                "agent:jvm:environment"))
                        .capabilities(agent && authentication.isAgentPermitted(agentRollupId,
                                "agent:jvm:capabilities"))
                        .build())
                .config(ImmutableConfigPermissions.builder()
                        .view(agent && authentication.isAgentPermitted(agentRollupId,
                                "agent:config:view"))
                        .edit(ImmutableEditConfigPermissions.builder()
                                .transaction(agent && authentication.isAgentPermitted(agentRollupId,
                                        "agent:config:edit:transaction"))
                                .gauge(agent && authentication.isAgentPermitted(agentRollupId,
                                        "agent:config:edit:gauge"))
                                .alert(agent && authentication.isAgentPermitted(agentRollupId,
                                        "agent:config:edit:alert"))
                                .ui(agent && authentication.isAgentPermitted(agentRollupId,
                                        "agent:config:edit:ui"))
                                .plugin(agent && authentication.isAgentPermitted(agentRollupId,
                                        "agent:config:edit:plugin"))
                                .instrumentation(agent && authentication.isAgentPermitted(
                                        agentRollupId, "agent:config:edit:instrumentation"))
                                .advanced(agent && authentication.isAgentPermitted(agentRollupId,
                                        "agent:config:edit:advanced"))
                                .userRecording(
                                        agent && authentication.isAgentPermitted(agentRollupId,
                                                "agent:config:edit:userRecording"))
                                .build())
                        .build())
                .build();
    }

    private class CentralLayoutBuilder {

        // linked hash map to preserve ordering
        private final Map<String, AgentRollupLayout> agentRollups = Maps.newLinkedHashMap();
        private final Map<String, List<String>> transactionTypesMap;
        private final Map<String, Map<String, List<String>>> traceAttributeNamesMap;

        private boolean hasSomeAccess = false;
        private boolean showNavbarTransaction = false;
        private boolean showNavbarError = false;
        private boolean showNavbarJvm = false;
        private boolean showNavbarReport = false;
        private boolean showNavbarConfig = false;

        private CentralLayoutBuilder(Authentication authentication) throws Exception {
            transactionTypesMap = transactionTypeRepository.read();
            traceAttributeNamesMap = traceAttributeNameRepository.read();
            // "*" is to check permissions for "all agents"
            Permissions permissions = getPermissions(authentication, "*", true);
            hasSomeAccess =
                    permissions.hasSomeAccess()
                            || authentication.isAdminPermitted("admin:view");
            showNavbarTransaction = permissions.transaction().hasSomeAccess();
            showNavbarError = permissions.error().hasSomeAccess();
            showNavbarJvm = permissions.jvm().hasSomeAccess();
            // for now (for simplicity) reporting requires permission for ALL reportable metrics
            // (currently transaction:overview and jvm:gauges)
            showNavbarReport = permissions.transaction().overview() && permissions.jvm().gauges();
            showNavbarConfig = permissions.config().view();
        }

        private void process(FilteredAgentRollup agentRollup, int depth) throws IOException {
            Permissions permissions = agentRollup.permissions();
            hasSomeAccess = true;
            showNavbarTransaction =
                    showNavbarTransaction || permissions.transaction().hasSomeAccess();
            showNavbarError = showNavbarError || permissions.error().hasSomeAccess();
            showNavbarJvm = showNavbarJvm || permissions.jvm().hasSomeAccess();
            // for now (for simplicity) reporting requires permission for ALL reportable metrics
            // (currently transaction:overview and jvm:gauges)
            showNavbarReport = showNavbarReport || (permissions.transaction().overview()
                    && permissions.jvm().gauges());
            showNavbarConfig = showNavbarConfig || permissions.config().view();
            UiConfig uiConfig = configRepository.getUiConfig(agentRollup.id());
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
            Set<String> transactionTypes = Sets.newTreeSet(String.CASE_INSENSITIVE_ORDER);
            List<String> storedTransactionTypes = transactionTypesMap.get(agentRollup.id());
            if (storedTransactionTypes != null) {
                transactionTypes.addAll(storedTransactionTypes);
            }
            transactionTypes.add(defaultDisplayedTransactionType);
            Map<String, List<String>> traceAttributeNames =
                    traceAttributeNamesMap.get(agentRollup.id());
            if (traceAttributeNames == null) {
                traceAttributeNames = ImmutableMap.of();
            }
            agentRollups.put(agentRollup.id(),
                    ImmutableAgentRollupLayout.builder()
                            .display(agentRollup.display())
                            .depth(depth)
                            .agent(agentRollup.agent())
                            .permissions(permissions)
                            .addAllTransactionTypes(transactionTypes)
                            .putAllTraceAttributeNames(traceAttributeNames)
                            .defaultDisplayedTransactionType(defaultDisplayedTransactionType)
                            .defaultDisplayedPercentiles(defaultDisplayedPercentiles)
                            .build());
            for (FilteredAgentRollup childAgentRollup : agentRollup.children()) {
                process(childAgentRollup, depth + 1);
            }
        }

        private ImmutableLayout build(Authentication authentication) {
            if (hasSomeAccess) {
                return createLayout(authentication, agentRollups, showNavbarTransaction,
                        showNavbarError, showNavbarJvm, showNavbarReport, showNavbarConfig);
            } else {
                return createNoAccessLayout(authentication);
            }
        }
    }

    @Value.Immutable
    interface FilteredAgentRollup {
        String id();
        String display();
        boolean agent();
        Permissions permissions();
        List<FilteredAgentRollup> children();
    }

    @Value.Immutable
    abstract static class Layout {

        abstract boolean central();
        abstract boolean offline();
        abstract String footerMessage();
        abstract boolean loginEnabled();
        abstract ImmutableList<RollupConfig> rollupConfigs();
        abstract ImmutableList<Long> rollupExpirationMillis();
        abstract long gaugeCollectionIntervalMillis();
        abstract ImmutableMap<String, AgentRollupLayout> agentRollups();
        abstract boolean showNavbarTransaction();
        abstract boolean showNavbarError();
        abstract boolean showNavbarJvm();
        abstract boolean showNavbarReport();
        abstract boolean showNavbarConfig();
        abstract boolean adminView();
        abstract boolean adminEdit();
        abstract boolean loggedIn();
        abstract boolean ldap();
        abstract boolean redirectToLogin();
        abstract String defaultTimeZoneId();
        abstract List<String> timeZoneIds();

        @Value.Derived
        public String version() {
            return Versions.getJsonVersion(this);
        }
    }

    @Value.Immutable
    interface AgentRollupLayout {
        String display();
        int depth();
        boolean agent();
        Permissions permissions();
        List<String> transactionTypes();
        Map<String, List<String>> traceAttributeNames(); // key is transaction type
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

    private static class FilteredAgentRollupOrdering extends Ordering<FilteredAgentRollup> {
        @Override
        public int compare(FilteredAgentRollup left, FilteredAgentRollup right) {
            return left.display().compareToIgnoreCase(right.display());
        }
    }
}
