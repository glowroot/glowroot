/*
 * Copyright 2012-2018 the original author or authors.
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

import java.io.File;
import java.io.IOException;
import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.common.base.Splitter;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;
import com.google.common.collect.Ordering;
import com.google.common.io.CharStreams;
import com.google.common.net.MediaType;
import com.google.common.primitives.Longs;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.ssl.SslContextBuilder;
import org.checkerframework.checker.nullness.qual.MonotonicNonNull;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.checkerframework.checker.nullness.qual.RequiresNonNull;
import org.immutables.value.Value;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.glowroot.common.live.LiveAggregateRepository;
import org.glowroot.common.util.ObjectMappers;
import org.glowroot.common2.config.CentralAdminGeneralConfig;
import org.glowroot.common2.config.CentralStorageConfig;
import org.glowroot.common2.config.CentralWebConfig;
import org.glowroot.common2.config.EmbeddedAdminGeneralConfig;
import org.glowroot.common2.config.EmbeddedStorageConfig;
import org.glowroot.common2.config.EmbeddedWebConfig;
import org.glowroot.common2.config.HealthchecksIoConfig;
import org.glowroot.common2.config.HttpProxyConfig;
import org.glowroot.common2.config.ImmutableCentralAdminGeneralConfig;
import org.glowroot.common2.config.ImmutableCentralStorageConfig;
import org.glowroot.common2.config.ImmutableCentralWebConfig;
import org.glowroot.common2.config.ImmutableEmbeddedAdminGeneralConfig;
import org.glowroot.common2.config.ImmutableEmbeddedStorageConfig;
import org.glowroot.common2.config.ImmutableEmbeddedWebConfig;
import org.glowroot.common2.config.ImmutableHealthchecksIoConfig;
import org.glowroot.common2.config.ImmutableHttpProxyConfig;
import org.glowroot.common2.config.ImmutableLdapConfig;
import org.glowroot.common2.config.ImmutablePagerDutyConfig;
import org.glowroot.common2.config.ImmutablePagerDutyIntegrationKey;
import org.glowroot.common2.config.ImmutableSlackConfig;
import org.glowroot.common2.config.ImmutableSlackWebhook;
import org.glowroot.common2.config.ImmutableSmtpConfig;
import org.glowroot.common2.config.ImmutableUserConfig;
import org.glowroot.common2.config.LdapConfig;
import org.glowroot.common2.config.PagerDutyConfig;
import org.glowroot.common2.config.RoleConfig;
import org.glowroot.common2.config.SlackConfig;
import org.glowroot.common2.config.SlackConfig.SlackWebhook;
import org.glowroot.common2.config.SmtpConfig;
import org.glowroot.common2.config.SmtpConfig.ConnectionSecurity;
import org.glowroot.common2.config.UserConfig;
import org.glowroot.common2.repo.ConfigRepository;
import org.glowroot.common2.repo.ConfigRepository.DuplicatePagerDutyIntegrationKeyDisplayException;
import org.glowroot.common2.repo.ConfigRepository.DuplicatePagerDutyIntegrationKeyException;
import org.glowroot.common2.repo.ConfigRepository.DuplicateSlackWebhookDisplayException;
import org.glowroot.common2.repo.ConfigRepository.DuplicateSlackWebhookUrlException;
import org.glowroot.common2.repo.ConfigRepository.OptimisticLockException;
import org.glowroot.common2.repo.RepoAdmin;
import org.glowroot.common2.repo.RepoAdmin.H2Table;
import org.glowroot.common2.repo.util.AlertingService;
import org.glowroot.common2.repo.util.Encryption;
import org.glowroot.common2.repo.util.HttpClient;
import org.glowroot.common2.repo.util.IdGenerator;
import org.glowroot.common2.repo.util.LazySecretKey.SymmetricEncryptionKeyMissingException;
import org.glowroot.common2.repo.util.MailService;
import org.glowroot.ui.CommonHandler.CommonResponse;
import org.glowroot.ui.HttpServer.PortChangeFailedException;
import org.glowroot.ui.HttpSessionManager.Authentication;
import org.glowroot.ui.LdapAuthentication.AuthenticationException;

import static com.google.common.base.Preconditions.checkNotNull;
import static io.netty.handler.codec.http.HttpResponseStatus.BAD_REQUEST;
import static io.netty.handler.codec.http.HttpResponseStatus.OK;
import static io.netty.handler.codec.http.HttpResponseStatus.PRECONDITION_FAILED;

@JsonService
class AdminJsonService {

    private static final Logger logger = LoggerFactory.getLogger(ConfigJsonService.class);
    private static final ObjectMapper mapper = ObjectMappers.create();

    private static final Ordering<H2Table> orderingByBytesDesc = new Ordering<H2Table>() {
        @Override
        public int compare(H2Table left, H2Table right) {
            return Longs.compare(right.bytes(), left.bytes());
        }
    };

    private final boolean central;
    private final boolean offlineViewer;
    private final boolean webPortReadOnly;
    private final List<File> confDirs;
    private final ConfigRepository configRepository;
    private final RepoAdmin repoAdmin;
    private final LiveAggregateRepository liveAggregateRepository;
    private final MailService mailService;
    private final HttpClient httpClient;

    // null when running in servlet container
    private volatile @MonotonicNonNull HttpServer httpServer;

    AdminJsonService(boolean central, boolean offlineViewer, boolean webPortReadOnly,
            List<File> confDirs, ConfigRepository configRepository,
            RepoAdmin repoAdmin, LiveAggregateRepository liveAggregateRepository,
            MailService mailService, HttpClient httpClient) {
        this.central = central;
        this.offlineViewer = offlineViewer;
        this.webPortReadOnly = webPortReadOnly;
        this.confDirs = confDirs;
        this.configRepository = configRepository;
        this.repoAdmin = repoAdmin;
        this.liveAggregateRepository = liveAggregateRepository;
        this.mailService = mailService;
        this.httpClient = httpClient;
    }

    void setHttpServer(HttpServer httpServer) {
        this.httpServer = httpServer;
    }

    // all users have permission to change their own password
    @POST(path = "/backend/change-password", permission = "")
    String changePassword(@BindRequest ChangePassword changePassword,
            @BindAuthentication Authentication authentication) throws Exception {
        if (authentication.anonymous()) {
            throw new JsonServiceException(BAD_REQUEST, "cannot change anonymous password");
        }
        UserConfig userConfig = configRepository
                .getUserConfigCaseInsensitive(authentication.caseAmbiguousUsername());
        checkNotNull(userConfig, "user no longer exists");
        if (!PasswordHash.validatePassword(changePassword.currentPassword(),
                userConfig.passwordHash())) {
            return "{\"currentPasswordIncorrect\":true}";
        }
        ImmutableUserConfig updatedUserConfig = ImmutableUserConfig.builder().copyFrom(userConfig)
                .passwordHash(PasswordHash.createHash(changePassword.newPassword()))
                .build();
        configRepository.updateUserConfig(updatedUserConfig, userConfig.version());
        return "";
    }

    @GET(path = "/backend/admin/general", permission = "admin:view:general")
    String getGeneralConfig() throws Exception {
        if (central) {
            return getCentralAdminGeneralConfig();
        } else {
            return getEmbeddedAdminGeneralConfig();
        }
    }

    @GET(path = "/backend/admin/web", permission = "admin:view:web")
    String getWebConfig() throws Exception {
        if (central) {
            return getCentralWebConfig();
        } else {
            return getEmbeddedWebConfig(false);
        }
    }

    @GET(path = "/backend/admin/storage", permission = "admin:view:storage")
    String getStorageConfig() throws Exception {
        if (central) {
            CentralStorageConfig config = configRepository.getCentralStorageConfig();
            return mapper.writeValueAsString(CentralStorageConfigDto.create(config));
        } else {
            EmbeddedStorageConfig config = configRepository.getEmbeddedStorageConfig();
            return mapper.writeValueAsString(EmbeddedStorageConfigDto.create(config));
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

    @GET(path = "/backend/admin/http-proxy", permission = "admin:view:httpProxy")
    String getHttpProxyConfig() throws Exception {
        HttpProxyConfig config = configRepository.getHttpProxyConfig();
        return mapper.writeValueAsString(HttpProxyConfigDto.create(config));
    }

    @GET(path = "/backend/admin/ldap", permission = "admin:view:ldap")
    String getLdapConfig() throws Exception {
        List<String> allGlowrootRoles = Lists.newArrayList();
        for (RoleConfig roleConfig : configRepository.getRoleConfigs()) {
            allGlowrootRoles.add(roleConfig.name());
        }
        allGlowrootRoles = Ordering.natural().sortedCopy(allGlowrootRoles);
        return mapper.writeValueAsString(ImmutableLdapConfigResponse.builder()
                .config(LdapConfigDto.create(configRepository.getLdapConfig()))
                .allGlowrootRoles(allGlowrootRoles)
                .build());
    }

    @GET(path = "/backend/admin/pager-duty", permission = "admin:view:pagerDuty")
    String getPagerDutyConfig() throws Exception {
        PagerDutyConfig config = configRepository.getPagerDutyConfig();
        return mapper.writeValueAsString(PagerDutyConfigDto.create(config));
    }

    @GET(path = "/backend/admin/slack", permission = "admin:view:slack")
    String getSlackConfig() throws Exception {
        SlackConfig config = configRepository.getSlackConfig();
        return mapper.writeValueAsString(SlackConfigDto.create(config));
    }

    @GET(path = "/backend/admin/healthchecks-io", permission = "admin:view:healthchecksIo")
    String getHealthchecksIoConfig() throws Exception {
        HealthchecksIoConfig config = configRepository.getHealthchecksIoConfig();
        return mapper.writeValueAsString(HealthchecksIoConfigDto.create(config));
    }

    @GET(path = "/backend/admin/json", permission = "admin:view")
    String getAllAdmin() throws Exception {
        Object configDto;
        if (central) {
            configDto =
                    AllCentralAdminConfigDto.create(configRepository.getAllCentralAdminConfig());
        } else {
            configDto =
                    AllEmbeddedAdminConfigDto.create(configRepository.getAllEmbeddedAdminConfig());
        }
        ObjectNode configRootNode = mapper.valueToTree(configDto);
        ((ObjectNode) checkNotNull(configRootNode.get("smtp"))).remove("encryptedPassword");
        ((ObjectNode) checkNotNull(configRootNode.get("httpProxy"))).remove("encryptedPassword");
        ((ObjectNode) checkNotNull(configRootNode.get("ldap"))).remove("encryptedPassword");
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

    @POST(path = "/backend/admin/general", permission = "admin:edit:general")
    String updateGeneralConfig(@BindRequest String content) throws Exception {
        if (central) {
            CentralAdminGeneralConfigDto configDto =
                    mapper.readValue(content, ImmutableCentralAdminGeneralConfigDto.class);
            CentralAdminGeneralConfig config = configDto.convert();
            try {
                configRepository.updateCentralAdminGeneralConfig(config, configDto.version());
            } catch (OptimisticLockException e) {
                throw new JsonServiceException(PRECONDITION_FAILED, e);
            }
            return getCentralAdminGeneralConfig();
        } else {
            EmbeddedAdminGeneralConfigDto configDto =
                    mapper.readValue(content, ImmutableEmbeddedAdminGeneralConfigDto.class);
            EmbeddedAdminGeneralConfig config = configDto.convert();
            try {
                configRepository.updateEmbeddedAdminGeneralConfig(config, configDto.version());
            } catch (OptimisticLockException e) {
                throw new JsonServiceException(PRECONDITION_FAILED, e);
            }
            return getEmbeddedAdminGeneralConfig();
        }
    }

    @POST(path = "/backend/admin/web", permission = "admin:edit:web")
    Object updateWebConfig(@BindRequest String content) throws Exception {
        if (central) {
            CentralWebConfigDto configDto =
                    mapper.readValue(content, ImmutableCentralWebConfigDto.class);
            CentralWebConfig config = configDto.convert();
            try {
                configRepository.updateCentralWebConfig(config, configDto.version());
            } catch (OptimisticLockException e) {
                throw new JsonServiceException(PRECONDITION_FAILED, e);
            }
            return getCentralWebConfig();
        } else {
            checkNotNull(httpServer);
            EmbeddedWebConfigDto configDto =
                    mapper.readValue(content, ImmutableEmbeddedWebConfigDto.class);
            EmbeddedWebConfig config = configDto.convert();
            if (config.https() && !httpServer.getHttps()) {
                // validate certificate and private key exist and are valid
                File certificateFile = getConfFile("ui-cert.pem");
                if (certificateFile == null) {
                    return "{\"httpsRequiredFilesDoNotExist\":true}";
                }
                File privateKeyFile = getConfFile("ui-key.pem");
                if (privateKeyFile == null) {
                    return "{\"httpsRequiredFilesDoNotExist\":true}";
                }
                try {
                    SslContextBuilder.forServer(certificateFile, privateKeyFile);
                } catch (Exception e) {
                    logger.debug(e.getMessage(), e);
                    StringBuilder sb = new StringBuilder();
                    JsonGenerator jg =
                            mapper.getFactory().createGenerator(CharStreams.asWriter(sb));
                    try {
                        jg.writeStartObject();
                        jg.writeStringField("httpsValidationError", e.getMessage());
                        jg.writeEndObject();
                    } finally {
                        jg.close();
                    }
                    return sb.toString();
                }
            }
            if (webPortReadOnly && config.port() != checkNotNull(httpServer.getPort())) {
                throw new JsonServiceException(BAD_REQUEST,
                        "cannot change port when using -Dglowroot.agent.port");
            }
            try {
                configRepository.updateEmbeddedWebConfig(config, configDto.version());
            } catch (OptimisticLockException e) {
                throw new JsonServiceException(PRECONDITION_FAILED, e);
            }
            return onSuccessfulEmbeddedWebUpdate(config);
        }
    }

    @POST(path = "/backend/admin/storage", permission = "admin:edit:storage")
    String updateStorageConfig(@BindRequest String content) throws Exception {
        if (central) {
            CentralStorageConfigDto configDto =
                    mapper.readValue(content, ImmutableCentralStorageConfigDto.class);
            try {
                configRepository.updateCentralStorageConfig(configDto.convert(),
                        configDto.version());
            } catch (OptimisticLockException e) {
                throw new JsonServiceException(PRECONDITION_FAILED, e);
            }
        } else {
            EmbeddedStorageConfigDto configDto =
                    mapper.readValue(content, ImmutableEmbeddedStorageConfigDto.class);
            try {
                configRepository.updateEmbeddedStorageConfig(configDto.convert(),
                        configDto.version());
            } catch (OptimisticLockException e) {
                throw new JsonServiceException(PRECONDITION_FAILED, e);
            }
            repoAdmin.resizeIfNeeded();
        }
        return getStorageConfig();
    }

    @POST(path = "/backend/admin/smtp", permission = "admin:edit:smtp")
    String updateSmtpConfig(@BindRequest SmtpConfigDto configDto) throws Exception {
        try {
            configRepository.updateSmtpConfig(configDto.convert(configRepository),
                    configDto.version());
        } catch (SymmetricEncryptionKeyMissingException e) {
            return "{\"symmetricEncryptionKeyMissing\":true}";
        } catch (OptimisticLockException e) {
            throw new JsonServiceException(PRECONDITION_FAILED, e);
        }
        return getSmtpConfig();
    }

    @POST(path = "/backend/admin/http-proxy", permission = "admin:edit:httpProxy")
    String updateHttpProxyConfig(@BindRequest HttpProxyConfigDto configDto) throws Exception {
        try {
            configRepository.updateHttpProxyConfig(configDto.convert(configRepository),
                    configDto.version());
        } catch (SymmetricEncryptionKeyMissingException e) {
            return "{\"symmetricEncryptionKeyMissing\":true}";
        } catch (OptimisticLockException e) {
            throw new JsonServiceException(PRECONDITION_FAILED, e);
        }
        return getHttpProxyConfig();
    }

    @POST(path = "/backend/admin/ldap", permission = "admin:edit:ldap")
    String updateLdapConfig(@BindRequest LdapConfigDto configDto) throws Exception {
        try {
            configRepository.updateLdapConfig(configDto.convert(configRepository),
                    configDto.version());
        } catch (SymmetricEncryptionKeyMissingException e) {
            return "{\"symmetricEncryptionKeyMissing\":true}";
        } catch (OptimisticLockException e) {
            throw new JsonServiceException(PRECONDITION_FAILED, e);
        }
        return getLdapConfig();
    }

    @POST(path = "/backend/admin/pager-duty", permission = "admin:edit:pagerDuty")
    String updatePagerDutyConfig(@BindRequest PagerDutyConfigDto configDto) throws Exception {
        try {
            configRepository.updatePagerDutyConfig(configDto.convert(), configDto.version());
        } catch (DuplicatePagerDutyIntegrationKeyException e) {
            return "{\"duplicateIntegrationKey\":true}";
        } catch (DuplicatePagerDutyIntegrationKeyDisplayException e) {
            return "{\"duplicateIntegrationKeyDisplay\":true}";
        } catch (OptimisticLockException e) {
            throw new JsonServiceException(PRECONDITION_FAILED, e);
        }
        return getPagerDutyConfig();
    }

    @POST(path = "/backend/admin/slack", permission = "admin:edit:slack")
    String updateSlackConfig(@BindRequest SlackConfigDto configDto) throws Exception {
        try {
            configRepository.updateSlackConfig(configDto.convert(), configDto.version());
        } catch (DuplicateSlackWebhookUrlException e) {
            return "{\"duplicateWebhookUrl\":true}";
        } catch (DuplicateSlackWebhookDisplayException e) {
            return "{\"duplicateWebhookDisplay\":true}";
        } catch (OptimisticLockException e) {
            throw new JsonServiceException(PRECONDITION_FAILED, e);
        }
        return getSlackConfig();
    }

    @POST(path = "/backend/admin/healthchecks-io", permission = "admin:edit:healthchecksIo")
    String updateHealthchecksIoConfig(@BindRequest HealthchecksIoConfigDto configDto)
            throws Exception {
        try {
            configRepository.updateHealthchecksIoConfig(configDto.convert(), configDto.version());
        } catch (OptimisticLockException e) {
            throw new JsonServiceException(PRECONDITION_FAILED, e);
        }
        return getHealthchecksIoConfig();
    }

    @POST(path = "/backend/admin/json", permission = "admin:edit")
    String updateAllAdmin(@BindRequest String content) throws Exception {
        JsonNode rootNode = mapper.readTree(content);
        updatePassword((ObjectNode) rootNode.get("smtp"),
                configRepository.getSmtpConfig().encryptedPassword());
        updatePassword((ObjectNode) rootNode.get("httpProxy"),
                configRepository.getHttpProxyConfig().encryptedPassword());
        updatePassword((ObjectNode) rootNode.get("ldap"),
                configRepository.getLdapConfig().encryptedPassword());
        if (central) {
            AllCentralAdminConfigDto config =
                    mapper.treeToValue(rootNode, ImmutableAllCentralAdminConfigDto.class);
            configRepository.updateAllCentralAdminConfig(config.toConfig(), config.version());
        } else {
            AllEmbeddedAdminConfigDto config =
                    mapper.treeToValue(rootNode, ImmutableAllEmbeddedAdminConfigDto.class);
            configRepository.updateAllEmbeddedAdminConfig(config.toConfig(), config.version());
        }
        return getAllAdmin();
    }

    @POST(path = "/backend/admin/send-test-email", permission = "admin:edit:smtp")
    String sendTestEmail(@BindRequest SmtpConfigDto configDto) throws IOException {
        // capturing newPlainPassword separately so that newPassword doesn't go through
        // encryption/decryption which has possibility of throwing
        // org.glowroot.common.repo.util.LazySecretKey.SymmetricEncryptionKeyMissingException
        SmtpConfigDto configDtoWithoutNewPassword;
        String passwordOverride;
        String newPassword = configDto.newPassword();
        if (newPassword.isEmpty()) {
            configDtoWithoutNewPassword = configDto;
            passwordOverride = null;
        } else {
            configDtoWithoutNewPassword = ImmutableSmtpConfigDto.builder()
                    .copyFrom(configDto)
                    .newPassword("")
                    .build();
            passwordOverride = newPassword;
        }
        String testEmailRecipient = configDtoWithoutNewPassword.testEmailRecipient();
        checkNotNull(testEmailRecipient);
        List<String> emailAddresses =
                Splitter.on(',').trimResults().splitToList(testEmailRecipient);
        try {
            String centralDisplay;
            String agentDisplay;
            if (central) {
                centralDisplay =
                        configRepository.getCentralAdminGeneralConfig().centralDisplayName();
                agentDisplay = "";
            } else {
                centralDisplay = "";
                agentDisplay = configRepository.getEmbeddedAdminGeneralConfig()
                        .agentDisplayNameOrDefault();
            }
            String subject = "Test email";
            AlertingService.sendEmail(centralDisplay, agentDisplay, subject, emailAddresses, "",
                    configDtoWithoutNewPassword.convert(configRepository), passwordOverride,
                    configRepository.getLazySecretKey(), mailService);
        } catch (Exception e) {
            logger.debug(e.getMessage(), e);
            return createErrorResponse(e);
        }
        return "{}";
    }

    @POST(path = "/backend/admin/test-http-proxy", permission = "admin:edit:httpProxy")
    String testHttpProxy(@BindRequest HttpProxyConfigDto configDto) throws IOException {
        // capturing newPlainPassword separately so that newPassword doesn't go through
        // encryption/decryption which has possibility of throwing
        // org.glowroot.common.repo.util.LazySecretKey.SymmetricEncryptionKeyMissingException
        HttpProxyConfigDto configDtoWithoutNewPassword;
        String passwordOverride;
        String newPassword = configDto.newPassword();
        if (newPassword.isEmpty()) {
            configDtoWithoutNewPassword = configDto;
            passwordOverride = null;
        } else {
            configDtoWithoutNewPassword = ImmutableHttpProxyConfigDto.builder()
                    .copyFrom(configDto)
                    .newPassword("")
                    .build();
            passwordOverride = newPassword;
        }
        String testUrl = configDtoWithoutNewPassword.testUrl();
        checkNotNull(testUrl);
        URI uri;
        try {
            uri = new URI(testUrl);
        } catch (URISyntaxException e) {
            logger.debug(e.getMessage(), e);
            return createErrorResponse(e);
        }
        if (uri.getScheme() == null) {
            return createErrorResponse("Invalid url, missing protocol (e.g. http://)");
        }
        if (uri.getHost() == null) {
            return createErrorResponse("Invalid url, missing host");
        }
        String responseContent;
        try {
            responseContent = httpClient.getWithHttpProxyConfigOverride(testUrl,
                    configDtoWithoutNewPassword.convert(configRepository), passwordOverride);
        } catch (Exception e) {
            logger.debug(e.getMessage(), e);
            return createErrorResponse(e);
        }
        StringBuilder sb = new StringBuilder();
        JsonGenerator jg = mapper.getFactory().createGenerator(CharStreams.asWriter(sb));
        try {
            jg.writeStartObject();
            jg.writeStringField("content", responseContent);
            jg.writeEndObject();
        } finally {
            jg.close();
        }
        return sb.toString();
    }

    @POST(path = "/backend/admin/test-ldap", permission = "admin:edit:ldap")
    String testLdap(@BindRequest LdapConfigDto configDto) throws Exception {
        // capturing newPlainPassword separately so that newPassword doesn't go through
        // encryption/decryption which has possibility of throwing
        // org.glowroot.common.repo.util.LazySecretKey.SymmetricEncryptionKeyMissingException
        LdapConfigDto configDtoWithoutNewPassword;
        String passwordOverride;
        String newPassword = configDto.newPassword();
        if (newPassword.isEmpty()) {
            configDtoWithoutNewPassword = configDto;
            passwordOverride = null;
        } else {
            configDtoWithoutNewPassword = ImmutableLdapConfigDto.builder()
                    .copyFrom(configDto)
                    .newPassword("")
                    .build();
            passwordOverride = newPassword;
        }
        LdapConfig config = configDtoWithoutNewPassword.convert(configRepository);
        String authTestUsername = checkNotNull(configDtoWithoutNewPassword.authTestUsername());
        String authTestPassword = checkNotNull(configDtoWithoutNewPassword.authTestPassword());
        Set<String> ldapGroupDns;
        try {
            ldapGroupDns = LdapAuthentication.authenticateAndGetLdapGroupDns(authTestUsername,
                    authTestPassword, config, passwordOverride,
                    configRepository.getLazySecretKey());
        } catch (AuthenticationException e) {
            logger.debug(e.getMessage(), e);
            return createErrorResponse(e);
        }
        Set<String> glowrootRoles = LdapAuthentication.getGlowrootRoles(ldapGroupDns, config);
        StringBuilder sb = new StringBuilder();
        JsonGenerator jg = mapper.getFactory().createGenerator(CharStreams.asWriter(sb));
        try {
            jg.writeStartObject();
            jg.writeObjectField("ldapGroupDns", ldapGroupDns);
            jg.writeObjectField("glowrootRoles", glowrootRoles);
            jg.writeEndObject();
        } finally {
            jg.close();
        }
        return sb.toString();
    }

    @POST(path = "/backend/admin/defrag-h2-data", permission = "")
    void defragH2Data(@BindAuthentication Authentication authentication) throws Exception {
        if (!offlineViewer && !authentication.isAdminPermitted("admin:edit:storage")) {
            throw new JsonServiceException(HttpResponseStatus.FORBIDDEN);
        }
        repoAdmin.defragH2Data();
    }

    @POST(path = "/backend/admin/compact-h2-data", permission = "")
    void compactH2Data(@BindAuthentication Authentication authentication) throws Exception {
        if (!offlineViewer && !authentication.isAdminPermitted("admin:edit:storage")) {
            throw new JsonServiceException(HttpResponseStatus.FORBIDDEN);
        }
        repoAdmin.compactH2Data();
    }

    @POST(path = "/backend/admin/analyze-h2-disk-space", permission = "")
    String analyzeH2DiskSpace(@BindAuthentication Authentication authentication) throws Exception {
        if (!offlineViewer && !authentication.isAdminPermitted("admin:edit:storage")) {
            throw new JsonServiceException(HttpResponseStatus.FORBIDDEN);
        }
        long h2DataFileSize = repoAdmin.getH2DataFileSize();
        List<H2Table> tables = repoAdmin.analyzeH2DiskSpace();
        StringBuilder sb = new StringBuilder();
        JsonGenerator jg = mapper.getFactory().createGenerator(CharStreams.asWriter(sb));
        try {
            jg.writeStartObject();
            jg.writeNumberField("h2DataFileSize", h2DataFileSize);
            jg.writeObjectField("tables", orderingByBytesDesc.sortedCopy(tables));
            jg.writeEndObject();
        } finally {
            jg.close();
        }
        return sb.toString();
    }

    @POST(path = "/backend/admin/analyze-trace-counts", permission = "")
    String analyzeTraceCounts(@BindAuthentication Authentication authentication) throws Exception {
        if (!offlineViewer && !authentication.isAdminPermitted("admin:edit:storage")) {
            throw new JsonServiceException(HttpResponseStatus.FORBIDDEN);
        }
        return mapper.writeValueAsString(repoAdmin.analyzeTraceCounts());
    }

    @POST(path = "/backend/admin/delete-all-stored-data", permission = "admin:edit:storage")
    void deleteAllData() throws Exception {
        repoAdmin.deleteAllData();
        liveAggregateRepository.clearInMemoryData();
    }

    @POST(path = "/backend/admin/update-cassandra-twcs-window-sizes",
            permission = "admin:edit:storage")
    String updateCassandraTwcsWindowSizes() throws Exception {
        return Integer.toString(repoAdmin.updateCassandraTwcsWindowSizes());
    }

    @GET(path = "/backend/admin/cassandra-write-totals", permission = "admin:view:storage")
    String getCassandraWriteTotals(@BindRequest CassandraWriteTotalsRequest request)
            throws JsonProcessingException {
        String tableName = request.tableName();
        String agentRollupId = request.agentRollupId();
        String transactionType = request.transactionType();
        int limit = request.limit();
        if (tableName == null) {
            return mapper.writeValueAsString(repoAdmin.getCassandraWriteTotalsPerTable(limit));
        } else if (agentRollupId == null) {
            return mapper.writeValueAsString(
                    repoAdmin.getCassandraWriteTotalsPerAgentRollup(tableName, limit));
        } else if (transactionType == null) {
            return mapper.writeValueAsString(repoAdmin
                    .getCassandraWriteTotalsPerTransactionType(tableName, agentRollupId, limit));
        } else {
            return mapper.writeValueAsString(repoAdmin.getCassandraWriteTotalsPerTransactionName(
                    tableName, agentRollupId, transactionType, limit));
        }
    }

    private @Nullable File getConfFile(String fileName) {
        for (File confDir : confDirs) {
            File confFile = new File(confDir, fileName);
            if (confFile.exists()) {
                return confFile;
            }
        }
        return null;
    }

    @RequiresNonNull("httpServer")
    private CommonResponse onSuccessfulEmbeddedWebUpdate(EmbeddedWebConfig config)
            throws Exception {
        boolean closeCurrentChannelAfterPortChange = false;
        boolean portChangeFailed = false;
        if (config.port() != checkNotNull(httpServer.getPort())) {
            try {
                httpServer.changePort(config.port());
                closeCurrentChannelAfterPortChange = true;
            } catch (PortChangeFailedException e) {
                logger.error(e.getMessage(), e);
                portChangeFailed = true;
            }
        }
        if (config.https() != httpServer.getHttps() && !portChangeFailed) {
            // only change protocol if port change did not fail, otherwise confusing to display
            // message that port change failed while at the same time redirecting user to HTTP/S
            httpServer.changeProtocol(config.https());
            closeCurrentChannelAfterPortChange = true;
        }
        String responseText = getEmbeddedWebConfig(portChangeFailed);
        CommonResponse response = new CommonResponse(OK, MediaType.JSON_UTF_8, responseText);
        if (closeCurrentChannelAfterPortChange) {
            response.setCloseConnectionAfterPortChange();
        }
        return response;
    }

    private String getEmbeddedAdminGeneralConfig() throws Exception {
        return mapper.writeValueAsString(ImmutableEmbeddedAdminGeneralConfigResponse.builder()
                .config(EmbeddedAdminGeneralConfigDto
                        .create(configRepository.getEmbeddedAdminGeneralConfig()))
                .defaultAgentDisplayName(EmbeddedAdminGeneralConfig.defaultAgentDisplayName())
                .build());
    }

    private String getCentralAdminGeneralConfig() throws Exception {
        return mapper.writeValueAsString(CentralAdminGeneralConfigDto
                .create(configRepository.getCentralAdminGeneralConfig()));
    }

    private String getEmbeddedWebConfig(boolean portChangeFailed) throws Exception {
        EmbeddedWebConfig config = configRepository.getEmbeddedWebConfig();
        ImmutableEmbeddedWebConfigResponse.Builder builder =
                ImmutableEmbeddedWebConfigResponse.builder()
                        .config(EmbeddedWebConfigDto.create(config))
                        .portReadOnly(webPortReadOnly)
                        .portChangeFailed(portChangeFailed);
        if (httpServer == null) {
            builder.activePort(config.port())
                    .activeBindAddress(config.bindAddress())
                    .activeHttps(config.https());
        } else {
            builder.activePort(checkNotNull(httpServer.getPort()))
                    .activeBindAddress(httpServer.getBindAddress())
                    .activeHttps(httpServer.getHttps());
        }
        for (File confDir : confDirs) {
            builder.addConfDirs(confDir.getAbsolutePath());
        }
        return mapper.writeValueAsString(builder.build());
    }

    private String getCentralWebConfig() throws Exception {
        return mapper.writeValueAsString(ImmutableCentralWebConfigResponse.builder()
                .config(CentralWebConfigDto.create(configRepository.getCentralWebConfig()))
                .build());
    }

    private void updatePassword(@Nullable ObjectNode objectNode, String existingEncryptedPassword)
            throws Exception {
        if (objectNode == null) {
            return;
        }
        JsonNode passwordNode = objectNode.get("password");
        if (passwordNode == null) {
            JsonNode encryptedPasswordNode = objectNode.get("encryptedPassword");
            if (encryptedPasswordNode == null) {
                objectNode.put("encryptedPassword", existingEncryptedPassword);
            }
        } else {
            objectNode.put("encryptedPassword", Encryption.encrypt(passwordNode.asText(),
                    configRepository.getLazySecretKey()));
        }
    }

    private static String createErrorResponse(Exception exception) throws IOException {
        String message = exception.getLocalizedMessage();
        String exceptionName = exception.getClass().getSimpleName();
        if (message == null) {
            return createErrorResponse(exceptionName);
        } else {
            return createErrorResponse(exceptionName + ": " + message);
        }
    }

    private static String createErrorResponse(@Nullable String message) throws IOException {
        StringBuilder sb = new StringBuilder();
        JsonGenerator jg = mapper.getFactory().createGenerator(CharStreams.asWriter(sb));
        try {
            jg.writeStartObject();
            jg.writeBooleanField("error", true);
            jg.writeStringField("message", message);
            jg.writeEndObject();
        } finally {
            jg.close();
        }
        return sb.toString();
    }

    @Value.Immutable
    interface ChangePassword {
        String currentPassword();
        String newPassword();
    }

    @Value.Immutable
    interface EmbeddedAdminGeneralConfigResponse {
        EmbeddedAdminGeneralConfigDto config();
        String defaultAgentDisplayName();
    }

    @Value.Immutable
    interface EmbeddedWebConfigResponse {
        EmbeddedWebConfigDto config();
        int activePort();
        String activeBindAddress();
        boolean activeHttps();
        boolean portReadOnly();
        boolean portChangeFailed();
        List<String> confDirs();
    }

    @Value.Immutable
    interface CentralWebConfigResponse {
        CentralWebConfigDto config();
    }

    @Value.Immutable
    interface SmtpConfigResponse {
        SmtpConfigDto config();
        String localServerName();
    }

    @Value.Immutable
    interface LdapConfigResponse {
        LdapConfigDto config();
        List<String> allGlowrootRoles();
    }

    @Value.Immutable
    interface CassandraWriteTotalsRequest {
        @Nullable
        String tableName();
        @Nullable
        String agentRollupId();
        @Nullable
        String transactionType();
        int limit();
    }

    @Value.Immutable
    abstract static class EmbeddedAdminGeneralConfigDto {

        abstract String agentDisplayName();
        abstract String version();

        private EmbeddedAdminGeneralConfig convert() {
            return ImmutableEmbeddedAdminGeneralConfig.builder()
                    .agentDisplayName(agentDisplayName())
                    .build();
        }

        private static EmbeddedAdminGeneralConfigDto create(EmbeddedAdminGeneralConfig config) {
            return ImmutableEmbeddedAdminGeneralConfigDto.builder()
                    .agentDisplayName(config.agentDisplayName())
                    .version(config.version())
                    .build();
        }
    }

    @Value.Immutable
    abstract static class CentralAdminGeneralConfigDto {

        abstract String centralDisplayName();
        abstract String version();

        private CentralAdminGeneralConfig convert() {
            return ImmutableCentralAdminGeneralConfig.builder()
                    .centralDisplayName(centralDisplayName())
                    .build();
        }

        private static CentralAdminGeneralConfigDto create(CentralAdminGeneralConfig config) {
            return ImmutableCentralAdminGeneralConfigDto.builder()
                    .centralDisplayName(config.centralDisplayName())
                    .version(config.version())
                    .build();
        }
    }

    @Value.Immutable
    abstract static class EmbeddedWebConfigDto {

        abstract int port();
        abstract String bindAddress();
        abstract boolean https();
        abstract String contextPath();
        abstract int sessionTimeoutMinutes();
        abstract String sessionCookieName();
        abstract String version();

        private EmbeddedWebConfig convert() {
            return ImmutableEmbeddedWebConfig.builder()
                    .port(port())
                    .bindAddress(bindAddress())
                    .https(https())
                    .contextPath(contextPath())
                    .sessionTimeoutMinutes(sessionTimeoutMinutes())
                    .sessionCookieName(sessionCookieName())
                    .build();
        }

        private static EmbeddedWebConfigDto create(EmbeddedWebConfig config) {
            return ImmutableEmbeddedWebConfigDto.builder()
                    .port(config.port())
                    .bindAddress(config.bindAddress())
                    .https(config.https())
                    .contextPath(config.contextPath())
                    .sessionTimeoutMinutes(config.sessionTimeoutMinutes())
                    .sessionCookieName(config.sessionCookieName())
                    .version(config.version())
                    .build();
        }
    }

    @Value.Immutable
    abstract static class CentralWebConfigDto {

        abstract int sessionTimeoutMinutes();
        abstract String sessionCookieName();
        abstract String version();

        private CentralWebConfig convert() {
            return ImmutableCentralWebConfig.builder()
                    .sessionTimeoutMinutes(sessionTimeoutMinutes())
                    .sessionCookieName(sessionCookieName())
                    .build();
        }

        private static CentralWebConfigDto create(CentralWebConfig config) {
            return ImmutableCentralWebConfigDto.builder()
                    .sessionTimeoutMinutes(config.sessionTimeoutMinutes())
                    .sessionCookieName(config.sessionCookieName())
                    .version(config.version())
                    .build();
        }
    }

    @Value.Immutable
    abstract static class EmbeddedStorageConfigDto {

        abstract ImmutableList<Integer> rollupExpirationHours();
        abstract int traceExpirationHours();
        abstract int fullQueryTextExpirationHours();
        abstract ImmutableList<Integer> rollupCappedDatabaseSizesMb();
        abstract int traceCappedDatabaseSizeMb();
        abstract String version();

        private EmbeddedStorageConfig convert() {
            return ImmutableEmbeddedStorageConfig.builder()
                    .rollupExpirationHours(rollupExpirationHours())
                    .traceExpirationHours(traceExpirationHours())
                    .fullQueryTextExpirationHours(fullQueryTextExpirationHours())
                    .rollupCappedDatabaseSizesMb(rollupCappedDatabaseSizesMb())
                    .traceCappedDatabaseSizeMb(traceCappedDatabaseSizeMb())
                    .build();
        }

        private static EmbeddedStorageConfigDto create(EmbeddedStorageConfig config) {
            return ImmutableEmbeddedStorageConfigDto.builder()
                    .addAllRollupExpirationHours(config.rollupExpirationHours())
                    .traceExpirationHours(config.traceExpirationHours())
                    .fullQueryTextExpirationHours(config.fullQueryTextExpirationHours())
                    .addAllRollupCappedDatabaseSizesMb(config.rollupCappedDatabaseSizesMb())
                    .traceCappedDatabaseSizeMb(config.traceCappedDatabaseSizeMb())
                    .version(config.version())
                    .build();
        }
    }

    @Value.Immutable
    abstract static class CentralStorageConfigDto {

        abstract ImmutableList<Integer> rollupExpirationHours();
        abstract ImmutableList<Integer> queryAndServiceCallRollupExpirationHours();
        abstract ImmutableList<Integer> profileRollupExpirationHours();
        abstract int traceExpirationHours();
        abstract String version();

        private CentralStorageConfig convert() {
            return ImmutableCentralStorageConfig.builder()
                    .rollupExpirationHours(rollupExpirationHours())
                    .queryAndServiceCallRollupExpirationHours(
                            queryAndServiceCallRollupExpirationHours())
                    .profileRollupExpirationHours(profileRollupExpirationHours())
                    .traceExpirationHours(traceExpirationHours())
                    .build();
        }

        private static CentralStorageConfigDto create(CentralStorageConfig config) {
            return ImmutableCentralStorageConfigDto.builder()
                    .addAllRollupExpirationHours(config.rollupExpirationHours())
                    .addAllQueryAndServiceCallRollupExpirationHours(
                            config.queryAndServiceCallRollupExpirationHours())
                    .addAllProfileRollupExpirationHours(config.profileRollupExpirationHours())
                    .traceExpirationHours(config.traceExpirationHours())
                    .version(config.version())
                    .build();
        }
    }

    @Value.Immutable
    abstract static class SmtpConfigDto {

        abstract String host();
        @JsonInclude
        abstract @Nullable Integer port();
        @JsonInclude
        abstract @Nullable ConnectionSecurity connectionSecurity();
        abstract String username();
        abstract boolean passwordExists();
        @Value.Default
        String newPassword() { // only used in request
            return "";
        }
        abstract Map<String, String> additionalProperties();
        abstract String fromEmailAddress();
        abstract String fromDisplayName();
        abstract @Nullable String testEmailRecipient(); // only used in request
        abstract String version();

        private SmtpConfig convert(ConfigRepository configRepository) throws Exception {
            ImmutableSmtpConfig.Builder builder = ImmutableSmtpConfig.builder()
                    .host(host())
                    .port(port())
                    .connectionSecurity(connectionSecurity())
                    .username(username())
                    .putAllAdditionalProperties(additionalProperties())
                    .fromEmailAddress(fromEmailAddress())
                    .fromDisplayName(fromDisplayName());
            if (!passwordExists()) {
                // clear password
                builder.encryptedPassword("");
            } else if (passwordExists() && !newPassword().isEmpty()) {
                // change password
                builder.encryptedPassword(
                        Encryption.encrypt(newPassword(), configRepository.getLazySecretKey()));
            } else {
                // keep existing password
                builder.encryptedPassword(configRepository.getSmtpConfig().encryptedPassword());
            }
            return builder.build();
        }

        private static SmtpConfigDto create(SmtpConfig config) {
            return ImmutableSmtpConfigDto.builder()
                    .host(config.host())
                    .port(config.port())
                    .connectionSecurity(config.connectionSecurity())
                    .username(config.username())
                    .passwordExists(!config.encryptedPassword().isEmpty())
                    .putAllAdditionalProperties(config.additionalProperties())
                    .fromEmailAddress(config.fromEmailAddress())
                    .fromDisplayName(config.fromDisplayName())
                    .version(config.version())
                    .build();
        }
    }

    @Value.Immutable
    abstract static class HttpProxyConfigDto {

        abstract String host();
        @JsonInclude
        abstract @Nullable Integer port();
        abstract String username();
        abstract boolean passwordExists();
        @Value.Default
        String newPassword() { // only used in request
            return "";
        }
        abstract @Nullable String testUrl(); // only used in request
        abstract String version();

        private HttpProxyConfig convert(ConfigRepository configRepository) throws Exception {
            ImmutableHttpProxyConfig.Builder builder = ImmutableHttpProxyConfig.builder()
                    .host(host())
                    .port(port())
                    .username(username());
            if (!passwordExists()) {
                // clear password
                builder.encryptedPassword("");
            } else if (passwordExists() && !newPassword().isEmpty()) {
                // change password
                builder.encryptedPassword(
                        Encryption.encrypt(newPassword(), configRepository.getLazySecretKey()));
            } else {
                // keep existing password
                builder.encryptedPassword(
                        configRepository.getHttpProxyConfig().encryptedPassword());
            }
            return builder.build();
        }

        private static HttpProxyConfigDto create(HttpProxyConfig config) {
            return ImmutableHttpProxyConfigDto.builder()
                    .host(config.host())
                    .port(config.port())
                    .username(config.username())
                    .passwordExists(!config.encryptedPassword().isEmpty())
                    .version(config.version())
                    .build();
        }
    }

    @Value.Immutable
    abstract static class LdapConfigDto {

        abstract String host();
        @JsonInclude
        abstract @Nullable Integer port();
        abstract boolean ssl();
        abstract String username();
        abstract boolean passwordExists();
        @Value.Default
        String newPassword() { // only used in request
            return "";
        }
        abstract String userBaseDn();
        abstract String userSearchFilter();
        abstract String groupBaseDn();
        abstract String groupSearchFilter();
        abstract Map<String, List<String>> roleMappings();
        abstract @Nullable String authTestUsername(); // only used in request
        abstract @Nullable String authTestPassword(); // only used in request
        abstract String version();

        private LdapConfig convert(ConfigRepository configRepository) throws Exception {
            ImmutableLdapConfig.Builder builder = ImmutableLdapConfig.builder()
                    .host(host())
                    .port(port())
                    .ssl(ssl())
                    .username(username())
                    .userBaseDn(userBaseDn())
                    .userSearchFilter(userSearchFilter())
                    .groupBaseDn(groupBaseDn())
                    .groupSearchFilter(groupSearchFilter())
                    .roleMappings(roleMappings());
            if (!passwordExists()) {
                // clear password
                builder.encryptedPassword("");
            } else if (passwordExists() && !newPassword().isEmpty()) {
                // change password
                builder.encryptedPassword(
                        Encryption.encrypt(newPassword(), configRepository.getLazySecretKey()));
            } else {
                // keep existing password
                builder.encryptedPassword(configRepository.getLdapConfig().encryptedPassword());
            }
            return builder.build();
        }

        private static LdapConfigDto create(LdapConfig config) {
            return ImmutableLdapConfigDto.builder()
                    .host(config.host())
                    .port(config.port())
                    .ssl(config.ssl())
                    .username(config.username())
                    .passwordExists(!config.encryptedPassword().isEmpty())
                    .userBaseDn(config.userBaseDn())
                    .userSearchFilter(config.userSearchFilter())
                    .groupBaseDn(config.groupBaseDn())
                    .groupSearchFilter(config.groupSearchFilter())
                    .roleMappings(config.roleMappings())
                    .version(config.version())
                    .build();
        }
    }

    @Value.Immutable
    abstract static class PagerDutyConfigDto {

        public abstract List<ImmutablePagerDutyIntegrationKey> integrationKeys();
        abstract String version();

        private PagerDutyConfig convert() {
            return ImmutablePagerDutyConfig.builder()
                    .addAllIntegrationKeys(integrationKeys())
                    .build();
        }

        private static PagerDutyConfigDto create(PagerDutyConfig config) {
            return ImmutablePagerDutyConfigDto.builder()
                    .addAllIntegrationKeys(config.integrationKeys())
                    .version(config.version())
                    .build();
        }
    }

    @Value.Immutable
    abstract static class SlackConfigDto {

        public abstract List<ImmutableSlackWebhookDto> webhooks();
        abstract String version();

        private SlackConfig convert() {
            ImmutableSlackConfig.Builder builder = ImmutableSlackConfig.builder();
            for (SlackWebhookDto webhook : webhooks()) {
                builder.addWebhooks(webhook.convert());
            }
            return builder.build();
        }

        private static SlackConfigDto create(SlackConfig config) {
            ImmutableSlackConfigDto.Builder builder = ImmutableSlackConfigDto.builder();
            for (SlackWebhook webhook : config.webhooks()) {
                builder.addWebhooks(SlackWebhookDto.create(webhook));
            }
            return builder.version(config.version())
                    .build();
        }
    }

    @Value.Immutable
    abstract static class SlackWebhookDto {

        @Nullable
        abstract String id(); // null for new webhooks
        abstract String url();
        abstract String display();

        private ImmutableSlackWebhook convert() {
            String id = id();
            if (id == null) {
                id = IdGenerator.generateNewId();
            }
            return ImmutableSlackWebhook.builder()
                    .id(id)
                    .url(url())
                    .display(display())
                    .build();
        }

        private static ImmutableSlackWebhookDto create(SlackWebhook webhook) {
            return ImmutableSlackWebhookDto.builder()
                    .id(webhook.id())
                    .url(webhook.url())
                    .display(webhook.display())
                    .build();
        }
    }

    @Value.Immutable
    abstract static class HealthchecksIoConfigDto {

        abstract String pingUrl();
        abstract String version();

        private HealthchecksIoConfig convert() {
            return ImmutableHealthchecksIoConfig.builder()
                    .pingUrl(pingUrl())
                    .build();
        }

        private static HealthchecksIoConfigDto create(HealthchecksIoConfig config) {
            return ImmutableHealthchecksIoConfigDto.builder()
                    .pingUrl(config.pingUrl())
                    .version(config.version())
                    .build();
        }
    }
}
