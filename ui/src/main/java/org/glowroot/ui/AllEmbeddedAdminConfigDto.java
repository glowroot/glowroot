/*
 * Copyright 2018 the original author or authors.
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
import org.immutables.value.Value;

import org.glowroot.common2.config.AllEmbeddedAdminConfig;
import org.glowroot.common2.config.ImmutableAllEmbeddedAdminConfig;
import org.glowroot.common2.config.ImmutableEmbeddedAdminGeneralConfig;
import org.glowroot.common2.config.ImmutableEmbeddedStorageConfig;
import org.glowroot.common2.config.ImmutableEmbeddedWebConfig;
import org.glowroot.common2.config.ImmutableHealthchecksIoConfig;
import org.glowroot.common2.config.ImmutableHttpProxyConfig;
import org.glowroot.common2.config.ImmutableLdapConfig;
import org.glowroot.common2.config.ImmutablePagerDutyConfig;
import org.glowroot.common2.config.ImmutableRoleConfig;
import org.glowroot.common2.config.ImmutableSmtpConfig;
import org.glowroot.common2.config.RoleConfig;
import org.glowroot.common2.config.UserConfig;
import org.glowroot.ui.AllCentralAdminConfigDto.UserConfigDtx;

@Value.Immutable
public abstract class AllEmbeddedAdminConfigDto {

    @Value.Default
    public ImmutableEmbeddedAdminGeneralConfig general() {
        return ImmutableEmbeddedAdminGeneralConfig.builder().build();
    }

    @JsonInclude(Include.NON_EMPTY)
    public abstract List<ImmutableUserConfigDtx> users();

    @JsonInclude(Include.NON_EMPTY)
    public abstract List<ImmutableRoleConfig> roles();

    @Value.Default
    public ImmutableEmbeddedWebConfig web() {
        return ImmutableEmbeddedWebConfig.builder().build();
    }

    @Value.Default
    public ImmutableEmbeddedStorageConfig storage() {
        return ImmutableEmbeddedStorageConfig.builder().build();
    }

    @Value.Default
    public ImmutableSmtpConfig smtp() {
        return ImmutableSmtpConfig.builder().build();
    }

    @Value.Default
    public ImmutableHttpProxyConfig httpProxy() {
        return ImmutableHttpProxyConfig.builder().build();
    }

    @Value.Default
    public ImmutableLdapConfig ldap() {
        return ImmutableLdapConfig.builder().build();
    }

    @Value.Default
    public ImmutablePagerDutyConfig pagerDuty() {
        return ImmutablePagerDutyConfig.builder().build();
    }

    @Value.Default
    public ImmutableHealthchecksIoConfig healthchecksIo() {
        return ImmutableHealthchecksIoConfig.builder().build();
    }

    abstract @Nullable String version();

    AllEmbeddedAdminConfig toConfig() throws GeneralSecurityException {
        ImmutableAllEmbeddedAdminConfig.Builder builder = ImmutableAllEmbeddedAdminConfig.builder()
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
                .healthchecksIo(healthchecksIo())
                .build();
    }

    static AllEmbeddedAdminConfigDto create(AllEmbeddedAdminConfig config) {
        ImmutableAllEmbeddedAdminConfigDto.Builder builder =
                ImmutableAllEmbeddedAdminConfigDto.builder()
                        .general(ImmutableEmbeddedAdminGeneralConfig.copyOf(config.general()));
        for (UserConfig userConfig : config.users()) {
            builder.addUsers(UserConfigDtx.create(userConfig));
        }
        for (RoleConfig roleConfig : config.roles()) {
            builder.addRoles(ImmutableRoleConfig.copyOf(roleConfig));
        }
        return builder.web(ImmutableEmbeddedWebConfig.copyOf(config.web()))
                .storage(ImmutableEmbeddedStorageConfig.copyOf(config.storage()))
                .smtp(ImmutableSmtpConfig.copyOf(config.smtp()))
                .httpProxy(ImmutableHttpProxyConfig.copyOf(config.httpProxy()))
                .ldap(ImmutableLdapConfig.copyOf(config.ldap()))
                .pagerDuty(ImmutablePagerDutyConfig.copyOf(config.pagerDuty()))
                .healthchecksIo(ImmutableHealthchecksIoConfig.copyOf(config.healthchecksIo()))
                .version(config.version())
                .build();
    }
}
