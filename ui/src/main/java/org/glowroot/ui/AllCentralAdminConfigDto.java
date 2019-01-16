/*
 * Copyright 2018-2019 the original author or authors.
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

import java.security.GeneralSecurityException;
import java.util.List;

import javax.annotation.Nullable;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;
import com.google.common.collect.ImmutableSet;
import org.immutables.value.Value;

import org.glowroot.common2.config.AllCentralAdminConfig;
import org.glowroot.common2.config.ImmutableAllCentralAdminConfig;
import org.glowroot.common2.config.ImmutableCentralAdminGeneralConfig;
import org.glowroot.common2.config.ImmutableCentralStorageConfig;
import org.glowroot.common2.config.ImmutableCentralWebConfig;
import org.glowroot.common2.config.ImmutableHttpProxyConfig;
import org.glowroot.common2.config.ImmutableLdapConfig;
import org.glowroot.common2.config.ImmutablePagerDutyConfig;
import org.glowroot.common2.config.ImmutableRoleConfig;
import org.glowroot.common2.config.ImmutableSlackConfig;
import org.glowroot.common2.config.ImmutableSmtpConfig;
import org.glowroot.common2.config.ImmutableUserConfig;
import org.glowroot.common2.config.RoleConfig;
import org.glowroot.common2.config.UserConfig;

@Value.Immutable
abstract class AllCentralAdminConfigDto {

    @Value.Default
    ImmutableCentralAdminGeneralConfig general() {
        return ImmutableCentralAdminGeneralConfig.builder().build();
    }

    @JsonInclude(Include.NON_EMPTY)
    abstract List<ImmutableUserConfigDtx> users();

    @JsonInclude(Include.NON_EMPTY)
    abstract List<ImmutableRoleConfig> roles();

    @Value.Default
    ImmutableCentralWebConfig web() {
        return ImmutableCentralWebConfig.builder().build();
    }

    @Value.Default
    ImmutableCentralStorageConfig storage() {
        return ImmutableCentralStorageConfig.builder().build();
    }

    @Value.Default
    ImmutableSmtpConfig smtp() {
        return ImmutableSmtpConfig.builder().build();
    }

    @Value.Default
    ImmutableHttpProxyConfig httpProxy() {
        return ImmutableHttpProxyConfig.builder().build();
    }

    @Value.Default
    ImmutableLdapConfig ldap() {
        return ImmutableLdapConfig.builder().build();
    }

    @Value.Default
    ImmutablePagerDutyConfig pagerDuty() {
        return ImmutablePagerDutyConfig.builder().build();
    }

    @Value.Default
    ImmutableSlackConfig slack() {
        return ImmutableSlackConfig.builder().build();
    }

    abstract @Nullable String version();

    AllCentralAdminConfig toConfig() throws GeneralSecurityException {
        ImmutableAllCentralAdminConfig.Builder builder = ImmutableAllCentralAdminConfig.builder()
                .general(general());
        for (UserConfigDtx userConfig : users()) {
            builder.addUsers(userConfig.toConfig());
        }
        return builder.addAllRoles(roles())
                .web(web())
                .storage(storage())
                .smtp(smtp())
                .httpProxy(httpProxy())
                .ldap(ldap())
                .pagerDuty(pagerDuty())
                .slack(slack())
                .build();
    }

    static AllCentralAdminConfigDto create(AllCentralAdminConfig config) {
        ImmutableAllCentralAdminConfigDto.Builder builder =
                ImmutableAllCentralAdminConfigDto.builder()
                        .general(ImmutableCentralAdminGeneralConfig.copyOf(config.general()));
        for (UserConfig userConfig : config.users()) {
            builder.addUsers(UserConfigDtx.create(userConfig));
        }
        for (RoleConfig roleConfig : config.roles()) {
            builder.addRoles(ImmutableRoleConfig.copyOf(roleConfig));
        }
        return builder.web(ImmutableCentralWebConfig.copyOf(config.web()))
                .storage(ImmutableCentralStorageConfig.copyOf(config.storage()))
                .smtp(ImmutableSmtpConfig.copyOf(config.smtp()))
                .httpProxy(ImmutableHttpProxyConfig.copyOf(config.httpProxy()))
                .ldap(ImmutableLdapConfig.copyOf(config.ldap()))
                .pagerDuty(ImmutablePagerDutyConfig.copyOf(config.pagerDuty()))
                .slack(ImmutableSlackConfig.copyOf(config.slack()))
                .version(config.version())
                .build();
    }

    @Value.Immutable
    abstract static class UserConfigDtx {

        abstract String username();

        @Value.Default
        @JsonInclude(Include.NON_EMPTY)
        String password() {
            return "";
        }

        @Value.Default
        @JsonInclude(Include.NON_EMPTY)
        String passwordHash() {
            return "";
        }

        @Value.Default
        @JsonInclude(Include.NON_EMPTY)
        boolean ldap() {
            return false;
        }

        abstract ImmutableSet<String> roles();

        ImmutableUserConfig toConfig()
                throws GeneralSecurityException {
            return ImmutableUserConfig.builder()
                    .username(username())
                    .passwordHash(getPasswordHash())
                    .ldap(ldap())
                    .addAllRoles(roles())
                    .build();
        }

        private String getPasswordHash() throws GeneralSecurityException {
            String password = password();
            String passwordHash = passwordHash();
            if (!password.isEmpty() || !passwordHash.isEmpty()) {
                if (ldap()) {
                    throw new IllegalStateException("Password not allowed when ldap is true");
                }
                if (username().equalsIgnoreCase("anonymous")) {
                    throw new IllegalStateException("Password not allowed for anonymous user");
                }
                if (!password.isEmpty()) {
                    return PasswordHash.createHash(password);
                } else {
                    return passwordHash;
                }
            }
            // password will be retrieved from existing config during save (possibly under lock)
            return "";
        }

        static ImmutableUserConfigDtx create(UserConfig config) {
            return ImmutableUserConfigDtx.builder()
                    .username(config.username())
                    .ldap(config.ldap())
                    .roles(config.roles())
                    .build();
        }
    }
}
