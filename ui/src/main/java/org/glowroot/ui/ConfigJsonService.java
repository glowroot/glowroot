/*
 * Copyright 2011-2017 the original author or authors.
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
import java.util.List;

import javax.annotation.Nullable;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.base.Optional;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;
import com.google.common.collect.Ordering;
import org.immutables.value.Value;

import org.glowroot.common.repo.ConfigRepository;
import org.glowroot.common.repo.ConfigRepository.OptimisticLockException;
import org.glowroot.common.util.ObjectMappers;
import org.glowroot.common.util.Versions;
import org.glowroot.wire.api.model.AgentConfigOuterClass.AgentConfig.AdvancedConfig;
import org.glowroot.wire.api.model.AgentConfigOuterClass.AgentConfig.PluginConfig;
import org.glowroot.wire.api.model.AgentConfigOuterClass.AgentConfig.PluginProperty;
import org.glowroot.wire.api.model.AgentConfigOuterClass.AgentConfig.TransactionConfig;
import org.glowroot.wire.api.model.AgentConfigOuterClass.AgentConfig.UiConfig;
import org.glowroot.wire.api.model.AgentConfigOuterClass.AgentConfig.UserRecordingConfig;
import org.glowroot.wire.api.model.Proto.OptionalInt32;

import static com.google.common.base.Preconditions.checkNotNull;
import static io.netty.handler.codec.http.HttpResponseStatus.PRECONDITION_FAILED;

@JsonService
class ConfigJsonService {

    private static final ObjectMapper mapper = ObjectMappers.create();

    private final ConfigRepository configRepository;

    ConfigJsonService(ConfigRepository configRepository) {
        this.configRepository = configRepository;
    }

    @GET(path = "/backend/config/transaction", permission = "agent:config:view:transaction")
    String getTransactionConfig(@BindAgentId String agentId) throws Exception {
        TransactionConfig config = configRepository.getTransactionConfig(agentId);
        if (config == null) {
            return "{}";
        }
        return mapper.writeValueAsString(TransactionConfigDto.create(config));
    }

    @GET(path = "/backend/config/ui", permission = "agent:config:view:ui")
    String getUiConfig(@BindAgentId String agentId) throws Exception {
        UiConfig config = configRepository.getUiConfig(agentId);
        if (config == null) {
            return "{}";
        }
        return mapper.writeValueAsString(UiConfigDto.create(config));
    }

    @GET(path = "/backend/config/plugins", permission = "agent:config:view:plugin")
    String getPluginConfig(@BindAgentId String agentId, @BindRequest PluginConfigRequest request)
            throws Exception {
        Optional<String> pluginId = request.pluginId();
        if (pluginId.isPresent()) {
            return getPluginConfigInternal(agentId, request.pluginId().get());
        } else {
            List<PluginResponse> pluginResponses = Lists.newArrayList();
            List<PluginConfig> pluginConfigs = configRepository.getPluginConfigs(agentId);
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

    @GET(path = "/backend/config/user-recording", permission = "agent:config:view:userRecording")
    String getUserRecordingConfig(@BindAgentId String agentId) throws Exception {
        UserRecordingConfig config = configRepository.getUserRecordingConfig(agentId);
        if (config == null) {
            return "{}";
        }
        return mapper.writeValueAsString(UserRecordingConfigDto.create(config));
    }

    @GET(path = "/backend/config/advanced", permission = "agent:config:view:advanced")
    String getAdvancedConfig(@BindAgentId String agentId) throws Exception {
        AdvancedConfig config = configRepository.getAdvancedConfig(agentId);
        if (config == null) {
            return "{}";
        }
        return mapper.writeValueAsString(AdvancedConfigDto.create(config));
    }

    @POST(path = "/backend/config/transaction", permission = "agent:config:edit:transaction")
    String updateTransactionConfig(@BindAgentId String agentId,
            @BindRequest TransactionConfigDto configDto) throws Exception {
        try {
            configRepository.updateTransactionConfig(agentId, configDto.convert(),
                    configDto.version());
        } catch (OptimisticLockException e) {
            throw new JsonServiceException(PRECONDITION_FAILED, e);
        }
        return getTransactionConfig(agentId);
    }

    @POST(path = "/backend/config/ui", permission = "agent:config:edit:ui")
    String updateUiConfig(@BindAgentId String agentId, @BindRequest UiConfigDto configDto)
            throws Exception {
        try {
            configRepository.updateUiConfig(agentId, configDto.convert(),
                    configDto.version());
        } catch (OptimisticLockException e) {
            throw new JsonServiceException(PRECONDITION_FAILED, e);
        }
        return getUiConfig(agentId);
    }

    @POST(path = "/backend/config/plugins", permission = "agent:config:edit:plugins")
    String updatePluginConfig(@BindAgentId String agentId, @BindRequest PluginUpdateRequest request)
            throws Exception {
        List<PluginProperty> properties = Lists.newArrayList();
        for (PluginPropertyDto prop : request.properties()) {
            properties.add(prop.convert());
        }
        String pluginId = request.pluginId();
        try {
            configRepository.updatePluginConfig(agentId, pluginId, properties, request.version());
        } catch (OptimisticLockException e) {
            throw new JsonServiceException(PRECONDITION_FAILED, e);
        }
        return getPluginConfigInternal(agentId, pluginId);
    }

    @POST(path = "/backend/config/user-recording", permission = "agent:config:edit:userRecording")
    String updateUserRecordingConfig(@BindAgentId String agentId,
            @BindRequest UserRecordingConfigDto configDto) throws Exception {
        try {
            configRepository.updateUserRecordingConfig(agentId, configDto.convert(),
                    configDto.version());
        } catch (OptimisticLockException e) {
            throw new JsonServiceException(PRECONDITION_FAILED, e);
        }
        return getUserRecordingConfig(agentId);
    }

    @POST(path = "/backend/config/advanced", permission = "agent:config:edit:advanced")
    String updateAdvancedConfig(@BindAgentId String agentId,
            @BindRequest AdvancedConfigDto configDto) throws Exception {
        try {
            configRepository.updateAdvancedConfig(agentId, configDto.convert(),
                    configDto.version());
        } catch (OptimisticLockException e) {
            throw new JsonServiceException(PRECONDITION_FAILED, e);
        }
        return getAdvancedConfig(agentId);
    }

    private String getPluginConfigInternal(String agentId, String pluginId) throws IOException {
        PluginConfig config = configRepository.getPluginConfig(agentId, pluginId);
        if (config == null) {
            throw new IllegalArgumentException("Plugin id not found: " + pluginId);
        }
        return mapper.writeValueAsString(PluginConfigDto.create(config));
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
    abstract static class TransactionConfigDto {

        abstract Optional<String> agentId(); // only used in request
        abstract int slowThresholdMillis();
        abstract int profilingIntervalMillis();
        abstract boolean captureThreadStats();
        abstract String version();

        private TransactionConfig convert() {
            return TransactionConfig.newBuilder()
                    .setSlowThresholdMillis(of(slowThresholdMillis()))
                    .setProfilingIntervalMillis(of(profilingIntervalMillis()))
                    .setCaptureThreadStats(captureThreadStats())
                    .build();
        }
        private static TransactionConfigDto create(TransactionConfig config) {
            return ImmutableTransactionConfigDto.builder()
                    .slowThresholdMillis(config.getSlowThresholdMillis().getValue())
                    .profilingIntervalMillis(config.getProfilingIntervalMillis().getValue())
                    .captureThreadStats(config.getCaptureThreadStats())
                    .version(Versions.getVersion(config))
                    .build();
        }
    }

    @Value.Immutable
    abstract static class UserRecordingConfigDto {

        abstract Optional<String> agentId(); // only used in request
        abstract ImmutableList<String> users();
        abstract @Nullable Integer profilingIntervalMillis();
        abstract String version();

        private UserRecordingConfig convert() {
            UserRecordingConfig.Builder builder = UserRecordingConfig.newBuilder()
                    .addAllUser(users());
            Integer profilingIntervalMillis = profilingIntervalMillis();
            if (profilingIntervalMillis != null) {
                builder.setProfilingIntervalMillis(
                        OptionalInt32.newBuilder().setValue(profilingIntervalMillis));
            }
            return builder.build();
        }

        private static UserRecordingConfigDto create(UserRecordingConfig config) {
            ImmutableUserRecordingConfigDto.Builder builder =
                    ImmutableUserRecordingConfigDto.builder()
                            .users(config.getUserList());
            if (config.hasProfilingIntervalMillis()) {
                builder.profilingIntervalMillis(config.getProfilingIntervalMillis().getValue());
            }
            return builder.version(Versions.getVersion(config))
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
        abstract @Nullable Object value();
        abstract @Nullable Object defaultValue(); // only used in response
        abstract @Nullable String label(); // only used in response
        abstract @Nullable String checkboxLabel(); // only used in response
        abstract @Nullable String description(); // only used in response

        private PluginProperty convert() {
            return PluginProperty.newBuilder()
                    .setName(name())
                    .setValue(getValue())
                    .build();
        }

        private PluginProperty.Value getValue() {
            Object value = value();
            switch (type()) {
                case BOOLEAN:
                    checkNotNull(value);
                    return PluginProperty.Value.newBuilder().setBval((Boolean) value).build();
                case DOUBLE:
                    if (value == null) {
                        return PluginProperty.Value.newBuilder().setDvalNull(true).build();
                    } else {
                        return PluginProperty.Value.newBuilder()
                                .setDval(((Number) value).doubleValue()).build();
                    }
                case STRING:
                    checkNotNull(value);
                    return PluginProperty.Value.newBuilder().setSval((String) value).build();
                default:
                    throw new IllegalStateException("Unexpected property type: " + type());
            }
        }

        private static ImmutablePluginPropertyDto create(PluginProperty property) {
            return ImmutablePluginPropertyDto.builder()
                    .name(property.getName())
                    .type(getPropertyType(property.getValue().getValCase()))
                    .value(getPropertyValue(property.getValue()))
                    .defaultValue(getPropertyValue(property.getValue()))
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
                default:
                    throw new IllegalStateException("Unexpected property type: " + valCase);
            }
        }

        private static @Nullable Object getPropertyValue(PluginProperty.Value value) {
            PluginProperty.Value.ValCase valCase = value.getValCase();
            switch (valCase) {
                case BVAL:
                    return value.getBval();
                case DVAL_NULL:
                    return null;
                case DVAL:
                    return value.getDval();
                case SVAL:
                    return value.getSval();
                default:
                    throw new IllegalStateException("Unexpected property type: " + valCase);
            }
        }
    }

    enum PropertyType {
        BOOLEAN, DOUBLE, STRING;
    }

    @Value.Immutable
    abstract static class AdvancedConfigDto {

        abstract Optional<String> agentId(); // only used in request
        abstract boolean weavingTimer();
        abstract int immediatePartialStoreThresholdSeconds();
        abstract int maxAggregateTransactionsPerType();
        abstract int maxAggregateQueriesPerType();
        abstract int maxAggregateServiceCallsPerType();
        abstract int maxTraceEntriesPerTransaction();
        abstract int maxStackTraceSamplesPerTransaction();
        abstract int mbeanGaugeNotFoundDelaySeconds();
        abstract String version();

        private AdvancedConfig convert() {
            return AdvancedConfig.newBuilder()
                    .setWeavingTimer(weavingTimer())
                    .setImmediatePartialStoreThresholdSeconds(
                            of(immediatePartialStoreThresholdSeconds()))
                    .setMaxAggregateTransactionsPerType(of(maxAggregateTransactionsPerType()))
                    .setMaxAggregateQueriesPerType(of(maxAggregateQueriesPerType()))
                    .setMaxAggregateServiceCallsPerType(of(maxAggregateServiceCallsPerType()))
                    .setMaxTraceEntriesPerTransaction(of(maxTraceEntriesPerTransaction()))
                    .setMaxStackTraceSamplesPerTransaction(of(maxStackTraceSamplesPerTransaction()))
                    .setMbeanGaugeNotFoundDelaySeconds(of(mbeanGaugeNotFoundDelaySeconds()))
                    .build();
        }

        private static AdvancedConfigDto create(AdvancedConfig config) {
            return ImmutableAdvancedConfigDto.builder()
                    .weavingTimer(config.getWeavingTimer())
                    .immediatePartialStoreThresholdSeconds(
                            config.getImmediatePartialStoreThresholdSeconds().getValue())
                    .maxAggregateTransactionsPerType(
                            config.getMaxAggregateTransactionsPerType().getValue())
                    .maxAggregateQueriesPerType(config.getMaxAggregateQueriesPerType().getValue())
                    .maxAggregateServiceCallsPerType(
                            config.getMaxAggregateServiceCallsPerType().getValue())
                    .maxTraceEntriesPerTransaction(
                            config.getMaxTraceEntriesPerTransaction().getValue())
                    .maxStackTraceSamplesPerTransaction(
                            config.getMaxStackTraceSamplesPerTransaction().getValue())
                    .mbeanGaugeNotFoundDelaySeconds(
                            config.getMbeanGaugeNotFoundDelaySeconds().getValue())
                    .version(Versions.getVersion(config))
                    .build();
        }
    }

    @Value.Immutable
    abstract static class UiConfigDto {

        abstract Optional<String> agentId(); // only used in request
        abstract String defaultDisplayedTransactionType();
        abstract ImmutableList<Double> defaultDisplayedPercentiles();
        abstract String version();

        private UiConfig convert() throws Exception {
            return UiConfig.newBuilder()
                    .setDefaultDisplayedTransactionType(defaultDisplayedTransactionType())
                    .addAllDefaultDisplayedPercentile(
                            Ordering.natural().immutableSortedCopy(defaultDisplayedPercentiles()))
                    .build();
        }

        private static UiConfigDto create(UiConfig config) {
            return ImmutableUiConfigDto.builder()
                    .defaultDisplayedTransactionType(config.getDefaultDisplayedTransactionType())
                    .defaultDisplayedPercentiles(Ordering.natural()
                            .immutableSortedCopy(config.getDefaultDisplayedPercentileList()))
                    .version(Versions.getVersion(config))
                    .build();
        }
    }
}
