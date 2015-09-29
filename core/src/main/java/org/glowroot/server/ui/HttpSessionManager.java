/*
 * Copyright 2013-2015 the original author or authors.
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
package org.glowroot.server.ui;

import java.io.IOException;
import java.math.BigInteger;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Iterator;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;

import javax.annotation.Nullable;

import com.google.common.base.Charsets;
import com.google.common.collect.Maps;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.cookie.Cookie;
import io.netty.handler.codec.http.cookie.DefaultCookie;
import io.netty.handler.codec.http.cookie.ServerCookieDecoder;
import io.netty.handler.codec.http.cookie.ServerCookieEncoder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.glowroot.common.util.Clock;
import org.glowroot.server.repo.ConfigRepository;
import org.glowroot.server.repo.config.UserInterfaceConfig.AnonymousAccess;

import static io.netty.handler.codec.http.HttpResponseStatus.INTERNAL_SERVER_ERROR;
import static io.netty.handler.codec.http.HttpResponseStatus.OK;
import static io.netty.handler.codec.http.HttpVersion.HTTP_1_1;
import static java.util.concurrent.TimeUnit.MINUTES;

class HttpSessionManager {

    private static final Logger logger = LoggerFactory.getLogger(HttpSessionManager.class);

    private final ConfigRepository configRepository;
    private final Clock clock;
    private final LayoutService layoutJsonService;

    private final SecureRandom secureRandom = new SecureRandom();
    private final Map<String, Long> adminSessionExpirations = Maps.newConcurrentMap();
    private final Map<String, Long> readOnlySessionExpirations = Maps.newConcurrentMap();

    HttpSessionManager(ConfigRepository configRepository, Clock clock,
            LayoutService layoutJsonService) {
        this.configRepository = configRepository;
        this.clock = clock;
        this.layoutJsonService = layoutJsonService;
    }

    FullHttpResponse login(FullHttpRequest request, boolean admin) throws IOException {
        boolean success;
        String password = request.content().toString(Charsets.ISO_8859_1);
        String existingPasswordHash;
        if (admin) {
            existingPasswordHash = configRepository.getUserInterfaceConfig().adminPasswordHash();
        } else {
            existingPasswordHash = configRepository.getUserInterfaceConfig().readOnlyPasswordHash();
        }
        try {
            success = validatePassword(password, existingPasswordHash);
        } catch (GeneralSecurityException e) {
            logger.error(e.getMessage(), e);
            return new DefaultFullHttpResponse(HTTP_1_1, INTERNAL_SERVER_ERROR);
        }
        if (success) {
            String text = layoutJsonService.getLayout();
            FullHttpResponse response = HttpServices.createJsonResponse(text, OK);
            createSession(response, admin);
            return response;
        } else {
            String text = "{\"incorrectPassword\":true}";
            return HttpServices.createJsonResponse(text, OK);
        }
    }

    boolean hasReadAccess(HttpRequest request) {
        if (configRepository.getUserInterfaceConfig().anonymousAccess() != AnonymousAccess.NONE) {
            return true;
        }
        String sessionId = getSessionId(request);
        if (sessionId == null) {
            return false;
        }
        if (isValidNonExpired(sessionId, true)) {
            return true;
        }
        if (isValidNonExpired(sessionId, false)) {
            return true;
        }
        return false;
    }

    boolean hasAdminAccess(HttpRequest request) {
        if (configRepository.getUserInterfaceConfig().anonymousAccess() == AnonymousAccess.ADMIN) {
            // anonymous is ok
            return true;
        }
        // anonymous is not ok
        String sessionId = getSessionId(request);
        if (sessionId == null) {
            return false;
        }
        return isValidNonExpired(sessionId, true);
    }

    @Nullable
    String getAuthenticatedUser(HttpRequest request) {
        String sessionId = getSessionId(request);
        if (sessionId == null) {
            return null;
        }
        if (isValidNonExpired(sessionId, true)) {
            return "admin";
        }
        if (isValidNonExpired(sessionId, false)) {
            return "read-only";
        }
        return null;
    }

    FullHttpResponse signOut(HttpRequest request) {
        String sessionId = getSessionId(request);
        if (sessionId != null) {
            adminSessionExpirations.remove(sessionId);
            readOnlySessionExpirations.remove(sessionId);
        }
        FullHttpResponse response = new DefaultFullHttpResponse(HTTP_1_1, OK);
        deleteSessionCookie(response);
        return response;
    }

    void createSession(HttpResponse response, boolean admin) {
        String sessionId = new BigInteger(130, secureRandom).toString(32);
        updateSessionExpiration(sessionId, admin);
        Cookie cookie = new DefaultCookie("GLOWROOT_SESSION_ID", sessionId);
        cookie.setHttpOnly(true);
        cookie.setPath("/");
        response.headers().add(HttpHeaderNames.SET_COOKIE,
                ServerCookieEncoder.STRICT.encode(cookie));
        purgeExpiredSessions();
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
        adminSessionExpirations.clear();
        readOnlySessionExpirations.clear();
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

    private void purgeExpiredSessions() {
        long currentTimeMillis = clock.currentTimeMillis();
        purgeExpiredSessions(currentTimeMillis, adminSessionExpirations);
        purgeExpiredSessions(currentTimeMillis, readOnlySessionExpirations);
    }

    private boolean isValidNonExpired(String sessionId, boolean admin) {
        Map<String, Long> sessionExpirations =
                admin ? adminSessionExpirations : readOnlySessionExpirations;
        Long expires = sessionExpirations.get(sessionId);
        if (expires == null || clock.currentTimeMillis() > expires) {
            return false;
        }
        // session is valid and not expired, update expiration
        updateSessionExpiration(sessionId, admin);
        return true;
    }

    private void updateSessionExpiration(String sessionId, boolean admin) {
        Map<String, Long> sessionExpirations =
                admin ? adminSessionExpirations : readOnlySessionExpirations;
        int timeoutMinutes = configRepository.getUserInterfaceConfig().sessionTimeoutMinutes();
        if (timeoutMinutes == 0) {
            sessionExpirations.put(sessionId, Long.MAX_VALUE);
        } else {
            sessionExpirations.put(sessionId,
                    clock.currentTimeMillis() + MINUTES.toMillis(timeoutMinutes));
        }
    }

    private static void purgeExpiredSessions(long currentTimeMillis,
            Map<String, Long> sessionExpirations) {
        Iterator<Entry<String, Long>> i = sessionExpirations.entrySet().iterator();
        while (i.hasNext()) {
            if (i.next().getValue() < currentTimeMillis) {
                i.remove();
            }
        }
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
}
