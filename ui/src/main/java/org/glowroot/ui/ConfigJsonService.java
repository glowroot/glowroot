/*
 * Copyright 2011-2016 the original author or authors.
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
import java.net.InetAddress;
import java.util.List;
import java.util.Map;

import javax.annotation.Nullable;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.base.Charsets;
import com.google.common.base.Optional;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;
import com.google.common.collect.Ordering;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.QueryStringDecoder;
import org.checkerframework.checker.nullness.qual.MonotonicNonNull;
import org.checkerframework.checker.nullness.qual.RequiresNonNull;
import org.immutables.value.Value;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.glowroot.common.live.LiveWeavingService;
import org.glowroot.common.util.ObjectMappers;
import org.glowroot.common.util.Versions;
import org.glowroot.storage.config.ImmutableSmtpConfig;
import org.glowroot.storage.config.ImmutableStorageConfig;
import org.glowroot.storage.config.ImmutableUserInterfaceConfig;
import org.glowroot.storage.config.SmtpConfig;
import org.glowroot.storage.config.StorageConfig;
import org.glowroot.storage.config.UserInterfaceConfig;
import org.glowroot.storage.config.UserInterfaceConfig.AnonymousAccess;
import org.glowroot.storage.repo.ConfigRepository;
import org.glowroot.storage.repo.ConfigRepository.OptimisticLockException;
import org.glowroot.storage.repo.RepoAdmin;
import org.glowroot.storage.repo.helper.AlertingService;
import org.glowroot.storage.util.Encryption;
import org.glowroot.storage.util.MailService;
import org.glowroot.ui.HttpServer.PortChangeFailedException;
import org.glowroot.wire.api.model.AgentConfigOuterClass.AgentConfig.AdvancedConfig;
import org.glowroot.wire.api.model.AgentConfigOuterClass.AgentConfig.PluginConfig;
import org.glowroot.wire.api.model.AgentConfigOuterClass.AgentConfig.PluginProperty;
import org.glowroot.wire.api.model.AgentConfigOuterClass.AgentConfig.TransactionConfig;
import org.glowroot.wire.api.model.AgentConfigOuterClass.AgentConfig.UserRecordingConfig;
import org.glowroot.wire.api.model.Proto.OptionalInt32;

import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;
import static io.netty.handler.codec.http.HttpResponseStatus.OK;
import static io.netty.handler.codec.http.HttpResponseStatus.PRECONDITION_FAILED;
import static io.netty.handler.codec.http.HttpVersion.HTTP_1_1;

@JsonService
class ConfigJsonService {

    private static final Logger logger = LoggerFactory.getLogger(ConfigJsonService.class);
    private static final ObjectMapper mapper = ObjectMappers.create();

    private final ConfigRepository configRepository;
    private final RepoAdmin repoAdmin;
    private final HttpSessionManager httpSessionManager;
    private final MailService mailService;
    private final @Nullable LiveWeavingService liveWeavingService;

    private volatile @MonotonicNonNull HttpServer httpServer;

    ConfigJsonService(ConfigRepository configRepository, RepoAdmin repoAdmin,
            HttpSessionManager httpSessionManager, MailService mailService,
            @Nullable LiveWeavingService liveWeavingService) {
        this.configRepository = configRepository;
        this.repoAdmin = repoAdmin;
        this.httpSessionManager = httpSessionManager;
        this.mailService = mailService;
        this.liveWeavingService = liveWeavingService;
    }

    void setHttpServer(HttpServer httpServer) {
        this.httpServer = httpServer;
    }

    @GET("/backend/config/transaction")
    String getTransactionConfig(String queryString) throws Exception {
        String serverId = getServerId(queryString);
        return getTransactionConfigInternal(serverId);
    }

    @GET("/backend/config/user-recording")
    String getUserRecordingConfig(String queryString) throws Exception {
        String serverId = getServerId(queryString);
        return getUserRecordingConfigInternal(serverId);
    }

    @GET("/backend/config/advanced")
    String getAdvancedConfig(String queryString) throws Exception {
        String serverId = getServerId(queryString);
        return getAdvancedConfigInternal(serverId);
    }

    @GET("/backend/config/plugins")
    String getPluginConfig(String queryString) throws Exception {
        PluginConfigRequest request = QueryStrings.decode(queryString, PluginConfigRequest.class);
        String serverId = checkNotNull(request.serverId());
        Optional<String> pluginId = request.pluginId();
        if (pluginId.isPresent()) {
            return getPluginConfigInternal(serverId, request.pluginId().get());
        } else {
            List<PluginResponse> pluginResponses = Lists.newArrayList();
            List<PluginConfig> pluginConfigs = configRepository.getPluginConfigs(serverId);
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

    private String getPluginConfigInternal(String serverId, String pluginId) throws IOException {
        PluginConfig config = configRepository.getPluginConfig(serverId, pluginId);
        if (config == null) {
            throw new IllegalArgumentException("Plugin id not found: " + pluginId);
        }
        return mapper.writeValueAsString(PluginConfigDto.create(config));
    }

    @GET("/backend/config/ui")
    String getUserInterfaceConfig() throws Exception {
        // this code cannot be reached when httpServer is null
        checkNotNull(httpServer);
        return getUserInterface(false);
    }

    @GET("/backend/config/storage")
    String getStorageConfig() throws Exception {
        StorageConfig config = configRepository.getStorageConfig();
        return mapper.writeValueAsString(StorageConfigDto.create(config));
    }

    @GET("/backend/config/smtp")
    String getSmtpConfig() throws Exception {
        SmtpConfig config = configRepository.getSmtpConfig();
        String localServerName = InetAddress.getLocalHost().getHostName();
        return mapper.writeValueAsString(ImmutableSmtpConfigResponse.builder()
                .config(SmtpConfigDto.create(config))
                .localServerName(localServerName)
                .build());
    }

    @POST("/backend/config/transaction")
    String updateTransactionConfig(String content) throws Exception {
        TransactionConfigDto configDto =
                mapper.readValue(content, ImmutableTransactionConfigDto.class);
        String serverId = checkNotNull(configDto.serverId());
        try {
            configRepository.updateTransactionConfig(serverId, configDto.convert(),
                    configDto.version());
        } catch (OptimisticLockException e) {
            throw new JsonServiceException(PRECONDITION_FAILED, e);
        }
        return getTransactionConfigInternal(serverId);
    }

    @POST("/backend/config/user-recording")
    String updateUserRecordingConfig(String content) throws Exception {
        UserRecordingConfigDto configDto =
                mapper.readValue(content, ImmutableUserRecordingConfigDto.class);
        String serverId = checkNotNull(configDto.serverId());
        try {
            configRepository.updateUserRecordingConfig(serverId, configDto.convert(),
                    configDto.version());
        } catch (OptimisticLockException e) {
            throw new JsonServiceException(PRECONDITION_FAILED, e);
        }
        return getUserRecordingConfigInternal(serverId);
    }

    @POST("/backend/config/advanced")
    String updateAdvancedConfig(String content) throws Exception {
        AdvancedConfigDto configDto = mapper.readValue(content, ImmutableAdvancedConfigDto.class);
        String serverId = checkNotNull(configDto.serverId());
        try {
            configRepository.updateAdvancedConfig(serverId, configDto.convert(),
                    configDto.version());
        } catch (OptimisticLockException e) {
            throw new JsonServiceException(PRECONDITION_FAILED, e);
        }
        return getAdvancedConfigInternal(serverId);
    }

    @POST("/backend/config/plugins")
    String updatePluginConfig(String content) throws Exception {
        PluginUpdateRequest pluginUpdateRequest =
                mapper.readValue(content, ImmutablePluginUpdateRequest.class);
        String serverId = pluginUpdateRequest.serverId();
        String pluginId = pluginUpdateRequest.pluginId();
        List<PluginProperty> properties = Lists.newArrayList();
        for (PluginPropertyDto prop : pluginUpdateRequest.properties()) {
            properties.add(prop.convert());
        }
        try {
            configRepository.updatePluginConfig(serverId, pluginId, properties,
                    pluginUpdateRequest.version());
        } catch (OptimisticLockException e) {
            throw new JsonServiceException(PRECONDITION_FAILED, e);
        }
        return getPluginConfigInternal(serverId, pluginId);
    }

    @POST("/backend/config/ui")
    Object updateUserInterfaceConfig(String content) throws Exception {
        // this code cannot be reached when httpServer is null
        checkNotNull(httpServer);
        UserInterfaceConfigDto configDto =
                mapper.readValue(content, ImmutableUserInterfaceConfigDto.class);
        configDto.validate();
        UserInterfaceConfig priorConfig = configRepository.getUserInterfaceConfig();
        UserInterfaceConfig config;
        try {
            config = configDto.convert(priorConfig);
        } catch (CurrentPasswordIncorrectException e) {
            return "{\"currentPasswordIncorrect\":true}";
        }
        try {
            configRepository.updateUserInterfaceConfig(config, configDto.version());
        } catch (OptimisticLockException e) {
            throw new JsonServiceException(PRECONDITION_FAILED, e);
        }
        return onSuccessfulUserInterfaceUpdate(priorConfig, config);
    }

    @POST("/backend/config/storage")
    String updateStorageConfig(String content) throws Exception {
        StorageConfigDto configDto = mapper.readValue(content, ImmutableStorageConfigDto.class);
        try {
            configRepository.updateStorageConfig(configDto.convert(), configDto.version());
        } catch (OptimisticLockException e) {
            throw new JsonServiceException(PRECONDITION_FAILED, e);
        }
        repoAdmin.resizeIfNecessary();
        return getStorageConfig();
    }

    @POST("/backend/config/smtp")
    String updateSmtpConfig(String content) throws Exception {
        SmtpConfigDto configDto = mapper.readValue(content, ImmutableSmtpConfigDto.class);
        try {
            configRepository.updateSmtpConfig(configDto.convert(configRepository),
                    configDto.version());
        } catch (OptimisticLockException e) {
            throw new JsonServiceException(PRECONDITION_FAILED, e);
        }
        return getSmtpConfig();
    }

    @POST("/backend/config/send-test-email")
    void sendTestEmail(String content) throws Exception {
        SmtpConfigDto configDto = mapper.readValue(content, ImmutableSmtpConfigDto.class);
        String testEmailRecipient = configDto.testEmailRecipient();
        checkNotNull(testEmailRecipient);
        AlertingService.sendTestEmails(testEmailRecipient, configDto.convert(configRepository),
                configRepository, mailService);
    }

    private String getTransactionConfigInternal(String serverId) throws IOException {
        TransactionConfig config = configRepository.getTransactionConfig(serverId);
        if (config == null) {
            return "{\"empty\": true}";
        }
        return mapper.writeValueAsString(TransactionConfigDto.create(config));
    }

    private String getUserRecordingConfigInternal(String serverId) throws IOException {
        UserRecordingConfig config = configRepository.getUserRecordingConfig(serverId);
        return mapper.writeValueAsString(UserRecordingConfigDto.create(config));
    }

    private String getAdvancedConfigInternal(String serverId) throws IOException {
        checkNotNull(liveWeavingService);
        AdvancedConfig config = configRepository.getAdvancedConfig(serverId);
        return mapper.writeValueAsString(AdvancedConfigDto.create(config));
    }

    @RequiresNonNull("httpServer")
    private Object onSuccessfulUserInterfaceUpdate(UserInterfaceConfig priorConfig,
            UserInterfaceConfig config) throws Exception {
        boolean portChangedSucceeded = false;
        boolean portChangedFailed = false;
        if (priorConfig.port() != config.port()) {
            try {
                httpServer.changePort(config.port());
                portChangedSucceeded = true;
            } catch (PortChangeFailedException e) {
                logger.error(e.getMessage(), e);
                portChangedFailed = true;
            }
        }
        String responseText = getUserInterface(portChangedFailed);
        ByteBuf responseContent = Unpooled.copiedBuffer(responseText, Charsets.ISO_8859_1);
        FullHttpResponse response = new DefaultFullHttpResponse(HTTP_1_1, OK, responseContent);
        if (portChangedSucceeded) {
            response.headers().set("Glowroot-Port-Changed", "true");
        }
        // only create/delete session on successful update
        if (!priorConfig.adminPasswordEnabled() && config.adminPasswordEnabled()) {
            httpSessionManager.createSession(response, true);
        } else if (priorConfig.adminPasswordEnabled() && !config.adminPasswordEnabled()) {
            httpSessionManager.clearAllSessions();
            httpSessionManager.deleteSessionCookie(response);
        }
        return response;
    }

    @RequiresNonNull("httpServer")
    private String getUserInterface(boolean portChangeFailed) throws Exception {
        UserInterfaceConfig config = configRepository.getUserInterfaceConfig();
        UserInterfaceConfigDto configDto = ImmutableUserInterfaceConfigDto.builder()
                .defaultDisplayedTransactionType(config.defaultDisplayedTransactionType())
                .defaultDisplayedPercentiles(Ordering.natural()
                        .immutableSortedCopy(config.defaultDisplayedPercentiles()))
                .port(config.port())
                .adminPasswordEnabled(config.adminPasswordEnabled())
                .readOnlyPasswordEnabled(config.readOnlyPasswordEnabled())
                .anonymousAccess(config.anonymousAccess())
                .sessionTimeoutMinutes(config.sessionTimeoutMinutes())
                .version(config.version())
                .build();
        return mapper.writeValueAsString(ImmutableUserInterfaceConfigResponse.builder()
                .config(configDto)
                .activePort(httpServer.getPort())
                .portChangeFailed(portChangeFailed)
                .build());
    }

    private static String getServerId(String queryString) {
        return QueryStringDecoder.decodeComponent(queryString.substring("server-id".length() + 1));
    }

    private static OptionalInt32 of(int value) {
        return OptionalInt32.newBuilder().setValue(value).build();
    }

    private static class AdminPasswordHelper {

        private final String currentPassword;
        private final String newPassword;
        private final String originalPasswordHash;

        private AdminPasswordHelper(String currentPassword, String newPassword,
                String originalPasswordHash) {
            this.currentPassword = currentPassword;
            this.newPassword = newPassword;
            this.originalPasswordHash = originalPasswordHash;
        }

        private String verifyAndGenerateNewPasswordHash() throws Exception {
            if (enablePassword()) {
                // UI validation prevents this from happening
                checkState(originalPasswordHash.isEmpty(), "Password is already enabled");
                return PasswordHash.createHash(newPassword);
            }
            if (!PasswordHash.validatePassword(currentPassword, originalPasswordHash)) {
                throw new CurrentPasswordIncorrectException();
            }
            if (disablePassword()) {
                return "";
            }
            if (changePassword()) {
                return PasswordHash.createHash(newPassword);
            }
            // UI validation prevents this from happening
            throw new IllegalStateException("Current and new password are both empty");
        }

        private boolean enablePassword() {
            return currentPassword.isEmpty() && !newPassword.isEmpty();
        }

        private boolean disablePassword() {
            return !currentPassword.isEmpty() && newPassword.isEmpty();
        }

        private boolean changePassword() {
            return !currentPassword.isEmpty() && !newPassword.isEmpty();
        }
    }

    @Value.Immutable
    interface UserInterfaceConfigResponse {
        UserInterfaceConfigDto config();
        int activePort();
        boolean portChangeFailed();
    }

    @Value.Immutable
    interface SmtpConfigResponse {
        SmtpConfigDto config();
        String localServerName();
    }

    @Value.Immutable
    interface PluginConfigRequest {
        String serverId();
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

        abstract @Nullable String serverId(); // only used in request
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

        abstract @Nullable String serverId(); // only used in request
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
            return ImmutableUserRecordingConfigDto.builder()
                    .users(config.getUserList())
                    .profilingIntervalMillis(config.getProfilingIntervalMillis().getValue())
                    .version(Versions.getVersion(config))
                    .build();
        }
    }

    @Value.Immutable
    abstract static class AdvancedConfigDto {

        abstract @Nullable String serverId(); // only used in request
        abstract boolean weavingTimer();
        abstract int immediatePartialStoreThresholdSeconds();
        abstract int maxAggregateTransactionsPerTransactionType();
        abstract int maxAggregateQueriesPerQueryType();
        abstract int maxTraceEntriesPerTransaction();
        abstract int maxStackTraceSamplesPerTransaction();
        abstract int mbeanGaugeNotFoundDelaySeconds();
        abstract String version();

        private AdvancedConfig convert() {
            return AdvancedConfig.newBuilder()
                    .setWeavingTimer(weavingTimer())
                    .setImmediatePartialStoreThresholdSeconds(
                            of(immediatePartialStoreThresholdSeconds()))
                    .setMaxAggregateTransactionsPerTransactionType(
                            of(maxAggregateTransactionsPerTransactionType()))
                    .setMaxAggregateQueriesPerQueryType(of(maxAggregateQueriesPerQueryType()))
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
                    .maxAggregateTransactionsPerTransactionType(
                            config.getMaxAggregateTransactionsPerTransactionType().getValue())
                    .maxAggregateQueriesPerQueryType(
                            config.getMaxAggregateQueriesPerQueryType().getValue())
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
    interface PluginUpdateRequest {
        String serverId();
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
                    return PropertyType.DOUBLE;
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
    abstract static class UserInterfaceConfigDto {

        abstract String defaultDisplayedTransactionType();
        abstract ImmutableList<Double> defaultDisplayedPercentiles();
        abstract int port();
        abstract boolean adminPasswordEnabled();
        abstract boolean readOnlyPasswordEnabled();
        abstract AnonymousAccess anonymousAccess();
        // only used for requests
        @Value.Default
        String currentAdminPassword() {
            return "";
        }
        // only used for requests
        @Value.Default
        String newAdminPassword() {
            return "";
        }
        // only used for requests
        @Value.Default
        String newReadOnlyPassword() {
            return "";
        }
        abstract int sessionTimeoutMinutes();
        abstract String version();

        private void validate() {
            if (readOnlyPasswordEnabled()) {
                checkState(adminPasswordEnabled());
            }
            switch (anonymousAccess()) {
                case ADMIN:
                    checkState(!adminPasswordEnabled());
                    checkState(!readOnlyPasswordEnabled());
                    break;
                case READ_ONLY:
                    checkState(adminPasswordEnabled());
                    checkState(!readOnlyPasswordEnabled());
                    break;
                case NONE:
                    checkState(adminPasswordEnabled());
                    break;
                default:
                    throw new IllegalStateException(
                            "Unexpected anonymous access: " + anonymousAccess());
            }
        }

        private UserInterfaceConfig convert(UserInterfaceConfig priorConfig) throws Exception {
            ImmutableUserInterfaceConfig.Builder builder = ImmutableUserInterfaceConfig.builder()
                    .defaultDisplayedTransactionType(defaultDisplayedTransactionType())
                    .defaultDisplayedPercentiles(defaultDisplayedPercentiles())
                    .port(port())
                    .sessionTimeoutMinutes(sessionTimeoutMinutes());
            if (currentAdminPassword().length() > 0 || newAdminPassword().length() > 0) {
                AdminPasswordHelper adminPasswordHelper =
                        new AdminPasswordHelper(currentAdminPassword(), newAdminPassword(),
                                priorConfig.adminPasswordHash());
                builder.adminPasswordHash(adminPasswordHelper.verifyAndGenerateNewPasswordHash());
            } else {
                builder.adminPasswordHash(priorConfig.adminPasswordHash());
            }
            if (!readOnlyPasswordEnabled()) {
                // clear read only password
                builder.readOnlyPasswordHash("");
            } else if (readOnlyPasswordEnabled() && !newReadOnlyPassword().isEmpty()) {
                // change read only password
                String readOnlyPasswordHash = PasswordHash.createHash(newReadOnlyPassword());
                builder.readOnlyPasswordHash(readOnlyPasswordHash);
            } else {
                // keep existing read only password
                builder.readOnlyPasswordHash(priorConfig.readOnlyPasswordHash());
            }
            if (priorConfig.anonymousAccess() != AnonymousAccess.ADMIN
                    && anonymousAccess() == AnonymousAccess.ADMIN
                    && currentAdminPassword().isEmpty()) {
                // enabling admin access for anonymous users requires admin password
                throw new IllegalStateException();
            }
            builder.anonymousAccess(anonymousAccess());
            return builder.build();
        }
    }

    @Value.Immutable
    abstract static class StorageConfigDto {

        abstract ImmutableList<Integer> rollupExpirationHours();
        abstract int traceExpirationHours();
        abstract ImmutableList<Integer> rollupCappedDatabaseSizesMb();
        abstract int traceCappedDatabaseSizeMb();
        abstract String version();

        private StorageConfig convert() {
            return ImmutableStorageConfig.builder()
                    .rollupExpirationHours(rollupExpirationHours())
                    .traceExpirationHours(traceExpirationHours())
                    .rollupCappedDatabaseSizesMb(rollupCappedDatabaseSizesMb())
                    .traceCappedDatabaseSizeMb(traceCappedDatabaseSizeMb())
                    .build();
        }

        private static StorageConfigDto create(StorageConfig config) {
            return ImmutableStorageConfigDto.builder()
                    .addAllRollupExpirationHours(config.rollupExpirationHours())
                    .traceExpirationHours(config.traceExpirationHours())
                    .addAllRollupCappedDatabaseSizesMb(config.rollupCappedDatabaseSizesMb())
                    .traceCappedDatabaseSizeMb(config.traceCappedDatabaseSizeMb())
                    .version(config.version())
                    .build();
        }
    }

    @Value.Immutable
    abstract static class SmtpConfigDto {

        abstract String fromEmailAddress();
        abstract String fromDisplayName();
        abstract String host();
        abstract @Nullable Integer port();
        abstract boolean ssl();
        abstract String username();
        abstract boolean passwordExists();
        abstract Map<String, String> additionalProperties();
        // only used for requests
        @Value.Default
        String newPassword() {
            return "";
        }
        // only used for requests
        abstract @Nullable String testEmailRecipient();
        abstract String version();

        private SmtpConfig convert(ConfigRepository configRepository) throws Exception {
            ImmutableSmtpConfig.Builder builder = ImmutableSmtpConfig.builder()
                    .fromEmailAddress(fromEmailAddress())
                    .fromDisplayName(fromDisplayName())
                    .host(host())
                    .port(port())
                    .ssl(ssl())
                    .username(username())
                    .putAllAdditionalProperties(additionalProperties());
            if (!passwordExists()) {
                // clear password
                builder.encryptedPassword("");
            } else if (passwordExists() && !newPassword().isEmpty()) {
                // change password
                String newEncryptedPassword =
                        Encryption.encrypt(newPassword(), configRepository.getSecretKey());
                builder.encryptedPassword(newEncryptedPassword);
            } else {
                // keep existing password
                SmtpConfig priorConfig = configRepository.getSmtpConfig();
                builder.encryptedPassword(priorConfig.encryptedPassword());
            }
            return builder.build();
        }

        private static SmtpConfigDto create(SmtpConfig config) {
            return ImmutableSmtpConfigDto.builder()
                    .fromEmailAddress(config.fromEmailAddress())
                    .fromDisplayName(config.fromDisplayName())
                    .host(config.host())
                    .port(config.port())
                    .ssl(config.ssl())
                    .username(config.username())
                    .passwordExists(!config.encryptedPassword().isEmpty())
                    .putAllAdditionalProperties(config.additionalProperties())
                    .version(config.version())
                    .build();
        }
    }

    @SuppressWarnings("serial")
    private static class CurrentPasswordIncorrectException extends Exception {}
}
