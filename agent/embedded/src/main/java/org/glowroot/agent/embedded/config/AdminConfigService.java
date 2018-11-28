/*
 * Copyright 2011-2018 the original author or authors.
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
package org.glowroot.agent.embedded.config;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.core.type.TypeReference;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.glowroot.common.util.OnlyUsedByTests;
import org.glowroot.common2.config.AllEmbeddedAdminConfig;
import org.glowroot.common2.config.EmbeddedAdminGeneralConfig;
import org.glowroot.common2.config.EmbeddedStorageConfig;
import org.glowroot.common2.config.EmbeddedWebConfig;
import org.glowroot.common2.config.HealthchecksIoConfig;
import org.glowroot.common2.config.HttpProxyConfig;
import org.glowroot.common2.config.ImmutableAllEmbeddedAdminConfig;
import org.glowroot.common2.config.ImmutableEmbeddedAdminGeneralConfig;
import org.glowroot.common2.config.ImmutableEmbeddedStorageConfig;
import org.glowroot.common2.config.ImmutableEmbeddedWebConfig;
import org.glowroot.common2.config.ImmutableHealthchecksIoConfig;
import org.glowroot.common2.config.ImmutableHttpProxyConfig;
import org.glowroot.common2.config.ImmutableLdapConfig;
import org.glowroot.common2.config.ImmutablePagerDutyConfig;
import org.glowroot.common2.config.ImmutableRoleConfig;
import org.glowroot.common2.config.ImmutableSlackConfig;
import org.glowroot.common2.config.ImmutableSmtpConfig;
import org.glowroot.common2.config.ImmutableUserConfig;
import org.glowroot.common2.config.LdapConfig;
import org.glowroot.common2.config.PagerDutyConfig;
import org.glowroot.common2.config.RoleConfig;
import org.glowroot.common2.config.SlackConfig;
import org.glowroot.common2.config.SmtpConfig;
import org.glowroot.common2.config.UserConfig;

public class AdminConfigService {

    private static final Logger logger = LoggerFactory.getLogger(AdminConfigService.class);

    private final AdminConfigFile adminConfigFile;

    private volatile EmbeddedAdminGeneralConfig generalConfig;
    private volatile List<UserConfig> userConfigs;
    private volatile List<RoleConfig> roleConfigs;
    private volatile EmbeddedWebConfig webConfig;
    private volatile EmbeddedStorageConfig storageConfig;
    private volatile SmtpConfig smtpConfig;
    private volatile HttpProxyConfig httpProxyConfig;
    private volatile LdapConfig ldapConfig;
    private volatile PagerDutyConfig pagerDutyConfig;
    private volatile SlackConfig slackConfig;
    private volatile HealthchecksIoConfig healthchecksIoConfig;

    public static AdminConfigService create(List<File> confDirs, boolean configReadOnly,
            @Nullable Integer webPortOverride) {
        AdminConfigService configService =
                new AdminConfigService(confDirs, configReadOnly, webPortOverride);
        // it's nice to update config.json on startup if it is missing some/all config
        // properties so that the file contents can be reviewed/updated/copied if desired
        try {
            configService.writeAll();
        } catch (IOException e) {
            logger.error(e.getMessage(), e);
        }
        return configService;
    }

    private AdminConfigService(List<File> confDirs, boolean configReadOnly,
            @Nullable Integer webPortOverride) {
        adminConfigFile = new AdminConfigFile(confDirs, configReadOnly);
        ImmutableEmbeddedAdminGeneralConfig generalConfig =
                adminConfigFile.getConfig("general", ImmutableEmbeddedAdminGeneralConfig.class);
        if (generalConfig == null) {
            this.generalConfig = ImmutableEmbeddedAdminGeneralConfig.builder().build();
        } else {
            this.generalConfig = generalConfig;
        }
        List<ImmutableUserConfig> userConfigs = adminConfigFile.getConfig("users",
                new TypeReference<List<ImmutableUserConfig>>() {});
        if (userConfigs == null) {
            this.userConfigs = ImmutableList.of();
        } else {
            this.userConfigs = ImmutableList.<UserConfig>copyOf(userConfigs);
        }
        if (this.userConfigs.isEmpty()) {
            this.userConfigs = ImmutableList.<UserConfig>of(ImmutableUserConfig.builder()
                    .username("anonymous")
                    .addRoles("Administrator")
                    .build());
        }
        List<ImmutableRoleConfig> roleConfigs = adminConfigFile.getConfig("roles",
                new TypeReference<List<ImmutableRoleConfig>>() {});
        if (roleConfigs == null) {
            this.roleConfigs = ImmutableList.of();
        } else {
            this.roleConfigs = ImmutableList.<RoleConfig>copyOf(roleConfigs);
        }
        if (this.roleConfigs.isEmpty()) {
            this.roleConfigs = ImmutableList.<RoleConfig>of(ImmutableRoleConfig.builder()
                    .name("Administrator")
                    .addPermissions("agent:transaction", "agent:error", "agent:jvm",
                            "agent:incident", "agent:config", "admin")
                    .build());
        }
        ImmutableEmbeddedWebConfig webConfig =
                adminConfigFile.getConfig("web", ImmutableEmbeddedWebConfig.class);
        if (webConfig == null) {
            this.webConfig = ImmutableEmbeddedWebConfig.builder().build();
        } else {
            this.webConfig = webConfig;
        }
        if (webPortOverride != null) {
            this.webConfig = ImmutableEmbeddedWebConfig.builder()
                    .copyFrom(this.webConfig)
                    .port(webPortOverride)
                    .build();
        }
        ImmutableEmbeddedStorageConfig storageConfig =
                adminConfigFile.getConfig("storage", ImmutableEmbeddedStorageConfig.class);
        if (storageConfig == null) {
            this.storageConfig = ImmutableEmbeddedStorageConfig.builder().build();
        } else if (storageConfig.hasListIssues()) {
            this.storageConfig = withCorrectedLists(storageConfig);
        } else {
            this.storageConfig = storageConfig;
        }
        ImmutableSmtpConfig smtpConfig =
                adminConfigFile.getConfig("smtp", ImmutableSmtpConfig.class);
        if (smtpConfig == null) {
            this.smtpConfig = ImmutableSmtpConfig.builder().build();
        } else {
            this.smtpConfig = smtpConfig;
        }
        ImmutableHttpProxyConfig httpProxyConfig =
                adminConfigFile.getConfig("httpProxy", ImmutableHttpProxyConfig.class);
        if (httpProxyConfig == null) {
            this.httpProxyConfig = ImmutableHttpProxyConfig.builder().build();
        } else {
            this.httpProxyConfig = httpProxyConfig;
        }
        ImmutableLdapConfig ldapConfig =
                adminConfigFile.getConfig("ldap", ImmutableLdapConfig.class);
        if (ldapConfig == null) {
            this.ldapConfig = ImmutableLdapConfig.builder().build();
        } else {
            this.ldapConfig = ldapConfig;
        }
        ImmutablePagerDutyConfig pagerDutyConfig =
                adminConfigFile.getConfig("pagerDuty", ImmutablePagerDutyConfig.class);
        if (pagerDutyConfig == null) {
            this.pagerDutyConfig = ImmutablePagerDutyConfig.builder().build();
        } else {
            this.pagerDutyConfig = pagerDutyConfig;
        }
        ImmutableSlackConfig slackConfig =
                adminConfigFile.getConfig("slack", ImmutableSlackConfig.class);
        if (slackConfig == null) {
            this.slackConfig = ImmutableSlackConfig.builder().build();
        } else {
            this.slackConfig = slackConfig;
        }
        ImmutableHealthchecksIoConfig healthchecksIoConfig =
                adminConfigFile.getConfig("healthchecksIo", ImmutableHealthchecksIoConfig.class);
        if (healthchecksIoConfig == null) {
            this.healthchecksIoConfig = ImmutableHealthchecksIoConfig.builder().build();
        } else {
            this.healthchecksIoConfig = healthchecksIoConfig;
        }
    }

    public EmbeddedAdminGeneralConfig getEmbeddedAdminGeneralConfig() {
        return generalConfig;
    }

    public List<UserConfig> getUserConfigs() {
        return userConfigs;
    }

    public List<RoleConfig> getRoleConfigs() {
        return roleConfigs;
    }

    public EmbeddedWebConfig getEmbeddedWebConfig() {
        return webConfig;
    }

    public EmbeddedStorageConfig getEmbeddedStorageConfig() {
        return storageConfig;
    }

    public SmtpConfig getSmtpConfig() {
        return smtpConfig;
    }

    public HttpProxyConfig getHttpProxyConfig() {
        return httpProxyConfig;
    }

    public LdapConfig getLdapConfig() {
        return ldapConfig;
    }

    public PagerDutyConfig getPagerDutyConfig() {
        return pagerDutyConfig;
    }

    public SlackConfig getSlackConfig() {
        return slackConfig;
    }

    public HealthchecksIoConfig getHealthchecksIoConfig() {
        return healthchecksIoConfig;
    }

    public AllEmbeddedAdminConfig getAllAdminConfig() {
        return ImmutableAllEmbeddedAdminConfig.builder()
                .general(generalConfig)
                .users(userConfigs)
                .roles(roleConfigs)
                .web(webConfig)
                .storage(storageConfig)
                .smtp(smtpConfig)
                .httpProxy(httpProxyConfig)
                .ldap(ldapConfig)
                .pagerDuty(pagerDutyConfig)
                .slack(slackConfig)
                .healthchecksIo(healthchecksIoConfig)
                .build();
    }

    public void updateEmbeddedAdminGeneralConfig(EmbeddedAdminGeneralConfig config)
            throws Exception {
        adminConfigFile.writeConfig("general", config);
        generalConfig = ImmutableEmbeddedAdminGeneralConfig.copyOf(config);
    }

    public void updateUserConfigs(List<UserConfig> configs) throws Exception {
        adminConfigFile.writeConfig("users", configs);
        userConfigs = ImmutableList.copyOf(configs);
    }

    public void updateRoleConfigs(List<RoleConfig> configs) throws Exception {
        adminConfigFile.writeConfig("roles", configs);
        roleConfigs = ImmutableList.copyOf(configs);
    }

    public void updateEmbeddedWebConfig(EmbeddedWebConfig config) throws Exception {
        adminConfigFile.writeConfig("web", config);
        webConfig = ImmutableEmbeddedWebConfig.copyOf(config);
    }

    public void updateEmbeddedStorageConfig(EmbeddedStorageConfig config) throws Exception {
        adminConfigFile.writeConfig("storage", config);
        storageConfig = ImmutableEmbeddedStorageConfig.copyOf(config);
    }

    public void updateSmtpConfig(SmtpConfig config) throws Exception {
        adminConfigFile.writeConfig("smtp", config);
        smtpConfig = ImmutableSmtpConfig.copyOf(config);
    }

    public void updateHttpProxyConfig(HttpProxyConfig config) throws Exception {
        adminConfigFile.writeConfig("httpProxy", config);
        httpProxyConfig = ImmutableHttpProxyConfig.copyOf(config);
    }

    public void updateLdapConfig(LdapConfig config) throws Exception {
        adminConfigFile.writeConfig("ldap", config);
        ldapConfig = ImmutableLdapConfig.copyOf(config);
    }

    public void updatePagerDutyConfig(PagerDutyConfig config) throws Exception {
        adminConfigFile.writeConfig("pagerDuty", config);
        pagerDutyConfig = ImmutablePagerDutyConfig.copyOf(config);
    }

    public void updateSlackConfig(SlackConfig config) throws Exception {
        adminConfigFile.writeConfig("slack", config);
        slackConfig = ImmutableSlackConfig.copyOf(config);
    }

    public void updateHealthchecksIoConfig(HealthchecksIoConfig config) throws Exception {
        adminConfigFile.writeConfig("healthchecksIo", config);
        healthchecksIoConfig = ImmutableHealthchecksIoConfig.copyOf(config);
    }

    public void updateAllAdminConfig(AllEmbeddedAdminConfig config) throws IOException {
        Map<String, Object> configs = Maps.newHashMap();
        configs.put("general", config.general());
        configs.put("users", config.users());
        configs.put("roles", config.roles());
        configs.put("web", config.web());
        configs.put("storage", config.storage());
        configs.put("smtp", config.smtp());
        configs.put("httpProxy", config.httpProxy());
        configs.put("ldap", config.ldap());
        configs.put("pagerDuty", config.pagerDuty());
        configs.put("slack", config.slack());
        configs.put("healthchecksIo", config.healthchecksIo());
        adminConfigFile.writeConfigsOnStartup(configs);
        this.generalConfig = config.general();
        this.userConfigs = ImmutableList.copyOf(config.users());
        this.roleConfigs = ImmutableList.copyOf(config.roles());
        this.webConfig = config.web();
        this.storageConfig = config.storage();
        this.smtpConfig = config.smtp();
        this.httpProxyConfig = config.httpProxy();
        this.ldapConfig = config.ldap();
        this.pagerDutyConfig = config.pagerDuty();
        this.slackConfig = config.slack();
        this.healthchecksIoConfig = config.healthchecksIo();
    }

    private void writeAll() throws IOException {
        Map<String, Object> configs = Maps.newHashMap();
        configs.put("general", generalConfig);
        configs.put("users", userConfigs);
        configs.put("roles", roleConfigs);
        configs.put("web", webConfig);
        configs.put("storage", storageConfig);
        configs.put("smtp", smtpConfig);
        configs.put("httpProxy", httpProxyConfig);
        configs.put("ldap", ldapConfig);
        configs.put("pagerDuty", pagerDutyConfig);
        configs.put("slack", slackConfig);
        configs.put("healthchecksIo", healthchecksIoConfig);
        adminConfigFile.writeConfigsOnStartup(configs);
    }

    @OnlyUsedByTests
    public void resetAdminConfig() throws IOException {
        generalConfig = ImmutableEmbeddedAdminGeneralConfig.builder().build();
        userConfigs = ImmutableList.<UserConfig>of(ImmutableUserConfig.builder()
                .username("anonymous")
                .addRoles("Administrator")
                .build());
        roleConfigs = ImmutableList.<RoleConfig>of(ImmutableRoleConfig.builder()
                .name("Administrator")
                .addPermissions("agent:transaction", "agent:error", "agent:jvm",
                        "agent:config:view", "agent:config:edit", "admin")
                .build());
        webConfig = ImmutableEmbeddedWebConfig.builder().build();
        storageConfig = ImmutableEmbeddedStorageConfig.builder().build();
        smtpConfig = ImmutableSmtpConfig.builder().build();
        httpProxyConfig = ImmutableHttpProxyConfig.builder().build();
        ldapConfig = ImmutableLdapConfig.builder().build();
        pagerDutyConfig = ImmutablePagerDutyConfig.builder().build();
        slackConfig = ImmutableSlackConfig.builder().build();
        healthchecksIoConfig = ImmutableHealthchecksIoConfig.builder().build();
        writeAll();
    }

    private static ImmutableEmbeddedStorageConfig withCorrectedLists(EmbeddedStorageConfig config) {
        EmbeddedStorageConfig defaultConfig = ImmutableEmbeddedStorageConfig.builder().build();
        ImmutableList<Integer> rollupExpirationHours =
                fix(config.rollupExpirationHours(), defaultConfig.rollupExpirationHours());
        ImmutableList<Integer> rollupCappedDatabaseSizesMb = fix(
                config.rollupCappedDatabaseSizesMb(), defaultConfig.rollupCappedDatabaseSizesMb());
        return ImmutableEmbeddedStorageConfig.builder()
                .copyFrom(config)
                .rollupExpirationHours(rollupExpirationHours)
                .rollupCappedDatabaseSizesMb(rollupCappedDatabaseSizesMb)
                .build();
    }

    private static ImmutableList<Integer> fix(ImmutableList<Integer> thisList,
            List<Integer> defaultList) {
        if (thisList.size() >= defaultList.size()) {
            return thisList.subList(0, defaultList.size());
        }
        List<Integer> correctedList = Lists.newArrayList(thisList);
        for (int i = thisList.size(); i < defaultList.size(); i++) {
            correctedList.add(defaultList.get(i));
        }
        return ImmutableList.copyOf(correctedList);
    }
}
