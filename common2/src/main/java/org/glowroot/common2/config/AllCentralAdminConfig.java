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
package org.glowroot.common2.config;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnore;
import org.immutables.value.Value;

import org.glowroot.common.util.Versions;

@Value.Immutable
public abstract class AllCentralAdminConfig {

    public abstract CentralAdminGeneralConfig general();
    public abstract List<UserConfig> users();
    public abstract List<RoleConfig> roles();
    public abstract CentralWebConfig web();
    public abstract CentralStorageConfig storage();
    public abstract SmtpConfig smtp();
    public abstract HttpProxyConfig httpProxy();
    public abstract LdapConfig ldap();
    public abstract PagerDutyConfig pagerDuty();
    public abstract SlackConfig slack();

    @Value.Derived
    @JsonIgnore
    public String version() {
        return Versions.getJsonVersion(this);
    }
}
