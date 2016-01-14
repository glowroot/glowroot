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

import java.net.InetAddress;
import java.util.List;
import java.util.Map;

import javax.annotation.Nullable;

import com.fasterxml.jackson.core.JsonProcessingException;
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

import org.glowroot.common.config.AdvancedConfig;
import org.glowroot.common.config.ImmutableAdvancedConfig;
import org.glowroot.common.config.ImmutablePluginConfig;
import org.glowroot.common.config.ImmutableTransactionConfig;
import org.glowroot.common.config.ImmutableUserRecordingConfig;
import org.glowroot.common.config.PluginConfig;
import org.glowroot.common.config.PluginDescriptor;
import org.glowroot.common.config.PropertyDescriptor;
import org.glowroot.common.config.PropertyValue;
import org.glowroot.common.config.TransactionConfig;
import org.glowroot.common.config.UserRecordingConfig;
import org.glowroot.common.live.LiveWeavingService;
import org.glowroot.common.util.ObjectMappers;
import org.glowroot.storage.repo.ConfigRepository;
import org.glowroot.storage.repo.ConfigRepository.OptimisticLockException;
import org.glowroot.storage.repo.RepoAdmin;
import org.glowroot.storage.repo.config.ImmutableSmtpConfig;
import org.glowroot.storage.repo.config.ImmutableStorageConfig;
import org.glowroot.storage.repo.config.ImmutableUserInterfaceConfig;
import org.glowroot.storage.repo.config.SmtpConfig;
import org.glowroot.storage.repo.config.StorageConfig;
import org.glowroot.storage.repo.config.UserInterfaceConfig;
import org.glowroot.storage.repo.config.UserInterfaceConfig.AnonymousAccess;
import org.glowroot.storage.repo.helper.AlertingService;
import org.glowroot.storage.util.Encryption;
import org.glowroot.storage.util.MailService;
import org.glowroot.ui.HttpServer.PortChangeFailedException;

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
    private final ImmutableList<PluginDescriptor> pluginDescriptors;
    private final HttpSessionManager httpSessionManager;
    private final MailService mailService;
    private final @Nullable LiveWeavingService liveWeavingService;

    private volatile @MonotonicNonNull HttpServer httpServer;

    ConfigJsonService(ConfigRepository configRepository, RepoAdmin repoAdmin,
            List<PluginDescriptor> pluginDescriptors, HttpSessionManager httpSessionManager,
            MailService mailService, @Nullable LiveWeavingService liveWeavingService) {
        this.configRepository = configRepository;
        this.repoAdmin = repoAdmin;
        this.pluginDescriptors = ImmutableList.copyOf(pluginDescriptors);
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
            for (PluginDescriptor pluginDescriptor : pluginDescriptors) {
                PluginConfig pluginConfig =
                        configRepository.getPluginConfig(serverId, pluginDescriptor.id());
                checkNotNull(pluginConfig);
                pluginResponses.add(ImmutablePluginResponse.builder()
                        .id(pluginDescriptor.id())
                        .name(pluginDescriptor.name())
                        .enabled(pluginConfig.enabled())
                        .build());
            }
            return mapper.writeValueAsString(pluginResponses);
        }
    }

    private String getPluginConfigInternal(String serverId, String pluginId)
            throws JsonProcessingException {
        PluginConfig config = configRepository.getPluginConfig(serverId, pluginId);
        PluginDescriptor pluginDescriptor = null;
        for (PluginDescriptor descriptor : pluginDescriptors) {
            if (descriptor.id().equals(pluginId)) {
                pluginDescriptor = descriptor;
                break;
            }
        }
        if (config == null || pluginDescriptor == null) {
            throw new IllegalArgumentException("Plugin id not found: " + pluginId);
        }
        return mapper.writeValueAsString(ImmutablePluginConfigResponse.builder()
                .name(pluginDescriptor.name())
                .addAllPropertyDescriptors(pluginDescriptor.properties())
                .config(PluginConfigDto.fromConfig(config))
                .build());
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
        return mapper.writeValueAsString(StorageConfigDto.fromConfig(config));
    }

    @GET("/backend/config/smtp")
    String getSmtpConfig() throws Exception {
        SmtpConfig config = configRepository.getSmtpConfig();
        String localServerName = InetAddress.getLocalHost().getHostName();
        return mapper.writeValueAsString(ImmutableSmtpConfigResponse.builder()
                .config(SmtpConfigDto.fromConfig(config))
                .localServerName(localServerName)
                .build());
    }

    @POST("/backend/config/transaction")
    String updateTransactionConfig(String content) throws Exception {
        TransactionConfigDto configDto =
                mapper.readValue(content, ImmutableTransactionConfigDto.class);
        String serverId = checkNotNull(configDto.serverId());
        try {
            configRepository.updateTransactionConfig(serverId, configDto.toConfig(),
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
            configRepository.updateUserRecordingConfig(serverId, configDto.toConfig(),
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
            configRepository.updateAdvancedConfig(serverId, configDto.toConfig(),
                    configDto.version());
        } catch (OptimisticLockException e) {
            throw new JsonServiceException(PRECONDITION_FAILED, e);
        }
        return getAdvancedConfigInternal(serverId);
    }

    @POST("/backend/config/plugins")
    String updatePluginConfig(String content) throws Exception {
        PluginConfigDto configDto = mapper.readValue(content, ImmutablePluginConfigDto.class);
        String serverId = checkNotNull(configDto.serverId());
        String pluginId = checkNotNull(configDto.pluginId());
        try {
            configRepository.updatePluginConfig(serverId, configDto.toConfig(pluginId),
                    configDto.version());
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
            config = toConfig(configDto, priorConfig);
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
            configRepository.updateStorageConfig(configDto.toConfig(), configDto.version());
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
            configRepository.updateSmtpConfig(toConfig(configDto), configDto.version());
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
        AlertingService.sendTestEmails(testEmailRecipient, toConfig(configDto), configRepository,
                mailService);
    }

    private String getTransactionConfigInternal(String serverId) throws JsonProcessingException {
        TransactionConfig config = configRepository.getTransactionConfig(serverId);
        if (config == null) {
            return "{\"empty\": true}";
        }
        return mapper.writeValueAsString(TransactionConfigDto.fromConfig(config));
    }

    private String getUserRecordingConfigInternal(String serverId) throws JsonProcessingException {
        UserRecordingConfig config = configRepository.getUserRecordingConfig(serverId);
        return mapper.writeValueAsString(UserRecordingConfigDto.fromConfig(config));
    }

    private String getAdvancedConfigInternal(String serverId) throws JsonProcessingException {
        checkNotNull(liveWeavingService);
        AdvancedConfig config = configRepository.getAdvancedConfig(serverId);
        return mapper.writeValueAsString(AdvancedConfigDto.fromConfig(config));
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

    private UserInterfaceConfig toConfig(UserInterfaceConfigDto configDto,
            UserInterfaceConfig priorConfig) throws Exception {
        ImmutableUserInterfaceConfig.Builder builder = ImmutableUserInterfaceConfig.builder()
                .defaultDisplayedTransactionType(configDto.defaultDisplayedTransactionType())
                .defaultDisplayedPercentiles(configDto.defaultDisplayedPercentiles())
                .port(configDto.port())
                .sessionTimeoutMinutes(configDto.sessionTimeoutMinutes());
        if (configDto.currentAdminPassword().length() > 0
                || configDto.newAdminPassword().length() > 0) {
            AdminPasswordHelper adminPasswordHelper =
                    new AdminPasswordHelper(configDto.currentAdminPassword(),
                            configDto.newAdminPassword(), priorConfig.adminPasswordHash());
            builder.adminPasswordHash(adminPasswordHelper.verifyAndGenerateNewPasswordHash());
        } else {
            builder.adminPasswordHash(priorConfig.adminPasswordHash());
        }
        if (!configDto.readOnlyPasswordEnabled()) {
            // clear read only password
            builder.readOnlyPasswordHash("");
        } else if (configDto.readOnlyPasswordEnabled()
                && !configDto.newReadOnlyPassword().isEmpty()) {
            // change read only password
            String readOnlyPasswordHash = PasswordHash.createHash(configDto.newReadOnlyPassword());
            builder.readOnlyPasswordHash(readOnlyPasswordHash);
        } else {
            // keep existing read only password
            builder.readOnlyPasswordHash(priorConfig.readOnlyPasswordHash());
        }
        if (priorConfig.anonymousAccess() != AnonymousAccess.ADMIN
                && configDto.anonymousAccess() == AnonymousAccess.ADMIN
                && configDto.currentAdminPassword().isEmpty()) {
            // enabling admin access for anonymous users requires admin password
            throw new IllegalStateException();
        }
        builder.anonymousAccess(configDto.anonymousAccess());
        return builder.build();
    }

    private SmtpConfig toConfig(SmtpConfigDto configDto) throws Exception {
        ImmutableSmtpConfig.Builder builder = ImmutableSmtpConfig.builder()
                .fromEmailAddress(configDto.fromEmailAddress())
                .fromDisplayName(configDto.fromDisplayName())
                .host(configDto.host())
                .port(configDto.port())
                .ssl(configDto.ssl())
                .username(configDto.username())
                .putAllAdditionalProperties(configDto.additionalProperties());
        if (!configDto.passwordExists()) {
            // clear password
            builder.encryptedPassword("");
        } else if (configDto.passwordExists() && !configDto.newPassword().isEmpty()) {
            // change password
            String newEncryptedPassword =
                    Encryption.encrypt(configDto.newPassword(), configRepository.getSecretKey());
            builder.encryptedPassword(newEncryptedPassword);
        } else {
            // keep existing password
            SmtpConfig priorConfig = configRepository.getSmtpConfig();
            builder.encryptedPassword(priorConfig.encryptedPassword());
        }
        return builder.build();
    }

    private static String getServerId(String queryString) {
        return QueryStringDecoder.decodeComponent(queryString.substring("server-id".length() + 1));
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
        boolean enabled();
    }

    @Value.Immutable
    interface PluginConfigResponse {
        String name();
        PluginConfigDto config();
        ImmutableList<PropertyDescriptor> propertyDescriptors();
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

        private TransactionConfig toConfig() {
            return ImmutableTransactionConfig.builder()
                    .slowThresholdMillis(slowThresholdMillis())
                    .profilingIntervalMillis(profilingIntervalMillis())
                    .captureThreadStats(captureThreadStats())
                    .build();
        }
        private static TransactionConfigDto fromConfig(TransactionConfig config) {
            return ImmutableTransactionConfigDto.builder()
                    .slowThresholdMillis(config.slowThresholdMillis())
                    .profilingIntervalMillis(config.profilingIntervalMillis())
                    .captureThreadStats(config.captureThreadStats())
                    .version(config.version())
                    .build();
        }
    }

    @Value.Immutable
    abstract static class UserRecordingConfigDto {

        abstract @Nullable String serverId(); // only used in request
        abstract ImmutableList<String> users();
        abstract @Nullable Integer profilingIntervalMillis();
        abstract String version();

        private UserRecordingConfig toConfig() {
            return ImmutableUserRecordingConfig.builder()
                    .users(users())
                    .profilingIntervalMillis(profilingIntervalMillis())
                    .build();
        }

        private static UserRecordingConfigDto fromConfig(UserRecordingConfig config) {
            return ImmutableUserRecordingConfigDto.builder()
                    .users(config.users())
                    .profilingIntervalMillis(config.profilingIntervalMillis())
                    .version(config.version())
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

        private AdvancedConfig toConfig() {
            return ImmutableAdvancedConfig.builder()
                    .weavingTimer(weavingTimer())
                    .immediatePartialStoreThresholdSeconds(immediatePartialStoreThresholdSeconds())
                    .maxAggregateTransactionsPerTransactionType(
                            maxAggregateTransactionsPerTransactionType())
                    .maxAggregateQueriesPerQueryType(maxAggregateQueriesPerQueryType())
                    .maxTraceEntriesPerTransaction(maxTraceEntriesPerTransaction())
                    .maxStackTraceSamplesPerTransaction(maxStackTraceSamplesPerTransaction())
                    .mbeanGaugeNotFoundDelaySeconds(mbeanGaugeNotFoundDelaySeconds())
                    .build();
        }

        private static AdvancedConfigDto fromConfig(AdvancedConfig config) {
            return ImmutableAdvancedConfigDto.builder()
                    .weavingTimer(config.weavingTimer())
                    .immediatePartialStoreThresholdSeconds(
                            config.immediatePartialStoreThresholdSeconds())
                    .maxAggregateTransactionsPerTransactionType(
                            config.maxAggregateTransactionsPerTransactionType())
                    .maxAggregateQueriesPerQueryType(config.maxAggregateQueriesPerQueryType())
                    .maxTraceEntriesPerTransaction(config.maxTraceEntriesPerTransaction())
                    .maxStackTraceSamplesPerTransaction(
                            config.maxStackTraceSamplesPerTransaction())
                    .mbeanGaugeNotFoundDelaySeconds(config.mbeanGaugeNotFoundDelaySeconds())
                    .version(config.version())
                    .build();
        }
    }

    @Value.Immutable
    abstract static class PluginConfigDto {

        abstract @Nullable String serverId(); // only used in request
        abstract @Nullable String pluginId(); // only used in request
        abstract boolean enabled();
        abstract Map<String, PropertyValue> properties();
        abstract String version();

        private PluginConfig toConfig(String id) {
            return ImmutablePluginConfig.builder()
                    .id(id)
                    .enabled(enabled())
                    .putAllProperties(properties())
                    .build();
        }

        private static PluginConfigDto fromConfig(PluginConfig config) {
            return ImmutablePluginConfigDto.builder()
                    .enabled(config.enabled())
                    .putAllProperties(config.properties())
                    .version(config.version())
                    .build();
        }
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
    }

    @Value.Immutable
    abstract static class StorageConfigDto {

        abstract ImmutableList<Integer> rollupExpirationHours();
        abstract int traceExpirationHours();
        abstract ImmutableList<Integer> rollupCappedDatabaseSizesMb();
        abstract int traceCappedDatabaseSizeMb();
        abstract String version();

        private StorageConfig toConfig() {
            return ImmutableStorageConfig.builder()
                    .rollupExpirationHours(rollupExpirationHours())
                    .traceExpirationHours(traceExpirationHours())
                    .rollupCappedDatabaseSizesMb(rollupCappedDatabaseSizesMb())
                    .traceCappedDatabaseSizeMb(traceCappedDatabaseSizeMb())
                    .build();
        }

        private static StorageConfigDto fromConfig(StorageConfig config) {
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

        private static SmtpConfigDto fromConfig(SmtpConfig config) {
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
