/*
 * Copyright 2013-2016 the original author or authors.
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

import java.math.BigInteger;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Iterator;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;

import javax.annotation.Nullable;

import com.google.common.collect.Maps;
import com.google.common.collect.Sets;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.cookie.Cookie;
import io.netty.handler.codec.http.cookie.DefaultCookie;
import io.netty.handler.codec.http.cookie.ServerCookieDecoder;
import io.netty.handler.codec.http.cookie.ServerCookieEncoder;
import org.immutables.value.Value;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.glowroot.common.config.LdapConfig;
import org.glowroot.common.config.RoleConfig;
import org.glowroot.common.config.RoleConfig.SimplePermission;
import org.glowroot.common.config.UserConfig;
import org.glowroot.common.repo.ConfigRepository;
import org.glowroot.common.util.Clock;
import org.glowroot.ui.LdapAuthentication.AuthenticationException;

import static com.google.common.base.Preconditions.checkState;
import static io.netty.handler.codec.http.HttpResponseStatus.OK;
import static java.util.concurrent.TimeUnit.MINUTES;

class HttpSessionManager {

    private static final Logger logger = LoggerFactory.getLogger(HttpSessionManager.class);
    private static final Logger auditLogger = LoggerFactory.getLogger("audit");

    private final boolean fat;
    private final boolean offlineViewer;
    private final ConfigRepository configRepository;
    private final Clock clock;
    private final LayoutService layoutService;

    private final SecureRandom secureRandom = new SecureRandom();
    private final Map<String, Session> sessions = Maps.newConcurrentMap();

    HttpSessionManager(boolean fat, boolean offlineViewer, ConfigRepository configRepository,
            Clock clock, LayoutService layoutService) {
        this.fat = fat;
        this.offlineViewer = offlineViewer;
        this.configRepository = configRepository;
        this.clock = clock;
        this.layoutService = layoutService;
    }

    FullHttpResponse login(String username, String password) throws Exception {
        if (username.equalsIgnoreCase("anonymous")) {
            auditFailedLogin(username);
            return buildIncorrectLoginResponse();
        }
        Authentication authentication = null;
        UserConfig userConfig = getUserConfigCaseInsensitive(username);
        if (userConfig == null || userConfig.ldap()) {
            Set<String> roles;
            try {
                roles = authenticateAgainstLdapAndGetGlowrootRoles(username, password);
            } catch (AuthenticationException e) {
                logger.debug(e.getMessage(), e);
                auditFailedLogin(username);
                return buildIncorrectLoginResponse();
            }
            if (userConfig != null) {
                roles = Sets.newHashSet(roles);
                roles.addAll(userConfig.roles());
            }
            if (!roles.isEmpty()) {
                authentication = getAuthentication(username, roles, true);
            }
        } else if (userConfig != null && validatePassword(password, userConfig.passwordHash())) {
            authentication = getAuthentication(userConfig.username(), userConfig.roles(), false);
        }
        if (authentication == null) {
            auditFailedLogin(username);
            return buildIncorrectLoginResponse();
        } else {
            String text = layoutService.getLayout(authentication);
            FullHttpResponse response = HttpServices.createJsonResponse(text, OK);
            createSession(response, authentication);
            auditSuccessfulLogin(username);
            return response;
        }
    }

    void signOut(HttpRequest request) {
        String sessionId = getSessionId(request);
        if (sessionId != null) {
            Session session = sessions.remove(sessionId);
            if (session != null) {
                auditLogout(session.authentication.caseAmbiguousUsername());
            }
        }
    }

    void deleteSessionCookie(HttpResponse response) {
        Cookie cookie = new DefaultCookie(configRepository.getWebConfig().sessionCookieName(), "");
        cookie.setHttpOnly(true);
        cookie.setMaxAge(0);
        cookie.setPath("/");
        response.headers().add(HttpHeaderNames.SET_COOKIE,
                ServerCookieEncoder.STRICT.encode(cookie));
    }

    Authentication getAuthentication(HttpRequest request) {
        if (offlineViewer) {
            return getOfflineViewerAuthentication();
        }
        String sessionId = getSessionId(request);
        if (sessionId == null) {
            return getAnonymousAuthentication();
        }
        Session session = sessions.get(sessionId);
        if (session == null) {
            return getAnonymousAuthentication();
        }
        long currentTimeMillis = clock.currentTimeMillis();
        if (session.isTimedOut(currentTimeMillis)) {
            return getAnonymousAuthentication();
        }
        session.touch(currentTimeMillis);
        return session.authentication;
    }

    @Nullable
    String getSessionId(HttpRequest request) {
        String cookieHeader = request.headers().getAsString(HttpHeaderNames.COOKIE);
        if (cookieHeader == null) {
            return null;
        }
        Set<Cookie> cookies = ServerCookieDecoder.STRICT.decode(cookieHeader);
        for (Cookie cookie : cookies) {
            if (cookie.name().equals(configRepository.getWebConfig().sessionCookieName())) {
                return cookie.value();
            }
        }
        return null;
    }

    Authentication getAnonymousAuthentication() {
        UserConfig userConfig = getUserConfigCaseInsensitive("anonymous");
        if (userConfig == null) {
            return ImmutableAuthentication.builder()
                    .fat(fat)
                    .offlineViewer(false)
                    .anonymous(true)
                    .ldap(false)
                    .caseAmbiguousUsername("anonymous")
                    .configRepository(configRepository)
                    .build();
        }
        return getAuthentication(userConfig.username(), userConfig.roles(), false);
    }

    private FullHttpResponse buildIncorrectLoginResponse() {
        String text = "{\"incorrectLogin\":true}";
        return HttpServices.createJsonResponse(text, OK);
    }

    private void createSession(HttpResponse response, Authentication authentication)
            throws Exception {
        String sessionId = new BigInteger(130, secureRandom).toString(32);
        sessions.put(sessionId, new Session(authentication));
        Cookie cookie =
                new DefaultCookie(configRepository.getWebConfig().sessionCookieName(), sessionId);
        cookie.setHttpOnly(true);
        cookie.setPath("/");
        response.headers().add(HttpHeaderNames.SET_COOKIE,
                ServerCookieEncoder.STRICT.encode(cookie));
        purgeExpiredSessions();
    }

    private Authentication getAuthentication(String username, Set<String> roles, boolean ldap) {
        return ImmutableAuthentication.builder()
                .fat(fat)
                .offlineViewer(false)
                .anonymous(username.equalsIgnoreCase("anonymous"))
                .ldap(ldap)
                .caseAmbiguousUsername(username)
                .roles(roles)
                .configRepository(configRepository)
                .build();
    }

    private Authentication getOfflineViewerAuthentication() {
        return ImmutableAuthentication.builder()
                .fat(true) // offline viewer only applies to fat
                .offlineViewer(true)
                .anonymous(true)
                .ldap(false)
                .caseAmbiguousUsername("anonymous")
                .configRepository(configRepository)
                .build();
    }

    private @Nullable UserConfig getUserConfigCaseInsensitive(String username) {
        for (UserConfig userConfig : configRepository.getUserConfigs()) {
            if (userConfig.username().equalsIgnoreCase(username)) {
                return userConfig;
            }
        }
        return null;
    }

    private void purgeExpiredSessions() {
        long currentTimeMillis = clock.currentTimeMillis();
        Iterator<Entry<String, Session>> i = sessions.entrySet().iterator();
        while (i.hasNext()) {
            Session session = i.next().getValue();
            if (session.isTimedOut(currentTimeMillis)) {
                i.remove();
                auditSessionTimeout(session.authentication.caseAmbiguousUsername());
            }
        }
    }

    private long getTimeoutMillis() {
        return MINUTES.toMillis(configRepository.getWebConfig().sessionTimeoutMinutes());
    }

    private Set<String> authenticateAgainstLdapAndGetGlowrootRoles(String username, String password)
            throws Exception {
        LdapConfig ldapConfig = configRepository.getLdapConfig();
        String host = ldapConfig.host();
        if (host.isEmpty()) {
            throw new AuthenticationException("LDAP is not configured");
        }
        Set<String> ldapGroupDns = LdapAuthentication.authenticateAndGetLdapGroupDns(username,
                password, ldapConfig, configRepository.getSecretKey());
        return LdapAuthentication.getGlowrootRoles(ldapGroupDns, ldapConfig);
    }

    private void auditFailedLogin(String username) {
        auditLogger.info("{} - failed login", username);
    }

    private void auditSuccessfulLogin(String username) {
        auditLogger.info("{} - successful login", username);
    }

    private void auditLogout(String username) {
        auditLogger.info("{} - logout", username);
    }

    private void auditSessionTimeout(String username) {
        auditLogger.info("{} - session timeout", username);
    }

    private static boolean validatePassword(String password, String passwordHash)
            throws GeneralSecurityException {
        if (passwordHash.isEmpty()) {
            // need special case for empty password
            return password.isEmpty();
        } else {
            return PasswordHash.validatePassword(password, passwordHash);
        }
    }

    private class Session {

        private final Authentication authentication;
        private volatile long lastRequest;

        private Session(Authentication authentication) {
            this.authentication = authentication;
            lastRequest = clock.currentTimeMillis();
        }

        private boolean isTimedOut(long currentTimeMillis) {
            return lastRequest < currentTimeMillis - getTimeoutMillis();
        }

        private void touch(long currentTimeMillis) {
            lastRequest = currentTimeMillis;
        }
    }

    @Value.Immutable
    abstract static class Authentication {

        abstract boolean fat();
        abstract boolean offlineViewer();
        abstract boolean anonymous();
        abstract boolean ldap();
        abstract String caseAmbiguousUsername(); // the case is exactly as user entered during login
        abstract Set<String> roles();

        abstract ConfigRepository configRepository();

        boolean isPermitted(String agentId, String permission) {
            if (permission.startsWith("agent:")) {
                return isAgentPermitted(agentId, permission);
            } else {
                return isAdminPermitted(permission);
            }
        }

        boolean isAgentPermitted(String agentId, String permission) {
            checkState(permission.startsWith("agent:"));
            if (offlineViewer()) {
                return !permission.startsWith("agent:config:edit:");
            }
            if (permission.equals("agent:trace")) {
                // special case for now
                return isAgentPermitted(agentId, "agent:transaction:traces")
                        || isAgentPermitted(agentId, "agent:error:traces");
            }
            return isPermitted(SimplePermission.create(agentId, permission));
        }

        boolean isAdminPermitted(String permission) {
            checkState(permission.startsWith("admin:"));
            if (offlineViewer()) {
                return permission.equals("admin:view") || permission.startsWith("admin:view:");
            }
            return isPermitted(SimplePermission.create(permission));
        }

        private boolean isPermitted(SimplePermission permission) {
            for (RoleConfig roleConfig : configRepository().getRoleConfigs()) {
                if (roles().contains(roleConfig.name()) && roleConfig.isPermitted(permission)) {
                    return true;
                }
            }
            return false;
        }
    }
}
