/*
 * Copyright 2018-2023 the original author or authors.
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
package org.glowroot.common2.config;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnore;
import org.immutables.value.Value;

import org.glowroot.common.util.Versions;

@Value.Immutable
public abstract class AllCentralAdminConfig {

    @Value.Default
    public ImmutableCentralAdminGeneralConfig general() {
        return ImmutableCentralAdminGeneralConfig.builder().build();
    }

    public abstract List<ImmutableUserConfig> users();
    public abstract List<ImmutableRoleConfig> roles();

    @Value.Default
    public ImmutableCentralWebConfig web() {
        return ImmutableCentralWebConfig.builder().build();
    }

    @Value.Default
    public ImmutableCentralStorageConfig storage() {
        return ImmutableCentralStorageConfig.builder().build();
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
    public ImmutableSlackConfig slack() {
        return ImmutableSlackConfig.builder().build();
    }

    @Value.Derived
    @JsonIgnore
    public String version() {
        return Versions.getJsonVersion(this);
    }
}
