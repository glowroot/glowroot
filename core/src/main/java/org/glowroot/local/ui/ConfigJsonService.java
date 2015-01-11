/*
 * Copyright 2011-2015 the original author or authors.
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
package org.glowroot.local.ui;

import java.io.File;
import java.security.GeneralSecurityException;
import java.util.List;
import java.util.Map;

import com.google.common.collect.ImmutableList;
import org.checkerframework.checker.nullness.qual.MonotonicNonNull;
import org.checkerframework.checker.nullness.qual.RequiresNonNull;
import org.immutables.common.marshal.Marshaling;
import org.immutables.value.Json;
import org.immutables.value.Value;
import org.jboss.netty.handler.codec.http.HttpResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.glowroot.common.Marshaling2;
import org.glowroot.config.AdvancedConfig;
import org.glowroot.config.ConfigService;
import org.glowroot.config.ConfigService.OptimisticLockException;
import org.glowroot.config.ImmutableAdvancedConfig;
import org.glowroot.config.ImmutablePluginConfig;
import org.glowroot.config.ImmutableProfilingConfig;
import org.glowroot.config.ImmutableStorageConfig;
import org.glowroot.config.ImmutableTraceConfig;
import org.glowroot.config.ImmutableUserInterfaceConfig;
import org.glowroot.config.ImmutableUserRecordingConfig;
import org.glowroot.config.MarshalingRoutines;
import org.glowroot.config.PluginConfig;
import org.glowroot.config.PluginDescriptor;
import org.glowroot.config.ProfilingConfig;
import org.glowroot.config.PropertyDescriptor;
import org.glowroot.config.PropertyValue;
import org.glowroot.config.StorageConfig;
import org.glowroot.config.TraceConfig;
import org.glowroot.config.UserInterfaceConfig;
import org.glowroot.config.UserRecordingConfig;
import org.glowroot.local.store.CappedDatabase;
import org.glowroot.local.ui.HttpServer.PortChangeFailedException;
import org.glowroot.transaction.TransactionModule;

import static com.google.common.base.Preconditions.checkNotNull;
import static org.jboss.netty.handler.codec.http.HttpResponseStatus.PRECONDITION_FAILED;

@JsonService
class ConfigJsonService {

    private static final Logger logger = LoggerFactory.getLogger(ConfigJsonService.class);

    private final ConfigService configService;
    private final CappedDatabase cappedDatabase;
    private final ImmutableList<PluginDescriptor> pluginDescriptors;
    private final File dataDir;
    private final HttpSessionManager httpSessionManager;
    private final TransactionModule transactionModule;

    private volatile @MonotonicNonNull HttpServer httpServer;

    ConfigJsonService(ConfigService configService, CappedDatabase cappedDatabase,
            List<PluginDescriptor> pluginDescriptors, File dataDir,
            HttpSessionManager httpSessionManager, TransactionModule transactionModule) {
        this.configService = configService;
        this.cappedDatabase = cappedDatabase;
        this.pluginDescriptors = ImmutableList.copyOf(pluginDescriptors);
        this.dataDir = dataDir;
        this.httpSessionManager = httpSessionManager;
        this.transactionModule = transactionModule;
    }

    void setHttpServer(HttpServer httpServer) {
        this.httpServer = httpServer;
    }

    @GET("/backend/config/trace")
    String getTraceConfig() throws Exception {
        TraceConfig config = configService.getTraceConfig();
        return Marshaling2.toJson(ImmutableTraceConfigResponse.builder()
                .config(TraceConfigDto.fromConfig(config))
                .build());
    }

    @GET("/backend/config/profiling")
    String getProfilingConfig() throws Exception {
        ProfilingConfig config = configService.getProfilingConfig();
        return Marshaling2.toJson(ImmutableProfilingConfigResponse.builder()
                .config(ProfilingConfigDto.fromConfig(config))
                .build());
    }

    @GET("/backend/config/user-recording")
    String getUserRecordingConfig() throws Exception {
        UserRecordingConfig config = configService.getUserRecordingConfig();
        return Marshaling2.toJson(ImmutableUserRecordingConfigResponse.builder()
                .config(UserRecordingConfigDto.fromConfig(config))
                .build());
    }

    @GET("/backend/config/storage")
    String getStorage() throws Exception {
        StorageConfig config = configService.getStorageConfig();
        return Marshaling2.toJson(ImmutableStorageConfigResponse.builder()
                .config(StorageConfigDto.fromConfig(config))
                .dataDir(dataDir.getCanonicalPath())
                .build());
    }

    @GET("/backend/config/user-interface")
    String getUserInterface() throws Exception {
        // this code cannot be reached when httpServer is null
        checkNotNull(httpServer);
        return getUserInterface(false);
    }

    @GET("/backend/config/advanced")
    String getAdvanced() throws Exception {
        AdvancedConfig config = configService.getAdvancedConfig();
        return Marshaling2.toJson(ImmutableAdvancedConfigResponse.builder()
                .config(AdvancedConfigDto.fromConfig(config))
                .metricWrapperMethodsActive(transactionModule.isMetricWrapperMethods())
                .build());
    }

    @GET("/backend/config/plugin/(.+)")
    String getPluginConfig(String pluginId) throws Exception {
        PluginConfig config = configService.getPluginConfig(pluginId);
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
        return Marshaling2.toJson(ImmutablePluginConfigResponse.builder()
                .name(pluginDescriptor.name())
                .addAllPropertyDescriptors(pluginDescriptor.properties())
                .config(PluginConfigDto.fromConfig(config))
                .build());
    }

    @POST("/backend/config/trace")
    String updateTraceConfig(String content) throws Exception {
        TraceConfigDto configDto = Marshaling.fromJson(content, TraceConfigDto.class);
        try {
            configService.updateTraceConfig(configDto.toConfig(), configDto.version());
        } catch (OptimisticLockException e) {
            throw new JsonServiceException(PRECONDITION_FAILED, e);
        }
        return getTraceConfig();
    }

    @POST("/backend/config/profiling")
    String updateProfilingConfig(String content) throws Exception {
        ProfilingConfigDto configDto = Marshaling.fromJson(content, ProfilingConfigDto.class);
        try {
            configService.updateProfilingConfig(configDto.toConfig(), configDto.version());
        } catch (OptimisticLockException e) {
            throw new JsonServiceException(PRECONDITION_FAILED, e);
        }
        return getProfilingConfig();
    }

    @POST("/backend/config/user-recording")
    String updateUserRecordingConfig(String content) throws Exception {
        UserRecordingConfigDto configDto =
                Marshaling.fromJson(content, UserRecordingConfigDto.class);
        try {
            configService.updateUserRecordingConfig(configDto.toConfig(), configDto.version());
        } catch (OptimisticLockException e) {
            throw new JsonServiceException(PRECONDITION_FAILED, e);
        }
        return getUserRecordingConfig();
    }

    @POST("/backend/config/storage")
    String updateStorageConfig(String content) throws Exception {
        StorageConfigDto configDto = Marshaling.fromJson(content, StorageConfigDto.class);
        try {
            configService.updateStorageConfig(configDto.toConfig(), configDto.version());
        } catch (OptimisticLockException e) {
            throw new JsonServiceException(PRECONDITION_FAILED, e);
        }
        // resize() doesn't do anything if the new and old value are the same
        cappedDatabase.resize(configService.getStorageConfig().cappedDatabaseSizeMb() * 1024);
        return getStorage();
    }

    @POST("/backend/config/user-interface")
    String updateUserInterfaceConfig(String content, HttpResponse response) throws Exception {
        // this code cannot be reached when httpServer is null
        checkNotNull(httpServer);
        UserInterfaceConfigDto configDto =
                Marshaling.fromJson(content, UserInterfaceConfigDto.class);
        ImmutableUserInterfaceConfig.Builder builder = ImmutableUserInterfaceConfig.builder()
                .defaultTransactionType(configDto.defaultTransactionType())
                .port(configDto.port())
                .sessionTimeoutMinutes(configDto.sessionTimeoutMinutes());
        UserInterfaceConfig priorConfig = configService.getUserInterfaceConfig();
        if (configDto.currentPassword().length() > 0 || configDto.newPassword().length() > 0) {
            try {
                builder.passwordHash(verifyAndGenerateNewPasswordHash(configDto.currentPassword(),
                        configDto.newPassword(), priorConfig.passwordHash()));
            } catch (CurrentPasswordIncorrectException e) {
                return "{\"currentPasswordIncorrect\":true}";
            }
        } else {
            builder.passwordHash(priorConfig.passwordHash());
        }
        UserInterfaceConfig config = builder.build();
        try {
            configService.updateUserInterfaceConfig(config, configDto.version());
        } catch (OptimisticLockException e) {
            throw new JsonServiceException(PRECONDITION_FAILED, e);
        }
        // only create/delete session on successful update
        if (!priorConfig.passwordEnabled() && config.passwordEnabled()) {
            httpSessionManager.createSession(response);
        } else if (priorConfig.passwordEnabled() && !config.passwordEnabled()) {
            httpSessionManager.clearAllSessions();
            httpSessionManager.deleteSessionCookie(response);
        }
        // lastly deal with ui port change
        if (priorConfig.port() != config.port()) {
            try {
                httpServer.changePort(config.port());
                response.headers().set("Glowroot-Port-Changed", "true");
            } catch (PortChangeFailedException e) {
                logger.error(e.getMessage(), e);
                return getUserInterface(true);
            }
        }
        return getUserInterface(false);
    }

    @POST("/backend/config/advanced")
    String updateAdvancedConfig(String content) throws Exception {
        AdvancedConfigDto configDto = Marshaling.fromJson(content, AdvancedConfigDto.class);
        try {
            configService.updateAdvancedConfig(configDto.toConfig(), configDto.version());
        } catch (OptimisticLockException e) {
            throw new JsonServiceException(PRECONDITION_FAILED, e);
        }
        return getAdvanced();
    }

    @POST("/backend/config/plugin/(.+)")
    String updatePluginConfig(String pluginId, String content) throws Exception {
        PluginConfigDto configDto = Marshaling.fromJson(content, PluginConfigDto.class);
        try {
            configService.updatePluginConfig(configDto.toConfig(pluginId), configDto.version());
        } catch (OptimisticLockException e) {
            throw new JsonServiceException(PRECONDITION_FAILED, e);
        }
        return getPluginConfig(pluginId);
    }

    @RequiresNonNull("httpServer")
    private String getUserInterface(boolean portChangeFailed) {
        UserInterfaceConfig config = configService.getUserInterfaceConfig();
        UserInterfaceConfigDto configDto = ImmutableUserInterfaceConfigDto.builder()
                .defaultTransactionType(config.defaultTransactionType())
                .port(config.port())
                .passwordEnabled(config.passwordEnabled())
                .sessionTimeoutMinutes(config.sessionTimeoutMinutes())
                .version(config.version())
                .build();
        return Marshaling2.toJson(ImmutableUserInterfaceConfigResponse.builder()
                .config(configDto)
                .activePort(httpServer.getPort())
                .portChangeFailed(portChangeFailed)
                .build());
    }

    private static String verifyAndGenerateNewPasswordHash(String currentPassword,
            String newPassword, String originalPasswordHash) throws GeneralSecurityException,
            CurrentPasswordIncorrectException {
        if (currentPassword.isEmpty() && !newPassword.isEmpty()) {
            // enabling password
            if (!originalPasswordHash.isEmpty()) {
                // UI validation prevents this from happening
                throw new IllegalStateException("Password is already enabled");
            }
            return PasswordHash.createHash(newPassword);
        } else if (!currentPassword.isEmpty() && newPassword.isEmpty()) {
            // disabling password
            if (!PasswordHash.validatePassword(currentPassword, originalPasswordHash)) {
                throw new CurrentPasswordIncorrectException();
            }
            return "";
        } else if (currentPassword.isEmpty() && newPassword.isEmpty()) {
            // UI validation prevents this from happening
            throw new IllegalStateException("Current and new password are both empty");
        } else {
            // changing password
            if (!PasswordHash.validatePassword(currentPassword, originalPasswordHash)) {
                throw new CurrentPasswordIncorrectException();
            }
            return PasswordHash.createHash(newPassword);
        }
    }

    @Value.Immutable
    @Json.Marshaled
    abstract static class TraceConfigResponse {
        abstract TraceConfigDto config();
    }

    @Value.Immutable
    @Json.Marshaled
    abstract static class ProfilingConfigResponse {
        abstract ProfilingConfigDto config();
    }

    @Value.Immutable
    @Json.Marshaled
    abstract static class UserRecordingConfigResponse {
        abstract UserRecordingConfigDto config();
    }

    @Value.Immutable
    @Json.Marshaled
    abstract static class StorageConfigResponse {
        abstract StorageConfigDto config();
        abstract String dataDir();
    }

    @Value.Immutable
    @Json.Marshaled
    abstract static class UserInterfaceConfigResponse {
        abstract UserInterfaceConfigDto config();
        abstract int activePort();
        abstract boolean portChangeFailed();
    }

    @Value.Immutable
    @Json.Marshaled
    abstract static class AdvancedConfigResponse {
        abstract AdvancedConfigDto config();
        abstract boolean metricWrapperMethodsActive();
    }

    @Value.Immutable
    @Json.Marshaled
    abstract static class PluginConfigResponse {
        abstract String name();
        abstract PluginConfigDto config();
        @Json.ForceEmpty
        abstract List<PropertyDescriptor> propertyDescriptors();
    }

    // these DTOs are only different from underlying config objects in that they contain the version
    // attribute, and that they have no default attribute values

    @Value.Immutable
    @Json.Marshaled
    abstract static class TraceConfigDto {

        abstract boolean enabled();
        abstract int storeThresholdMillis();
        abstract String version();

        private static TraceConfigDto fromConfig(TraceConfig config) {
            return ImmutableTraceConfigDto.builder()
                    .enabled(config.enabled())
                    .storeThresholdMillis(config.storeThresholdMillis())
                    .version(config.version())
                    .build();
        }

        private TraceConfig toConfig() {
            return ImmutableTraceConfig.builder()
                    .enabled(enabled())
                    .storeThresholdMillis(storeThresholdMillis())
                    .build();
        }
    }

    @Value.Immutable
    @Json.Marshaled
    abstract static class ProfilingConfigDto {

        abstract boolean enabled();
        abstract int intervalMillis();
        abstract String version();

        private static ProfilingConfigDto fromConfig(ProfilingConfig config) {
            return ImmutableProfilingConfigDto.builder()
                    .enabled(config.enabled())
                    .intervalMillis(config.intervalMillis())
                    .version(config.version())
                    .build();
        }

        private ProfilingConfig toConfig() {
            return ImmutableProfilingConfig.builder()
                    .enabled(enabled())
                    .intervalMillis(intervalMillis())
                    .build();
        }
    }

    @Value.Immutable
    @Json.Marshaled
    abstract static class UserRecordingConfigDto {

        abstract boolean enabled();
        abstract String user();
        abstract int profileIntervalMillis();
        abstract String version();

        private static UserRecordingConfigDto fromConfig(UserRecordingConfig config) {
            return ImmutableUserRecordingConfigDto.builder()
                    .enabled(config.enabled())
                    .user(config.user())
                    .profileIntervalMillis(config.profileIntervalMillis())
                    .version(config.version())
                    .build();
        }

        private UserRecordingConfig toConfig() {
            return ImmutableUserRecordingConfig.builder()
                    .enabled(enabled())
                    .user(user())
                    .profileIntervalMillis(profileIntervalMillis())
                    .build();
        }
    }

    @Value.Immutable
    @Json.Marshaled
    abstract static class StorageConfigDto {

        abstract int aggregateExpirationHours();
        abstract int traceExpirationHours();
        abstract int cappedDatabaseSizeMb();
        abstract String version();

        private static StorageConfigDto fromConfig(StorageConfig config) {
            return ImmutableStorageConfigDto.builder()
                    .aggregateExpirationHours(config.aggregateExpirationHours())
                    .traceExpirationHours(config.traceExpirationHours())
                    .cappedDatabaseSizeMb(config.cappedDatabaseSizeMb())
                    .version(config.version())
                    .build();
        }

        private StorageConfig toConfig() {
            return ImmutableStorageConfig.builder()
                    .aggregateExpirationHours(aggregateExpirationHours())
                    .traceExpirationHours(traceExpirationHours())
                    .cappedDatabaseSizeMb(cappedDatabaseSizeMb())
                    .build();
        }
    }

    @Value.Immutable
    @Json.Marshaled
    abstract static class UserInterfaceConfigDto {

        abstract String defaultTransactionType();
        abstract int port();
        abstract boolean passwordEnabled();
        // only used for requests
        @Value.Default
        String currentPassword() {
            return "";
        }
        // only used for requests
        @Value.Default
        String newPassword() {
            return "";
        }
        abstract int sessionTimeoutMinutes();
        abstract String version();
    }

    @Value.Immutable
    @Json.Marshaled
    abstract static class AdvancedConfigDto {

        abstract boolean metricWrapperMethods();
        abstract int immediatePartialStoreThresholdSeconds();
        abstract int maxTraceEntriesPerTransaction();
        abstract int maxStackTraceSamplesPerTransaction();
        abstract boolean captureThreadInfo();
        abstract boolean captureGcInfo();
        abstract int mbeanGaugeNotFoundDelaySeconds();
        abstract int internalQueryTimeoutSeconds();
        abstract String version();

        private static AdvancedConfigDto fromConfig(AdvancedConfig config) {
            return ImmutableAdvancedConfigDto
                    .builder()
                    .metricWrapperMethods(config.metricWrapperMethods())
                    .immediatePartialStoreThresholdSeconds(
                            config.immediatePartialStoreThresholdSeconds())
                    .maxTraceEntriesPerTransaction(config.maxTraceEntriesPerTransaction())
                    .maxStackTraceSamplesPerTransaction(config.maxStackTraceSamplesPerTransaction())
                    .captureThreadInfo(config.captureThreadInfo())
                    .captureGcInfo(config.captureGcInfo())
                    .mbeanGaugeNotFoundDelaySeconds(config.mbeanGaugeNotFoundDelaySeconds())
                    .internalQueryTimeoutSeconds(config.internalQueryTimeoutSeconds())
                    .version(config.version())
                    .build();
        }

        private AdvancedConfig toConfig() {
            return ImmutableAdvancedConfig.builder()
                    .metricWrapperMethods(metricWrapperMethods())
                    .immediatePartialStoreThresholdSeconds(immediatePartialStoreThresholdSeconds())
                    .maxTraceEntriesPerTransaction(maxTraceEntriesPerTransaction())
                    .maxStackTraceSamplesPerTransaction(maxStackTraceSamplesPerTransaction())
                    .captureThreadInfo(captureThreadInfo())
                    .captureGcInfo(captureGcInfo())
                    .mbeanGaugeNotFoundDelaySeconds(mbeanGaugeNotFoundDelaySeconds())
                    .internalQueryTimeoutSeconds(internalQueryTimeoutSeconds())
                    .build();
        }
    }

    @Value.Immutable
    @Json.Marshaled
    @Json.Import(MarshalingRoutines.class)
    abstract static class PluginConfigDto {

        abstract boolean enabled();
        @Json.ForceEmpty
        abstract Map<String, PropertyValue> properties();
        abstract String version();

        private static PluginConfigDto fromConfig(PluginConfig config) {
            return ImmutablePluginConfigDto.builder()
                    .enabled(config.enabled())
                    .putAllProperties(config.properties())
                    .version(config.version())
                    .build();
        }

        private PluginConfig toConfig(String id) {
            return ImmutablePluginConfig.builder()
                    .id(id)
                    .enabled(enabled())
                    .putAllProperties(properties())
                    .build();
        }
    }

    @SuppressWarnings("serial")
    private static class CurrentPasswordIncorrectException extends Exception {}
}
