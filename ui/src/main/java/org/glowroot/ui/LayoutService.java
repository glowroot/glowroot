/*
 * Copyright 2013-2018 the original author or authors.
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
import java.util.TimeZone;

import javax.annotation.Nullable;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.base.Joiner;
import com.google.common.base.Strings;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Lists;
import com.google.common.collect.Sets;
import org.immutables.value.Value;

import org.glowroot.common.config.ConfigDefaults;
import org.glowroot.common.repo.AgentRollupRepository;
import org.glowroot.common.repo.ConfigRepository;
import org.glowroot.common.repo.ConfigRepository.AgentConfigNotFoundException;
import org.glowroot.common.repo.ConfigRepository.RollupConfig;
import org.glowroot.common.repo.TraceAttributeNameRepository;
import org.glowroot.common.repo.TransactionTypeRepository;
import org.glowroot.common.util.ObjectMappers;
import org.glowroot.common.util.Versions;
import org.glowroot.ui.HttpSessionManager.Authentication;
import org.glowroot.wire.api.model.AgentConfigOuterClass.AgentConfig.UiConfig;

import static java.util.concurrent.TimeUnit.HOURS;

class LayoutService {

    private static final String AGENT_ID = "";

    private static final ObjectMapper mapper = ObjectMappers.create();

    private final boolean central;
    private final boolean offline;
    private final String version;
    private final ConfigRepository configRepository;
    private final TransactionTypeRepository transactionTypeRepository;
    private final TraceAttributeNameRepository traceAttributeNameRepository;
    private final AgentRollupRepository agentRollupRepository;

    LayoutService(boolean central, boolean offline, String version,
            ConfigRepository configRepository, TransactionTypeRepository transactionTypeRepository,
            TraceAttributeNameRepository traceAttributeNameRepository,
            AgentRollupRepository agentRollupRepository) {
        this.central = central;
        this.offline = offline;
        this.version = version;
        this.configRepository = configRepository;
        this.transactionTypeRepository = transactionTypeRepository;
        this.traceAttributeNameRepository = traceAttributeNameRepository;
        this.agentRollupRepository = agentRollupRepository;
    }

    String getLayoutJson(Authentication authentication) throws Exception {
        Layout layout = buildLayout(authentication);
        return mapper.writeValueAsString(layout);
    }

    String getLayoutVersion(Authentication authentication) throws Exception {
        Layout layout = buildLayout(authentication);
        return layout.version();
    }

    String getAgentRollupLayoutJson(String agentRollupId, Authentication authentication)
            throws Exception {
        AgentRollupLayout agentRollupLayout = buildAgentRollupLayout(authentication, agentRollupId);
        return mapper.writeValueAsString(agentRollupLayout);
    }

    @Nullable
    String getAgentRollupLayoutVersion(Authentication authentication, String agentRollupId)
            throws Exception {
        AgentRollupLayout agentRollupLayout = buildAgentRollupLayout(authentication, agentRollupId);
        if (agentRollupLayout == null) {
            return null;
        }
        return agentRollupLayout.version();
    }

    @Nullable
    AgentRollupLayout buildAgentRollupLayout(Authentication authentication, String agentRollupId)
            throws Exception {
        Permissions permissions =
                LayoutService.getPermissions(authentication, agentRollupId);
        List<String> agentRollupDisplayParts =
                agentRollupRepository.readAgentRollupDisplayParts(agentRollupId);
        FilteredAgentRollup agentRollup = ImmutableFilteredAgentRollup.builder()
                .id(agentRollupId)
                .display(Joiner.on(" :: ").join(agentRollupDisplayParts))
                .lastDisplayPart(agentRollupDisplayParts.get(agentRollupDisplayParts.size() - 1))
                .permissions(permissions)
                .build();
        return buildAgentRollupLayout(agentRollup, transactionTypeRepository.read(),
                traceAttributeNameRepository.read());
    }

    private @Nullable AgentRollupLayout buildAgentRollupLayout(FilteredAgentRollup agentRollup,
            Map<String, List<String>> transactionTypesMap,
            Map<String, Map<String, List<String>>> traceAttributeNamesMap) throws Exception {
        UiConfig uiConfig;
        try {
            uiConfig = configRepository.getUiConfig(agentRollup.id());
        } catch (AgentConfigNotFoundException e) {
            uiConfig = UiConfig.newBuilder()
                    .setDefaultTransactionType(ConfigDefaults.UI_DEFAULT_TRANSACTION_TYPE)
                    .addAllDefaultPercentile(ConfigDefaults.UI_DEFAULT_PERCENTILES)
                    .addAllDefaultGaugeName(ConfigDefaults.UI_DEFAULT_GAUGE_NAMES)
                    .build();
        }
        Permissions permissions = agentRollup.permissions();
        String defaultTransactionType = uiConfig.getDefaultTransactionType();
        Set<String> transactionTypes = Sets.newTreeSet();
        List<String> storedTransactionTypes = transactionTypesMap.get(agentRollup.id());
        if (storedTransactionTypes != null) {
            transactionTypes.addAll(storedTransactionTypes);
        }
        transactionTypes.add(defaultTransactionType);
        Map<String, List<String>> traceAttributeNames =
                traceAttributeNamesMap.get(agentRollup.id());
        if (traceAttributeNames == null) {
            traceAttributeNames = ImmutableMap.of();
        }
        return ImmutableAgentRollupLayout.builder()
                .id(agentRollup.id())
                .display(agentRollup.display())
                .permissions(permissions)
                .addAllTransactionTypes(transactionTypes)
                .putAllTraceAttributeNames(traceAttributeNames)
                .defaultTransactionType(defaultTransactionType)
                .defaultPercentiles(uiConfig.getDefaultPercentileList())
                .defaultGaugeNames(uiConfig.getDefaultGaugeNameList())
                .build();
    }

    private Layout buildLayout(Authentication authentication) throws Exception {
        if (central) {
            return buildLayoutCentral(authentication);
        } else {
            return buildLayoutEmbedded(authentication);
        }
    }

    private Layout buildLayoutEmbedded(Authentication authentication) throws Exception {
        Permissions permissions = getPermissions(authentication, AGENT_ID);
        boolean hasSomeAccess =
                permissions.hasSomeAccess() || authentication.isAdminPermitted("admin:view");
        if (!hasSomeAccess) {
            return createNoAccessLayout(authentication);
        }
        boolean showNavbarTransaction = permissions.transaction().hasSomeAccess();
        boolean showNavbarError = permissions.error().hasSomeAccess();
        boolean showNavbarJvm = permissions.jvm().hasSomeAccess();
        boolean showNavbarIncident =
                permissions.incident() && !configRepository.getAlertConfigs(AGENT_ID).isEmpty();
        // for now (for simplicity) reporting requires permission for ALL reportable metrics
        // (currently transaction:overview, error:overview and jvm:gauges)
        boolean showNavbarReport = permissions.transaction().overview()
                && permissions.error().overview() && permissions.jvm().gauges();
        boolean showNavbarConfig = permissions.config().view();
        // a couple of special cases for embedded ui
        UiConfig uiConfig = configRepository.getUiConfig(AGENT_ID);
        String defaultTransactionType = uiConfig.getDefaultTransactionType();
        Set<String> transactionTypes = Sets.newTreeSet();
        List<String> storedTransactionTypes = transactionTypeRepository.read().get(AGENT_ID);
        if (storedTransactionTypes != null) {
            transactionTypes.addAll(storedTransactionTypes);
        }
        transactionTypes.add(defaultTransactionType);

        Map<String, List<String>> traceAttributeNames =
                traceAttributeNameRepository.read().get(AGENT_ID);
        if (traceAttributeNames == null) {
            traceAttributeNames = ImmutableMap.of();
        }

        AgentRollupLayout embeddedAgentRollup = ImmutableAgentRollupLayout.builder()
                .id(AGENT_ID)
                .display(AGENT_ID)
                .permissions(permissions)
                .addAllTransactionTypes(transactionTypes)
                .putAllTraceAttributeNames(traceAttributeNames)
                .defaultTransactionType(defaultTransactionType)
                .defaultPercentiles(uiConfig.getDefaultPercentileList())
                .defaultGaugeNames(uiConfig.getDefaultGaugeNameList())
                .build();

        return createLayout(authentication, showNavbarTransaction, showNavbarError,
                showNavbarJvm, false, showNavbarIncident, showNavbarReport, showNavbarConfig,
                embeddedAgentRollup);
    }

    private Layout buildLayoutCentral(Authentication authentication) throws Exception {
        boolean showNavbarTransaction =
                authentication.hasAnyPermissionImpliedBy("agent:transaction");
        boolean showNavbarError = authentication.hasAnyPermissionImpliedBy("agent:error");
        boolean showNavbarJvm = authentication.hasAnyPermissionImpliedBy("agent:jvm");
        boolean showNavbarSyntheticMonitor =
                authentication.hasAnyPermissionImpliedBy("agent:syntheticMonitor");
        boolean showNavbarIncident = authentication.hasAnyPermissionImpliedBy("agent:incident");
        // for now (for simplicity) reporting requires permission for ALL reportable metrics
        // (currently transaction:overview, error:overview and jvm:gauges)
        boolean showNavbarReport =
                authentication.isPermittedForSomeAgentRollup("agent:transaction:overview")
                        && authentication.isPermittedForSomeAgentRollup("agent:error:overview")
                        && authentication.isPermittedForSomeAgentRollup("agent:jvm:gauges");
        boolean showNavbarConfig = authentication.hasAnyPermissionImpliedBy("agent:config");
        if (!showNavbarTransaction && !showNavbarError && !showNavbarJvm && !showNavbarIncident
                && !showNavbarReport && !authentication.isAdminPermitted("admin:view")) {
            return createNoAccessLayout(authentication);
        }
        return createLayout(authentication, showNavbarTransaction, showNavbarError, showNavbarJvm,
                showNavbarSyntheticMonitor, showNavbarIncident, showNavbarReport, showNavbarConfig,
                null);
    }

    private ImmutableLayout createNoAccessLayout(Authentication authentication) {
        return ImmutableLayout.builder()
                .central(central)
                .offline(offline)
                .glowrootVersion(version)
                .loginEnabled(true)
                .gaugeCollectionIntervalMillis(0)
                .showNavbarTransaction(false)
                .showNavbarError(false)
                .showNavbarJvm(false)
                .showNavbarSyntheticMonitor(false)
                .showNavbarIncident(false)
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
            boolean showNavbarTransaction, boolean showNavbarError, boolean showNavbarJvm,
            boolean showNavbarSyntheticMonitor, boolean showNavbarIncident,
            boolean showNavbarReport, boolean showNavbarConfig,
            @Nullable AgentRollupLayout embeddedAgentRollup) throws Exception {
        List<Long> rollupExpirationMillis = Lists.newArrayList();
        for (long hours : configRepository.getStorageConfig().rollupExpirationHours()) {
            rollupExpirationMillis.add(HOURS.toMillis(hours));
        }
        return ImmutableLayout.builder()
                .central(central)
                .offline(offline)
                .glowrootVersion(version)
                .loginEnabled(offline ? false
                        : configRepository.namedUsersExist()
                                || !configRepository.getLdapConfig().host().isEmpty())
                .addAllRollupConfigs(configRepository.getRollupConfigs())
                .addAllRollupExpirationMillis(rollupExpirationMillis)
                .gaugeCollectionIntervalMillis(configRepository.getGaugeCollectionIntervalMillis())
                .showNavbarTransaction(showNavbarTransaction)
                .showNavbarError(showNavbarError)
                .showNavbarJvm(showNavbarJvm)
                .showNavbarSyntheticMonitor(showNavbarSyntheticMonitor)
                .showNavbarIncident(showNavbarIncident)
                .showNavbarReport(showNavbarReport)
                .showNavbarConfig(showNavbarConfig)
                .adminView(authentication.isAdminPermitted("admin:view"))
                .adminEdit(authentication.isAdminPermitted("admin:edit"))
                .loggedIn(!authentication.anonymous())
                .ldap(authentication.ldap())
                .redirectToLogin(false)
                .defaultTimeZoneId(TimeZone.getDefault().getID())
                .addAllTimeZoneIds(getAllTimeZoneIds())
                .embeddedAgentRollup(embeddedAgentRollup)
                .build();
    }

    private @Nullable String getEmbeddedAgentDisplayName() {
        if (central) {
            return null;
        } else {
            return Strings.emptyToNull(
                    configRepository.getEmbeddedAdminGeneralConfig().agentDisplayNameOrDefault());
        }
    }

    static Permissions getPermissions(Authentication authentication, String agentRollupId)
            throws Exception {
        return ImmutablePermissions.builder()
                .transaction(ImmutableTransactionPermissions.builder()
                        .overview(authentication.isPermittedForAgentRollup(agentRollupId,
                                "agent:transaction:overview"))
                        .traces(authentication.isPermittedForAgentRollup(agentRollupId,
                                "agent:transaction:traces"))
                        .queries(authentication.isPermittedForAgentRollup(agentRollupId,
                                "agent:transaction:queries"))
                        .serviceCalls(authentication.isPermittedForAgentRollup(agentRollupId,
                                "agent:transaction:serviceCalls"))
                        .profile(authentication.isPermittedForAgentRollup(agentRollupId,
                                "agent:transaction:profile"))
                        .build())
                .error(ImmutableErrorPermissions.builder()
                        .overview(authentication.isPermittedForAgentRollup(agentRollupId,
                                "agent:error:overview"))
                        .traces(authentication.isPermittedForAgentRollup(agentRollupId,
                                "agent:error:traces"))
                        .build())
                .jvm(ImmutableJvmPermissions.builder()
                        .gauges(authentication.isPermittedForAgentRollup(agentRollupId,
                                "agent:jvm:gauges"))
                        .threadDump(authentication.isPermittedForAgentRollup(agentRollupId,
                                "agent:jvm:threadDump"))
                        .heapDump(authentication.isPermittedForAgentRollup(agentRollupId,
                                "agent:jvm:heapDump"))
                        .heapHistogram(authentication.isPermittedForAgentRollup(agentRollupId,
                                "agent:jvm:heapHistogram"))
                        .gc(authentication.isPermittedForAgentRollup(agentRollupId, "agent:jvm:gc"))
                        .mbeanTree(authentication.isPermittedForAgentRollup(agentRollupId,
                                "agent:jvm:mbeanTree"))
                        .systemProperties(authentication.isPermittedForAgentRollup(agentRollupId,
                                "agent:jvm:systemProperties"))
                        .environment(authentication.isPermittedForAgentRollup(agentRollupId,
                                "agent:jvm:environment"))
                        .capabilities(authentication.isPermittedForAgentRollup(agentRollupId,
                                "agent:jvm:capabilities"))
                        .build())
                .syntheticMonitor(authentication.isPermittedForAgentRollup(agentRollupId,
                        "agent:syntheticMonitor"))
                .incident(authentication.isPermittedForAgentRollup(agentRollupId, "agent:incident"))
                .config(ImmutableConfigPermissions.builder()
                        // central supports alert configs and ui config on rollups
                        .view(authentication.isPermittedForAgentRollup(agentRollupId,
                                "agent:config:view"))
                        .edit(ImmutableEditConfigPermissions.builder()
                                .general(authentication.isPermittedForAgentRollup(agentRollupId,
                                        "agent:config:edit:general"))
                                .transaction(authentication.isPermittedForAgentRollup(agentRollupId,
                                        "agent:config:edit:transaction"))
                                .gauge(authentication.isPermittedForAgentRollup(agentRollupId,
                                        "agent:config:edit:gauge"))
                                .jvm(authentication.isPermittedForAgentRollup(agentRollupId,
                                        "agent:config:edit:jvm"))
                                // central supports synthetic monitor configs on rollups
                                .syntheticMonitor(authentication.isPermittedForAgentRollup(
                                        agentRollupId, "agent:config:edit:syntheticMonitor"))
                                // central supports alert configs on rollups
                                .alert(authentication.isPermittedForAgentRollup(agentRollupId,
                                        "agent:config:edit:alert"))
                                // central supports ui config on rollups
                                .ui(authentication.isPermittedForAgentRollup(agentRollupId,
                                        "agent:config:edit:ui"))
                                .plugin(authentication.isPermittedForAgentRollup(agentRollupId,
                                        "agent:config:edit:plugin"))
                                .instrumentation(authentication.isPermittedForAgentRollup(
                                        agentRollupId, "agent:config:edit:instrumentation"))
                                // central supports advanced config on rollups
                                // (maxAggregateQueriesPerType and maxAggregateServiceCallsPerType)
                                .advanced(authentication.isPermittedForAgentRollup(agentRollupId,
                                        "agent:config:edit:advanced"))
                                .userRecording(authentication.isPermittedForAgentRollup(
                                        agentRollupId, "agent:config:edit:userRecording"))
                                .build())
                        .build())
                .build();
    }

    private static List<String> getAllTimeZoneIds() {
        List<String> allTimeZoneIds = Lists.newArrayList();
        // remove administrative zones which are just asking for confusion (e.g. Etc/GMT+8 is
        // 8 hours _behind_ GMT, see https://en.wikipedia.org/wiki/Tz_database#Area)
        for (String timeZoneId : TimeZone.getAvailableIDs()) {
            if (!timeZoneId.startsWith("Etc/")) {
                allTimeZoneIds.add(timeZoneId);
            }
        }
        return allTimeZoneIds;
    }

    @Value.Immutable
    interface FilteredAgentRollup {
        String id();
        String display();
        String lastDisplayPart();
        Permissions permissions();
        List<FilteredAgentRollup> children();
    }

    @Value.Immutable
    abstract static class Layout {

        abstract boolean central();
        abstract boolean offline();
        abstract String glowrootVersion();
        abstract boolean loginEnabled();
        abstract ImmutableList<RollupConfig> rollupConfigs();
        abstract ImmutableList<Long> rollupExpirationMillis();
        abstract long gaugeCollectionIntervalMillis();
        abstract boolean showNavbarTransaction();
        abstract boolean showNavbarError();
        abstract boolean showNavbarJvm();
        abstract boolean showNavbarSyntheticMonitor();
        abstract boolean showNavbarIncident();
        abstract boolean showNavbarReport();
        abstract boolean showNavbarConfig();
        abstract boolean adminView();
        abstract boolean adminEdit();
        abstract boolean loggedIn();
        abstract boolean ldap();
        abstract boolean redirectToLogin();
        abstract String defaultTimeZoneId();
        abstract List<String> timeZoneIds();
        abstract @Nullable AgentRollupLayout embeddedAgentRollup();

        @Value.Derived
        public String version() {
            return Versions.getJsonVersion(this);
        }
    }

    @Value.Immutable
    abstract static class AgentRollupLayout {
        abstract String id();
        abstract String display();
        abstract Permissions permissions();
        abstract List<String> transactionTypes();
        abstract Map<String, List<String>> traceAttributeNames(); // key is transaction type
        abstract String defaultTransactionType();
        abstract List<Double> defaultPercentiles();
        abstract List<String> defaultGaugeNames();

        @Value.Derived
        public String version() {
            return Versions.getJsonVersion(this);
        }
    }

    @Value.Immutable
    abstract static class Permissions {

        abstract TransactionPermissions transaction();
        abstract ErrorPermissions error();
        abstract JvmPermissions jvm();
        abstract boolean syntheticMonitor();
        abstract boolean incident();
        abstract ConfigPermissions config();

        boolean hasSomeAccess() {
            return transaction().hasSomeAccess() || error().hasSomeAccess() || jvm().hasSomeAccess()
                    || config().view();
        }
    }

    @Value.Immutable
    abstract static class TransactionPermissions {

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
    abstract static class ErrorPermissions {

        abstract boolean overview();
        abstract boolean traces();

        private boolean hasSomeAccess() {
            return overview() || traces();
        }
    }

    @Value.Immutable
    abstract static class JvmPermissions {

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
        boolean general();
        boolean transaction();
        boolean gauge();
        boolean jvm();
        boolean syntheticMonitor();
        boolean alert();
        boolean ui();
        boolean plugin();
        boolean instrumentation();
        boolean userRecording();
        boolean advanced();
    }
}
