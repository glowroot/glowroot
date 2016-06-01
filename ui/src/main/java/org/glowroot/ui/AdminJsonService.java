/*
 * Copyright 2012-2016 the original author or authors.
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
import java.util.Map;

import javax.annotation.Nullable;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.base.Charsets;
import com.google.common.collect.ImmutableList;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.FullHttpResponse;
import org.apache.shiro.authc.credential.PasswordService;
import org.apache.shiro.subject.Subject;
import org.checkerframework.checker.nullness.qual.MonotonicNonNull;
import org.checkerframework.checker.nullness.qual.RequiresNonNull;
import org.immutables.value.Value;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.glowroot.common.util.ObjectMappers;
import org.glowroot.storage.config.FatStorageConfig;
import org.glowroot.storage.config.ImmutableFatStorageConfig;
import org.glowroot.storage.config.ImmutableServerStorageConfig;
import org.glowroot.storage.config.ImmutableSmtpConfig;
import org.glowroot.storage.config.ImmutableUserConfig;
import org.glowroot.storage.config.ImmutableWebConfig;
import org.glowroot.storage.config.ServerStorageConfig;
import org.glowroot.storage.config.SmtpConfig;
import org.glowroot.storage.config.UserConfig;
import org.glowroot.storage.config.WebConfig;
import org.glowroot.storage.repo.AggregateRepository;
import org.glowroot.storage.repo.ConfigRepository;
import org.glowroot.storage.repo.ConfigRepository.OptimisticLockException;
import org.glowroot.storage.repo.GaugeValueRepository;
import org.glowroot.storage.repo.RepoAdmin;
import org.glowroot.storage.repo.TraceRepository;
import org.glowroot.storage.repo.TransactionTypeRepository;
import org.glowroot.storage.repo.helper.AlertingService;
import org.glowroot.storage.util.Encryption;
import org.glowroot.storage.util.MailService;
import org.glowroot.ui.HttpServer.PortChangeFailedException;

import static com.google.common.base.Preconditions.checkNotNull;
import static io.netty.handler.codec.http.HttpResponseStatus.OK;
import static io.netty.handler.codec.http.HttpResponseStatus.PRECONDITION_FAILED;
import static io.netty.handler.codec.http.HttpVersion.HTTP_1_1;

@JsonService
class AdminJsonService {

    private static final Logger logger = LoggerFactory.getLogger(ConfigJsonService.class);
    private static final ObjectMapper mapper = ObjectMappers.create();

    private final boolean fat;
    private final ConfigRepository configRepository;
    private final RepoAdmin repoAdmin;
    private final MailService mailService;
    private final PasswordService passwordService;

    private final AggregateRepository aggregateRepository;
    private final TraceRepository traceRepository;
    private final TransactionTypeRepository transactionTypeRepository;
    private final GaugeValueRepository gaugeValueRepository;

    private volatile @MonotonicNonNull HttpServer httpServer;

    AdminJsonService(boolean fat, ConfigRepository configRepository, RepoAdmin repoAdmin,
            MailService mailService, PasswordService passwordService,
            AggregateRepository aggregateRepository, TraceRepository traceRepository,
            TransactionTypeRepository transactionTypeRepository,
            GaugeValueRepository gaugeValueRepository) {
        this.fat = fat;
        this.configRepository = configRepository;
        this.repoAdmin = repoAdmin;
        this.mailService = mailService;
        this.passwordService = passwordService;
        this.aggregateRepository = aggregateRepository;
        this.traceRepository = traceRepository;
        this.transactionTypeRepository = transactionTypeRepository;
        this.gaugeValueRepository = gaugeValueRepository;
    }

    void setHttpServer(HttpServer httpServer) {
        this.httpServer = httpServer;
    }

    // all users have permission to change their own password
    @POST(path = "/backend/change-password", permission = "")
    String changePassword(@BindRequest ChangePassword changePassword, @BindSubject Subject subject)
            throws Exception {
        UserConfig userConfig = configRepository.getUserConfig((String) subject.getPrincipal());
        checkNotNull(userConfig, "user no longer exists");
        if (!passwordService.passwordsMatch(changePassword.currentPassword(),
                userConfig.passwordHash())) {
            return "{\"currentPasswordIncorrect\":true}";
        }
        ImmutableUserConfig updatedUserConfig = ImmutableUserConfig.builder().copyFrom(userConfig)
                .passwordHash(passwordService.encryptPassword(changePassword.newPassword()))
                .build();
        configRepository.updateUserConfig(updatedUserConfig, userConfig.version());
        return "";
    }

    @GET(path = "/backend/admin/web", permission = "admin:view:web")
    String getWebConfig() throws Exception {
        // this code cannot be reached when httpServer is null
        checkNotNull(httpServer);
        return getWebConfig(false);
    }

    @GET(path = "/backend/admin/storage", permission = "admin:view:storage")
    String getStorageConfig() throws Exception {
        if (fat) {
            FatStorageConfig config = configRepository.getFatStorageConfig();
            return mapper.writeValueAsString(FatStorageConfigDto.create(config));
        } else {
            ServerStorageConfig config = configRepository.getServerStorageConfig();
            return mapper.writeValueAsString(ServerStorageConfigDto.create(config));
        }
    }

    @GET(path = "/backend/admin/smtp", permission = "admin:view:smtp")
    String getSmtpConfig() throws Exception {
        SmtpConfig config = configRepository.getSmtpConfig();
        String localServerName = InetAddress.getLocalHost().getHostName();
        return mapper.writeValueAsString(ImmutableSmtpConfigResponse.builder()
                .config(SmtpConfigDto.create(config))
                .localServerName(localServerName)
                .build());
    }

    @POST(path = "/backend/admin/web", permission = "admin:edit:web")
    Object updateWebConfig(@BindRequest WebConfigDto configDto) throws Exception {
        // this code cannot be reached when httpServer is null
        checkNotNull(httpServer);
        WebConfig priorConfig = configRepository.getWebConfig();
        WebConfig config = configDto.convert();
        try {
            configRepository.updateWebConfig(config, configDto.version());
        } catch (OptimisticLockException e) {
            throw new JsonServiceException(PRECONDITION_FAILED, e);
        }
        return onSuccessfulAccessUpdate(priorConfig, config);
    }

    @POST(path = "/backend/admin/storage", permission = "admin:edit:storage")
    String updateStorageConfig(@BindRequest String content) throws Exception {
        if (fat) {
            FatStorageConfigDto configDto =
                    mapper.readValue(content, ImmutableFatStorageConfigDto.class);
            try {
                configRepository.updateFatStorageConfig(configDto.convert(), configDto.version());
            } catch (OptimisticLockException e) {
                throw new JsonServiceException(PRECONDITION_FAILED, e);
            }
            repoAdmin.resizeIfNecessary();
        } else {
            ServerStorageConfigDto configDto =
                    mapper.readValue(content, ImmutableServerStorageConfigDto.class);
            try {
                configRepository.updateServerStorageConfig(configDto.convert(),
                        configDto.version());
            } catch (OptimisticLockException e) {
                throw new JsonServiceException(PRECONDITION_FAILED, e);
            }
            repoAdmin.resizeIfNecessary();
        }
        return getStorageConfig();
    }

    @POST(path = "/backend/admin/smtp", permission = "admin:edit:smtp")
    String updateSmtpConfig(@BindRequest SmtpConfigDto configDto) throws Exception {
        try {
            configRepository.updateSmtpConfig(configDto.convert(configRepository),
                    configDto.version());
        } catch (OptimisticLockException e) {
            throw new JsonServiceException(PRECONDITION_FAILED, e);
        }
        return getSmtpConfig();
    }

    @POST(path = "/backend/admin/send-test-email", permission = "admin:edit:smtp")
    void sendTestEmail(@BindRequest SmtpConfigDto configDto) throws Exception {
        String testEmailRecipient = configDto.testEmailRecipient();
        checkNotNull(testEmailRecipient);
        AlertingService.sendTestEmails(testEmailRecipient, configDto.convert(configRepository),
                configRepository, mailService);
    }

    @POST(path = "/backend/admin/delete-all-stored-data", permission = "admin:edit:storage")
    void deleteAllData() throws Exception {
        // TODO optimize by just deleting and re-creating h2 db
        traceRepository.deleteAll();
        aggregateRepository.deleteAll();
        transactionTypeRepository.deleteAll();
        gaugeValueRepository.deleteAll();
        repoAdmin.defrag();
    }

    @POST(path = "/backend/admin/defrag-data", permission = "admin:edit:storage")
    void defragData() throws Exception {
        repoAdmin.defrag();
    }

    @RequiresNonNull("httpServer")
    private Object onSuccessfulAccessUpdate(WebConfig priorConfig, WebConfig config)
            throws Exception {
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
        String responseText = getWebConfig(portChangedFailed);
        ByteBuf responseContent = Unpooled.copiedBuffer(responseText, Charsets.ISO_8859_1);
        FullHttpResponse response = new DefaultFullHttpResponse(HTTP_1_1, OK, responseContent);
        if (portChangedSucceeded) {
            response.headers().set("Glowroot-Port-Changed", "true");
        }
        return response;
    }

    @RequiresNonNull("httpServer")
    private String getWebConfig(boolean portChangeFailed) throws Exception {
        WebConfig config = configRepository.getWebConfig();
        WebConfigDto configDto = ImmutableWebConfigDto.builder()
                .port(config.port())
                .sessionTimeoutMinutes(config.sessionTimeoutMinutes())
                .version(config.version())
                .build();
        return mapper.writeValueAsString(ImmutableWebConfigResponse.builder()
                .config(configDto)
                .activePort(httpServer.getPort())
                .portChangeFailed(portChangeFailed)
                .build());
    }

    @Value.Immutable
    interface ChangePassword {
        String currentPassword();
        String newPassword();
    }

    @Value.Immutable
    interface WebConfigResponse {
        WebConfigDto config();
        int activePort();
        boolean portChangeFailed();
    }

    @Value.Immutable
    interface SmtpConfigResponse {
        SmtpConfigDto config();
        String localServerName();
    }

    @Value.Immutable
    abstract static class WebConfigDto {

        abstract int port();
        abstract int sessionTimeoutMinutes();
        abstract String version();

        private WebConfig convert() throws Exception {
            return ImmutableWebConfig.builder()
                    .port(port())
                    .sessionTimeoutMinutes(sessionTimeoutMinutes())
                    .build();
        }
    }

    @Value.Immutable
    abstract static class FatStorageConfigDto {

        abstract ImmutableList<Integer> rollupExpirationHours();
        abstract int traceExpirationHours();
        abstract ImmutableList<Integer> rollupCappedDatabaseSizesMb();
        abstract int traceCappedDatabaseSizeMb();
        abstract String version();

        private FatStorageConfig convert() {
            return ImmutableFatStorageConfig.builder()
                    .rollupExpirationHours(rollupExpirationHours())
                    .traceExpirationHours(traceExpirationHours())
                    .rollupCappedDatabaseSizesMb(rollupCappedDatabaseSizesMb())
                    .traceCappedDatabaseSizeMb(traceCappedDatabaseSizeMb())
                    .build();
        }

        private static FatStorageConfigDto create(FatStorageConfig config) {
            return ImmutableFatStorageConfigDto.builder()
                    .addAllRollupExpirationHours(config.rollupExpirationHours())
                    .traceExpirationHours(config.traceExpirationHours())
                    .addAllRollupCappedDatabaseSizesMb(config.rollupCappedDatabaseSizesMb())
                    .traceCappedDatabaseSizeMb(config.traceCappedDatabaseSizeMb())
                    .version(config.version())
                    .build();
        }
    }

    @Value.Immutable
    abstract static class ServerStorageConfigDto {

        abstract ImmutableList<Integer> rollupExpirationHours();
        abstract int traceExpirationHours();
        abstract String version();

        private ServerStorageConfig convert() {
            return ImmutableServerStorageConfig.builder()
                    .rollupExpirationHours(rollupExpirationHours())
                    .traceExpirationHours(traceExpirationHours())
                    .build();
        }

        private static ServerStorageConfigDto create(ServerStorageConfig config) {
            return ImmutableServerStorageConfigDto.builder()
                    .addAllRollupExpirationHours(config.rollupExpirationHours())
                    .traceExpirationHours(config.traceExpirationHours())
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
        // only used in request
        @Value.Default
        String newPassword() {
            return "";
        }
        // only used in request
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
}
