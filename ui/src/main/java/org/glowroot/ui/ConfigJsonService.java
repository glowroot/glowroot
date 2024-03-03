/*
 * Copyright 2011-2023 the original author or authors.
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

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.*;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Optional;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;
import com.google.common.collect.Ordering;
import com.google.common.collect.Sets;
import com.google.common.io.CharStreams;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.glowroot.common.config.PluginNameComparison;
import org.glowroot.common.live.LiveAggregateRepository;
import org.glowroot.common.util.ObjectMappers;
import org.glowroot.common.util.Styles;
import org.glowroot.common.util.Versions;
import org.glowroot.common2.config.MoreConfigDefaults;
import org.glowroot.common2.repo.CassandraProfile;
import org.glowroot.common2.repo.ConfigRepository;
import org.glowroot.common2.repo.ConfigRepository.OptimisticLockException;
import org.glowroot.common2.repo.GaugeValueRepository;
import org.glowroot.common2.repo.GaugeValueRepository.Gauge;
import org.glowroot.common2.repo.TransactionTypeRepository;
import org.glowroot.ui.GaugeValueJsonService.GaugeOrdering;
import org.glowroot.wire.api.model.AgentConfigOuterClass.AgentConfig;
import org.glowroot.wire.api.model.AgentConfigOuterClass.AgentConfig.*;
import org.glowroot.wire.api.model.AgentConfigOuterClass.AgentConfig.PluginProperty.StringList;
import org.glowroot.wire.api.model.Proto.OptionalInt32;
import org.immutables.value.Value;
import org.owasp.html.HtmlPolicyBuilder;
import org.owasp.html.PolicyFactory;

import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

import static com.google.common.base.Preconditions.checkNotNull;
import static io.netty.handler.codec.http.HttpResponseStatus.PRECONDITION_FAILED;

@JsonService
class ConfigJsonService {

    private static final ObjectMapper mapper = ObjectMappers.create();

    private final TransactionTypeRepository transactionTypeRepository;
    private final GaugeValueRepository gaugeValueRepository;
    private final LiveAggregateRepository liveAggregateRepository;
    private final ConfigRepository configRepository;
    private final SanitizationService sanitizationService;

    ConfigJsonService(TransactionTypeRepository transactionTypeRepository,
            GaugeValueRepository gaugeValueRepository,
            LiveAggregateRepository liveAggregateRepository, ConfigRepository configRepository) {
        this.transactionTypeRepository = transactionTypeRepository;
        this.gaugeValueRepository = gaugeValueRepository;
        this.liveAggregateRepository = liveAggregateRepository;
        this.configRepository = configRepository;
        this.sanitizationService = new SanitizationService();
    }

    @GET(path = "/backend/config/general", permission = "agent:config:view:general")
    String getGeneralConfig(@BindAgentRollupId String agentRollupId) throws Exception {
        GeneralConfig config = configRepository.getGeneralConfig(agentRollupId);
        return mapper.writeValueAsString(GeneralConfigDto.create(config, agentRollupId));
    }

    @GET(path = "/backend/config/transaction", permission = "agent:config:view:transaction")
    String getTransactionConfig(@BindAgentId String agentId) throws Exception {
        TransactionConfig config = configRepository.getTransactionConfig(agentId);
        Set<String> transactionTypes = Sets.newTreeSet();
        transactionTypes.addAll(transactionTypeRepository.read(agentId));
        transactionTypes.addAll(liveAggregateRepository.getTransactionTypes(agentId));
        transactionTypes
                .add(configRepository.getUiDefaultsConfig(agentId).getDefaultTransactionType());
        return mapper.writeValueAsString(ImmutableTransactionConfigResponse.builder()
                .config(TransactionConfigDto.create(config))
                .defaultTransactionType(
                        configRepository.getUiDefaultsConfig(agentId).getDefaultTransactionType())
                .addAllAllTransactionTypes(transactionTypes)
                .build());
    }

    @GET(path = "/backend/config/jvm", permission = "agent:config:view:jvm")
    String getJvmConfig(@BindAgentId String agentId) throws Exception {
        JvmConfig config = configRepository.getJvmConfig(agentId);
        return mapper.writeValueAsString(JvmConfigDto.create(config));
    }

    // central supports ui defaults config on rollups
    @GET(path = "/backend/config/ui-defaults", permission = "agent:config:view:uiDefaults")
    String getUiDefaultsConfig(@BindAgentRollupId String agentRollupId) throws Exception {
        UiDefaultsConfig config = configRepository.getUiDefaultsConfig(agentRollupId);
        Set<String> transactionTypes = Sets.newTreeSet();
        transactionTypes.addAll(transactionTypeRepository.read(agentRollupId));
        transactionTypes.addAll(liveAggregateRepository.getTransactionTypes(agentRollupId));
        List<Gauge> gauges = gaugeValueRepository.getRecentlyActiveGauges(agentRollupId).toCompletableFuture().get();
        ImmutableList<Gauge> sortedGauges = new GaugeOrdering().immutableSortedCopy(gauges);
        return mapper.writeValueAsString(ImmutableUiDefaultsConfigResponse.builder()
                .config(UiDefaultsConfigDto.create(config))
                .addAllAllTransactionTypes(transactionTypes)
                .addAllAllGauges(sortedGauges)
                .build());
    }

    @GET(path = "/backend/config/plugins", permission = "agent:config:view:plugin")
    String getPluginConfig(@BindAgentId String agentId, @BindRequest PluginConfigRequest request)
            throws Exception {
        Optional<String> pluginId = request.pluginId();
        if (pluginId.isPresent()) {
            return getPluginConfigInternal(agentId, request.pluginId().get());
        } else {
            List<PluginResponse> pluginResponses = Lists.newArrayList();
            List<PluginConfig> pluginConfigs = new PluginConfigNameOrdering()
                    .sortedCopy(configRepository.getPluginConfigs(agentId));
            for (PluginConfig pluginConfig : pluginConfigs) {
                pluginResponses.add(ImmutablePluginResponse.builder()
                        .id(pluginConfig.getId())
                        .name(pluginConfig.getName())
                        .hasConfig(pluginConfig.getPropertyCount() > 0)
                        .build());
            }
            return mapper.writeValueAsString(pluginResponses);
        }
    }

    // central supports advanced config on rollups (maxQueryAggregates and maxServiceCallAggregates)
    @GET(path = "/backend/config/advanced", permission = "agent:config:view:advanced")
    String getAdvancedConfig(@BindAgentRollupId String agentRollupId) throws Exception {
        AdvancedConfig config = configRepository.getAdvancedConfig(agentRollupId).toCompletableFuture().get();
        return mapper.writeValueAsString(AdvancedConfigDto.create(config));
    }

    @GET(path = "/backend/config/json", permission = "agent:config:view")
    String getAllConfig(@BindAgentId String agentId) throws Exception {
        AgentConfig config = configRepository.getAllConfig(agentId);
        ObjectNode configRootNode = mapper.valueToTree(AllConfigDto.create(config));
        ObjectMappers.stripEmptyContainerNodes(configRootNode);
        StringBuilder sb = new StringBuilder();
        JsonGenerator jg = mapper.getFactory().createGenerator(CharStreams.asWriter(sb));
        try {
            jg.setPrettyPrinter(ObjectMappers.getPrettyPrinter());
            jg.writeObject(configRootNode);
        } finally {
            jg.close();
        }
        // newline is not required, just a personal preference
        sb.append(ObjectMappers.NEWLINE);
        return sb.toString();
    }

    @POST(path = "/backend/config/general", permission = "agent:config:edit:general")
    String updateGeneralConfig(@BindAgentRollupId String agentRollupId,
            @BindRequest GeneralConfigDto configDto) throws Exception {
        try {
            configRepository.updateGeneralConfig(agentRollupId, configDto.convert(),
                    configDto.version(), CassandraProfile.web);
        } catch (OptimisticLockException e) {
            throw new JsonServiceException(PRECONDITION_FAILED, e);
        }
        return getGeneralConfig(agentRollupId);
    }

    @POST(path = "/backend/config/transaction", permission = "agent:config:edit:transaction")
    String updateTransactionConfig(@BindAgentId String agentId,
            @BindRequest TransactionConfigDto configDto) throws Exception {
        try {
            configRepository.updateTransactionConfig(agentId, configDto.convert(),
                    configDto.version(), CassandraProfile.web);
        } catch (OptimisticLockException e) {
            throw new JsonServiceException(PRECONDITION_FAILED, e);
        }
        return getTransactionConfig(agentId);
    }

    @POST(path = "/backend/config/jvm", permission = "agent:config:edit:jvm")
    String updateJvmConfig(@BindAgentId String agentId, @BindRequest JvmConfigDto configDto)
            throws Exception {
        try {
            configRepository.updateJvmConfig(agentId, configDto.convert(), configDto.version(), CassandraProfile.web);
        } catch (OptimisticLockException e) {
            throw new JsonServiceException(PRECONDITION_FAILED, e);
        }
        return getJvmConfig(agentId);
    }

    // central supports ui defaults config on rollups
    @POST(path = "/backend/config/ui-defaults", permission = "agent:config:edit:uiDefaults")
    String updateUiDefaultsConfig(@BindAgentRollupId String agentRollupId,
            @BindRequest UiDefaultsConfigDto configDto) throws Exception {
        configDto = sanitizationService.sanitize(configDto);
        try {
            configRepository.updateUiDefaultsConfig(agentRollupId, configDto.convert(),
                    configDto.version(), CassandraProfile.web);
        } catch (OptimisticLockException e) {
            throw new JsonServiceException(PRECONDITION_FAILED, e);
        }
        return getUiDefaultsConfig(agentRollupId);
    }

    @POST(path = "/backend/config/plugins", permission = "agent:config:edit:plugins")
    String updatePluginConfig(@BindAgentId String agentId, @BindRequest PluginUpdateRequest request)
            throws Exception {
        String pluginId = request.pluginId();
        if (pluginId.equals("jdbc")) {
            String firstInvalidRegularExpression =
                    getFirstInvalidJdbcPluginRegularExpressions(request.properties());
            if (firstInvalidRegularExpression != null) {
                StringBuilder sb = new StringBuilder();
                JsonGenerator jg = mapper.getFactory().createGenerator(CharStreams.asWriter(sb));
                try {
                    jg.writeStartObject();
                    jg.writeStringField("firstInvalidRegularExpression",
                            firstInvalidRegularExpression);
                    jg.writeEndObject();
                } finally {
                    jg.close();
                }
                return sb.toString();
            }
        }
        PluginConfig.Builder builder = PluginConfig.newBuilder()
                .setId(pluginId);
        for (PluginPropertyDto prop : request.properties()) {
            builder.addProperty(prop.convert());
        }
        try {
            configRepository.updatePluginConfig(agentId, builder.build(), request.version(), CassandraProfile.web);
        } catch (OptimisticLockException e) {
            throw new JsonServiceException(PRECONDITION_FAILED, e);
        }
        return getPluginConfigInternal(agentId, pluginId);
    }

    // central supports advanced config on rollups (maxQueryAggregates and maxServiceCallAggregates)
    @POST(path = "/backend/config/advanced", permission = "agent:config:edit:advanced")
    String updateAdvancedConfig(@BindAgentRollupId String agentRollupId,
            @BindRequest AdvancedConfigDto configDto) throws Exception {
        try {
            configRepository.updateAdvancedConfig(agentRollupId, configDto.convert(),
                    configDto.version(), CassandraProfile.web);
        } catch (OptimisticLockException e) {
            throw new JsonServiceException(PRECONDITION_FAILED, e);
        }
        return getAdvancedConfig(agentRollupId);
    }

    @POST(path = "/backend/config/json", permission = "agent:config:edit")
    String updateAllConfig(@BindAgentId String agentId, @BindRequest AllConfigDto config)
            throws Exception {
        try {
            configRepository.updateAllConfig(agentId, config.toProto(), config.version(), CassandraProfile.web);
        } catch (OptimisticLockException e) {
            throw new JsonServiceException(PRECONDITION_FAILED, e);
        }
        return getAllConfig(agentId);
    }

    private String getPluginConfigInternal(String agentId, String pluginId) throws Exception {
        PluginConfig config = configRepository.getPluginConfig(agentId, pluginId);
        if (config == null) {
            throw new IllegalArgumentException("Plugin id not found: " + pluginId);
        }
        return mapper.writeValueAsString(PluginConfigDto.create(config));
    }

    private static @Nullable String getFirstInvalidJdbcPluginRegularExpressions(
            List<ImmutablePluginPropertyDto> properties) {
        for (PluginPropertyDto property : properties) {
            if (property.name().equals("captureBindParametersIncludes")
                    || property.name().equals("captureBindParametersExcludes")) {
                for (JsonNode value : checkNotNull(property.value())) {
                    String val = checkNotNull(value).textValue();
                    try {
                        Pattern.compile(val);
                    } catch (PatternSyntaxException e) {
                        return val;
                    }
                }
            }
        }
        return null;
    }

    private static OptionalInt32 of(int value) {
        return OptionalInt32.newBuilder().setValue(value).build();
    }

    @Value.Immutable
    interface PluginConfigRequest {
        Optional<String> pluginId();
    }

    @Value.Immutable
    interface PluginResponse {
        String id();
        String name();
        boolean hasConfig();
    }

    // these DTOs are only different from underlying config objects in that they contain the version
    // attribute, and that they have no default attribute values

    @Value.Immutable
    abstract static class GeneralConfigDto {

        abstract String display();
        abstract @Nullable String defaultDisplay(); // only used in response
        abstract String version();

        private GeneralConfig convert() {
            return GeneralConfig.newBuilder()
                    .setDisplay(display())
                    .build();
        }

        private static GeneralConfigDto create(GeneralConfig config, String agentRollupId) {
            return ImmutableGeneralConfigDto.builder()
                    .display(config.getDisplay())
                    .defaultDisplay(
                            MoreConfigDefaults.getDefaultAgentRollupDisplayPart(agentRollupId))
                    .version(Versions.getVersion(config))
                    .build();
        }
    }

    @Value.Immutable
    interface TransactionConfigResponse {
        ImmutableTransactionConfigDto config();
        String defaultTransactionType();
        List<String> allTransactionTypes();
    }

    @Value.Immutable
    abstract static class TransactionConfigDto {

        abstract int slowThresholdMillis();
        abstract int profilingIntervalMillis();
        abstract boolean captureThreadStats();
        abstract List<ImmutableSlowThresholdOverrideDto> slowThresholdOverrides();
        abstract String version();

        private TransactionConfig convert() {
            TransactionConfig.Builder builder = TransactionConfig.newBuilder()
                    .setSlowThresholdMillis(of(slowThresholdMillis()))
                    .setProfilingIntervalMillis(of(profilingIntervalMillis()))
                    .setCaptureThreadStats(captureThreadStats());
            for (SlowThresholdOverrideDto slowThresholdOverride : new SlowThresholdOverrideDtoOrdering()
                    .sortedCopy(slowThresholdOverrides())) {
                builder.addSlowThresholdOverride(slowThresholdOverride.convert());
            }
            return builder.build();
        }

        private static ImmutableTransactionConfigDto create(TransactionConfig config) {
            ImmutableTransactionConfigDto.Builder builder = ImmutableTransactionConfigDto.builder()
                    .slowThresholdMillis(config.getSlowThresholdMillis().getValue())
                    .profilingIntervalMillis(config.getProfilingIntervalMillis().getValue())
                    .captureThreadStats(config.getCaptureThreadStats())
                    .version(Versions.getVersion(config));
            for (SlowThresholdOverride slowThresholdOverride : config
                    .getSlowThresholdOverrideList()) {
                builder.addSlowThresholdOverrides(
                        SlowThresholdOverrideDto.create(slowThresholdOverride));
            }
            return builder.build();
        }
    }

    @Value.Immutable
    @Styles.AllParameters
    abstract static class SlowThresholdOverrideDto {

        abstract String transactionType();
        abstract String transactionName();
        abstract String user();
        abstract int thresholdMillis();

        private SlowThresholdOverride convert() {
            return SlowThresholdOverride.newBuilder()
                    .setTransactionType(transactionType())
                    .setTransactionName(transactionName())
                    .setUser(user())
                    .setThresholdMillis(thresholdMillis())
                    .build();
        }

        private static ImmutableSlowThresholdOverrideDto create(
                SlowThresholdOverride slowThreshold) {
            return ImmutableSlowThresholdOverrideDto.builder()
                    .transactionType(slowThreshold.getTransactionType())
                    .transactionName(slowThreshold.getTransactionName())
                    .user(slowThreshold.getUser())
                    .thresholdMillis(slowThreshold.getThresholdMillis())
                    .build();
        }
    }

    private static class SlowThresholdOverrideDtoOrdering
            extends Ordering<SlowThresholdOverrideDto> {
        @Override
        public int compare(SlowThresholdOverrideDto left, SlowThresholdOverrideDto right) {
            int compare = left.transactionType().compareToIgnoreCase(right.transactionType());
            if (compare != 0) {
                return compare;
            }
            compare = left.transactionName().compareToIgnoreCase(right.transactionName());
            if (compare != 0) {
                return compare;
            }
            compare = left.user().compareToIgnoreCase(right.user());
            if (compare != 0) {
                return compare;
            }
            return left.thresholdMillis() - right.thresholdMillis();
        }
    }

    @Value.Immutable
    abstract static class JvmConfigDto {

        abstract ImmutableList<String> maskSystemProperties();
        abstract ImmutableList<String> maskMBeanAttributes();
        abstract String version();

        private JvmConfig convert() {
            return JvmConfig.newBuilder()
                    .addAllMaskSystemProperty(maskSystemProperties())
                    .addAllMaskMbeanAttribute(maskMBeanAttributes())
                    .build();
        }

        private static JvmConfigDto create(JvmConfig config) {
            return ImmutableJvmConfigDto.builder()
                    .maskSystemProperties(config.getMaskSystemPropertyList())
                    .maskMBeanAttributes(config.getMaskMbeanAttributeList())
                    .version(Versions.getVersion(config))
                    .build();
        }
    }

    @Value.Immutable
    interface PluginUpdateRequest {
        String pluginId();
        List<ImmutablePluginPropertyDto> properties();
        String version();
    }

    // only used in response
    @Value.Immutable
    abstract static class PluginConfigDto {

        abstract String name();
        abstract List<ImmutablePluginPropertyDto> properties();
        abstract String version();

        private static PluginConfigDto create(PluginConfig config) {
            ImmutablePluginConfigDto.Builder builder = ImmutablePluginConfigDto.builder()
                    .name(config.getName());
            for (PluginProperty property : config.getPropertyList()) {
                builder.addProperties(PluginPropertyDto.create(property));
            }
            return builder.version(Versions.getVersion(config))
                    .build();
        }
    }

    // only used in response
    @Value.Immutable
    abstract static class PluginPropertyDto {

        abstract String name();
        abstract PropertyType type();
        abstract @Nullable JsonNode value();
        abstract @Nullable String label();
        abstract @Nullable String checkboxLabel();
        abstract @Nullable String description();

        private PluginProperty convert() {
            return PluginProperty.newBuilder()
                    .setName(name())
                    .setValue(getValue())
                    .build();
        }

        private PluginProperty.Value getValue() {
            JsonNode value = value();
            switch (type()) {
                case BOOLEAN:
                    checkNotNull(value);
                    return PluginProperty.Value.newBuilder().setBval(value.booleanValue()).build();
                case DOUBLE:
                    if (value == null) {
                        return PluginProperty.Value.newBuilder().setDvalNull(true).build();
                    } else {
                        return PluginProperty.Value.newBuilder()
                                .setDval((value.numberValue()).doubleValue()).build();
                    }
                case STRING:
                    checkNotNull(value);
                    return PluginProperty.Value.newBuilder().setSval(value.textValue()).build();
                case LIST:
                    checkNotNull(value);
                    StringList.Builder lval = StringList.newBuilder();
                    for (JsonNode val : value) {
                        lval.addVal(checkNotNull(val).textValue());
                    }
                    return PluginProperty.Value.newBuilder().setLval(lval).build();
                default:
                    throw new IllegalStateException("Unexpected property type: " + type());
            }
        }

        private static ImmutablePluginPropertyDto create(PluginProperty property) {
            return ImmutablePluginPropertyDto.builder()
                    .name(property.getName())
                    .type(getPropertyType(property.getValue().getValCase()))
                    .value(getPropertyValue(property.getValue()))
                    .label(property.getLabel())
                    .checkboxLabel(property.getCheckboxLabel())
                    .description(property.getDescription())
                    .build();
        }

        private static PropertyType getPropertyType(PluginProperty.Value.ValCase valCase) {
            switch (valCase) {
                case BVAL:
                    return PropertyType.BOOLEAN;
                case DVAL_NULL:
                case DVAL:
                    return PropertyType.DOUBLE;
                case SVAL:
                    return PropertyType.STRING;
                case LVAL:
                    return PropertyType.LIST;
                default:
                    throw new IllegalStateException("Unexpected property type: " + valCase);
            }
        }

        private static @Nullable JsonNode getPropertyValue(PluginProperty.Value value) {
            PluginProperty.Value.ValCase valCase = value.getValCase();
            switch (valCase) {
                case BVAL:
                    return BooleanNode.valueOf(value.getBval());
                case DVAL_NULL:
                    return null;
                case DVAL:
                    return DoubleNode.valueOf(value.getDval());
                case SVAL:
                    return TextNode.valueOf(value.getSval());
                case LVAL:
                    ArrayNode arrayNode = mapper.createArrayNode();
                    for (String val : value.getLval().getValList()) {
                        arrayNode.add(val);
                    }
                    return arrayNode;
                default:
                    throw new IllegalStateException("Unexpected property type: " + valCase);
            }
        }
    }

    enum PropertyType {
        BOOLEAN, DOUBLE, STRING, LIST;
    }

    @Value.Immutable
    abstract static class AdvancedConfigDto {

        abstract int immediatePartialStoreThresholdSeconds();
        abstract int maxTransactionAggregates();
        abstract int maxQueryAggregates();
        abstract int maxServiceCallAggregates();
        abstract int maxTraceEntriesPerTransaction();
        abstract int maxProfileSamplesPerTransaction();
        abstract int mbeanGaugeNotFoundDelaySeconds();
        abstract boolean weavingTimer();
        abstract String version();

        private AdvancedConfig convert() {
            return AdvancedConfig.newBuilder()
                    .setImmediatePartialStoreThresholdSeconds(
                            of(immediatePartialStoreThresholdSeconds()))
                    .setMaxTransactionAggregates(of(maxTransactionAggregates()))
                    .setMaxQueryAggregates(of(maxQueryAggregates()))
                    .setMaxServiceCallAggregates(of(maxServiceCallAggregates()))
                    .setMaxTraceEntriesPerTransaction(of(maxTraceEntriesPerTransaction()))
                    .setMaxProfileSamplesPerTransaction(of(maxProfileSamplesPerTransaction()))
                    .setMbeanGaugeNotFoundDelaySeconds(of(mbeanGaugeNotFoundDelaySeconds()))
                    .setWeavingTimer(weavingTimer())
                    .build();
        }

        private static AdvancedConfigDto create(AdvancedConfig config) {
            return ImmutableAdvancedConfigDto.builder()
                    .immediatePartialStoreThresholdSeconds(
                            config.getImmediatePartialStoreThresholdSeconds().getValue())
                    .maxTransactionAggregates(config.getMaxTransactionAggregates().getValue())
                    .maxQueryAggregates(config.getMaxQueryAggregates().getValue())
                    .maxServiceCallAggregates(config.getMaxServiceCallAggregates().getValue())
                    .maxTraceEntriesPerTransaction(
                            config.getMaxTraceEntriesPerTransaction().getValue())
                    .maxProfileSamplesPerTransaction(
                            config.getMaxProfileSamplesPerTransaction().getValue())
                    .mbeanGaugeNotFoundDelaySeconds(
                            config.getMbeanGaugeNotFoundDelaySeconds().getValue())
                    .weavingTimer(config.getWeavingTimer())
                    .version(Versions.getVersion(config))
                    .build();
        }
    }

    @Value.Immutable
    interface UiDefaultsConfigResponse {
        ImmutableUiDefaultsConfigDto config();
        List<String> allTransactionTypes();
        List<Gauge> allGauges();
    }

    @Value.Immutable
    abstract static class UiDefaultsConfigDto {

        abstract String defaultTransactionType();
        abstract List<Double> defaultPercentiles();
        abstract List<String> defaultGaugeNames();
        abstract String version();

        private UiDefaultsConfig convert() throws Exception {
            return UiDefaultsConfig.newBuilder()
                    .setDefaultTransactionType(defaultTransactionType())
                    .addAllDefaultPercentile(
                            Ordering.natural().immutableSortedCopy(defaultPercentiles()))
                    .addAllDefaultGaugeName(defaultGaugeNames())
                    .build();
        }

        private static ImmutableUiDefaultsConfigDto create(UiDefaultsConfig config) {
            return ImmutableUiDefaultsConfigDto.builder()
                    .defaultTransactionType(config.getDefaultTransactionType())
                    .defaultPercentiles(Ordering.natural()
                            .immutableSortedCopy(config.getDefaultPercentileList()))
                    .defaultGaugeNames(config.getDefaultGaugeNameList())
                    .version(Versions.getVersion(config))
                    .build();
        }
    }

    private static class PluginConfigNameOrdering extends Ordering<PluginConfig> {
        @Override
        public int compare(PluginConfig left, PluginConfig right) {
            return PluginNameComparison.compareNames(left.getName(), right.getName());
        }
    }

    @VisibleForTesting
    static class SanitizationService {

        private final PolicyFactory policy;

        public SanitizationService() {
            policy = new HtmlPolicyBuilder().toFactory();
        }

        UiDefaultsConfigDto sanitize(UiDefaultsConfigDto configDto) {
            return ImmutableUiDefaultsConfigDto.builder()
                    .copyFrom(configDto).build()
                    .withDefaultTransactionType(policy.sanitize(configDto.defaultTransactionType()));
        }
    }
}
