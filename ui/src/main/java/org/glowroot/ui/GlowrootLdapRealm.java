/*
 * Copyright 2016 the original author or authors.
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

import javax.annotation.Nullable;

import org.apache.shiro.authc.AuthenticationException;
import org.apache.shiro.authc.AuthenticationInfo;
import org.apache.shiro.authc.AuthenticationToken;
import org.apache.shiro.authz.AuthorizationInfo;
import org.apache.shiro.realm.ldap.JndiLdapContextFactory;
import org.apache.shiro.realm.ldap.JndiLdapRealm;
import org.apache.shiro.subject.PrincipalCollection;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.glowroot.storage.config.LdapConfig;
import org.glowroot.storage.config.UserConfig;
import org.glowroot.storage.repo.ConfigRepository;

class GlowrootLdapRealm extends JndiLdapRealm {

    private static final Logger logger = LoggerFactory.getLogger(GlowrootLdapRealm.class);

    private final ConfigRepository configRepository;

    private volatile String ldapConfigVersion;

    GlowrootLdapRealm(ConfigRepository configRepository) {
        this.configRepository = configRepository;
        LdapConfig ldapConfig = configRepository.getLdapConfig();
        init(castInitialized(this), ldapConfig);
        ldapConfigVersion = ldapConfig.version();
    }

    @Override
    public boolean supports(AuthenticationToken token) {
        if (configRepository.getLdapConfig().url().isEmpty()) {
            return false;
        }
        String username = (String) token.getPrincipal();
        UserConfig userConfig = configRepository.getUserConfigCaseInsensitive(username);
        return userConfig != null && userConfig.ldap();
    }

    @Override
    protected @Nullable AuthenticationInfo doGetAuthenticationInfo(AuthenticationToken token) {
        LdapConfig ldapConfig = configRepository.getLdapConfig();
        if (!ldapConfig.version().equals(ldapConfigVersion)) {
            init(this, ldapConfig);
        }
        try {
            return ldapConfig.url().isEmpty() ? null : super.doGetAuthenticationInfo(token);
        } catch (AuthenticationException e) {
            logger.debug(e.getMessage(), e);
            return null;
        }
    }

    @Override
    protected @Nullable AuthorizationInfo doGetAuthorizationInfo(PrincipalCollection principals) {
        return null;
    }

    static void init(JndiLdapRealm jndiLdapRealm, LdapConfig ldapConfig) {
        String url = ldapConfig.url();
        if (!url.isEmpty()) {
            String userDnTemplate = ldapConfig.userDnTemplate();
            if (!userDnTemplate.isEmpty()) {
                try {
                    jndiLdapRealm.setUserDnTemplate(userDnTemplate);
                } catch (IllegalArgumentException e) {
                    logger.error(e.getMessage(), e);
                }
            }
            JndiLdapContextFactory contextFactory =
                    (JndiLdapContextFactory) jndiLdapRealm.getContextFactory();
            contextFactory.setUrl(url);
            contextFactory.setAuthenticationMechanism(ldapConfig.authenticationMechanism());
        }
    }

    @SuppressWarnings("return.type.incompatible")
    private static <T> /*@Initialized*/ T castInitialized(/*@UnderInitialization*/ T obj) {
        return obj;
    }
}
