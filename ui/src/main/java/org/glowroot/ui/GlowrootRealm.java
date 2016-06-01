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
import org.apache.shiro.authc.SimpleAccount;
import org.apache.shiro.authc.UnknownAccountException;
import org.apache.shiro.authc.UsernamePasswordToken;
import org.apache.shiro.authz.AuthorizationInfo;
import org.apache.shiro.authz.SimpleAuthorizationInfo;
import org.apache.shiro.realm.AuthorizingRealm;
import org.apache.shiro.subject.PrincipalCollection;

import org.glowroot.storage.config.RoleConfig;
import org.glowroot.storage.config.UserConfig;
import org.glowroot.storage.repo.ConfigRepository;

import static com.google.common.base.Preconditions.checkNotNull;

class GlowrootRealm extends AuthorizingRealm {

    private static final String GLOWROOT_REALM = "Glowroot";

    private final ConfigRepository configRepository;

    GlowrootRealm(ConfigRepository configRepository) {
        this.configRepository = configRepository;
    }

    @Override
    protected AuthorizationInfo doGetAuthorizationInfo(PrincipalCollection principals) {
        String username = (String) principals.getPrimaryPrincipal();
        UserConfig userConfig = getUserConfigCaseInsensitive(username);
        checkNotNull(userConfig);
        SimpleAuthorizationInfo authorizationInfo = new SimpleAuthorizationInfo(userConfig.roles());
        for (String roleName : userConfig.roles()) {
            RoleConfig roleConfig = configRepository.getRoleConfig(roleName);
            if (roleConfig == null) {
                continue;
            }
            authorizationInfo.addStringPermissions(roleConfig.permissions());
        }
        return authorizationInfo;
    }

    @Override
    protected AuthenticationInfo doGetAuthenticationInfo(AuthenticationToken token)
            throws AuthenticationException {
        UsernamePasswordToken upToken = (UsernamePasswordToken) token;
        UserConfig userConfig = getUserConfigCaseInsensitive(upToken.getUsername());
        if (userConfig == null) {
            throw new UnknownAccountException();
        }
        SimpleAccount authenticationInfo =
                new SimpleAccount(userConfig.username(), userConfig.passwordHash(), GLOWROOT_REALM);
        return authenticationInfo;
    }

    private @Nullable UserConfig getUserConfigCaseInsensitive(String username) {
        for (UserConfig userConfig : configRepository.getUserConfigs()) {
            if (userConfig.username().equalsIgnoreCase(username)) {
                return userConfig;
            }
        }
        return null;
    }
}
