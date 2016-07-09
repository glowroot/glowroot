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
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;

import javax.annotation.Nullable;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Splitter;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
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

import org.glowroot.common.util.Clock;
import org.glowroot.storage.config.RoleConfig;
import org.glowroot.storage.config.UserConfig;
import org.glowroot.storage.repo.ConfigRepository;

import static io.netty.handler.codec.http.HttpResponseStatus.INTERNAL_SERVER_ERROR;
import static io.netty.handler.codec.http.HttpResponseStatus.OK;
import static io.netty.handler.codec.http.HttpVersion.HTTP_1_1;
import static java.util.concurrent.TimeUnit.MINUTES;

class HttpSessionManager {

    private static final Logger logger = LoggerFactory.getLogger(HttpSessionManager.class);

    private final boolean fat;
    private final ConfigRepository configRepository;
    private final Clock clock;
    private final LayoutService layoutJsonService;

    private final SecureRandom secureRandom = new SecureRandom();
    private final Map<String, Session> sessions = Maps.newConcurrentMap();

    HttpSessionManager(boolean fat, ConfigRepository configRepository,
            Clock clock, LayoutService layoutJsonService) {
        this.fat = fat;
        this.configRepository = configRepository;
        this.clock = clock;
        this.layoutJsonService = layoutJsonService;
    }

    FullHttpResponse login(String username, String password) throws Exception {
        if (username.toLowerCase(Locale.ENGLISH).equals("anonymous")) {
            String text = "{\"incorrectLogin\":true}";
            return HttpServices.createJsonResponse(text, OK);
        }
        UserConfig userConfig = getUserConfigCaseInsensitive(username);
        if (userConfig == null) {
            String text = "{\"incorrectLogin\":true}";
            return HttpServices.createJsonResponse(text, OK);
        }
        boolean success;
        String existingPasswordHash = userConfig.passwordHash();
        try {
            success = validatePassword(password, existingPasswordHash);
        } catch (GeneralSecurityException e) {
            logger.error(e.getMessage(), e);
            return new DefaultFullHttpResponse(HTTP_1_1, INTERNAL_SERVER_ERROR);
        }
        if (success) {
            Authentication authentication = getAuthentication(userConfig);
            String text = layoutJsonService.getLayout(authentication);
            FullHttpResponse response = HttpServices.createJsonResponse(text, OK);
            createSession(response, username);
            return response;
        } else {
            String text = "{\"incorrectLogin\":true}";
            return HttpServices.createJsonResponse(text, OK);
        }
    }

    void signOut(HttpRequest request) {
        String sessionId = getSessionId(request);
        if (sessionId != null) {
            sessions.remove(sessionId);
        }
    }

    void deleteSessionCookie(HttpResponse response) {
        Cookie cookie = new DefaultCookie("GLOWROOT_SESSION_ID", "");
        cookie.setHttpOnly(true);
        cookie.setMaxAge(0);
        cookie.setPath("/");
        response.headers().add(HttpHeaderNames.SET_COOKIE,
                ServerCookieEncoder.STRICT.encode(cookie));
    }

    void clearAllSessions() {
        sessions.clear();
    }

    Authentication getAuthentication(HttpRequest request) {
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
        UserConfig userConfig = getUserConfigCaseInsensitive(session.username);
        if (userConfig == null) {
            // user must have been just deleted
            return getAnonymousAuthentication();
        }
        return getAuthentication(userConfig);
    }

    @Nullable
    String getSessionId(HttpRequest request) {
        String cookieHeader = request.headers().getAsString(HttpHeaderNames.COOKIE);
        if (cookieHeader == null) {
            return null;
        }
        Set<Cookie> cookies = ServerCookieDecoder.STRICT.decode(cookieHeader);
        for (Cookie cookie : cookies) {
            if (cookie.name().equals("GLOWROOT_SESSION_ID")) {
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
                    .anonymous(true)
                    .username("anonymous")
                    .build();
        }
        return getAuthentication(userConfig);
    }

    private void createSession(HttpResponse response, String username) throws Exception {
        String sessionId = new BigInteger(130, secureRandom).toString(32);
        sessions.put(sessionId, new Session(username));
        Cookie cookie = new DefaultCookie("GLOWROOT_SESSION_ID", sessionId);
        cookie.setHttpOnly(true);
        cookie.setPath("/");
        response.headers().add(HttpHeaderNames.SET_COOKIE,
                ServerCookieEncoder.STRICT.encode(cookie));
        purgeExpiredSessions();
    }

    private Authentication getAuthentication(UserConfig userConfig) {
        Set<String> permissions = Sets.newHashSet();
        for (String roleName : userConfig.roles()) {
            RoleConfig roleConfig = configRepository.getRoleConfig(roleName);
            if (roleConfig == null) {
                continue;
            }
            permissions.addAll(roleConfig.permissions());
        }
        ImmutableAuthentication.Builder authentication = ImmutableAuthentication.builder()
                .fat(fat)
                .anonymous(userConfig.username().equalsIgnoreCase("anonymous"))
                .username(userConfig.username());
        for (String permission : permissions) {
            authentication.addPermissions(new SimplePermission(permission));
        }
        return authentication.build();
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
            if (i.next().getValue().isTimedOut(currentTimeMillis)) {
                i.remove();
            }
        }
    }

    private long getTimeoutMillis() {
        return MINUTES.toMillis(configRepository.getWebConfig().sessionTimeoutMinutes());
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

        private final String username;
        private volatile long lastRequest;

        private Session(String username) {
            this.username = username;
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
        abstract boolean anonymous();
        abstract String username();
        abstract Set<SimplePermission> permissions();

        boolean isPermitted(String agentId, String permission) {
            if (agentId.isEmpty()) {
                return isPermitted(permission);
            } else {
                return isPermitted("agent:" + agentId + permission.substring(5));
            }
        }

        boolean isPermitted(String permission) {
            SimplePermission p = new SimplePermission(permission);
            for (SimplePermission perm : permissions()) {
                if (perm.implies(p)) {
                    return true;
                }
            }
            return false;
        }
    }

    static class SimplePermission {

        private final ImmutableList<String> parts;

        @VisibleForTesting
        SimplePermission(String permission) {
            parts = ImmutableList.copyOf(Splitter.on(':').splitToList(permission));
        }

        @VisibleForTesting
        boolean implies(SimplePermission other) {
            List<String> otherParts = other.parts;
            if (otherParts.size() < parts.size()) {
                return false;
            }
            for (int i = 0; i < parts.size(); i++) {
                String part = parts.get(i);
                String otherPart = otherParts.get(i);
                if (!implies(part, otherPart)) {
                    return false;
                }
            }
            return true;
        }

        private static boolean implies(String part, String otherPart) {
            return part.equals(otherPart) || part.equals("*");
        }
    }
}
