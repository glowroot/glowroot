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
package org.glowroot.local.ui;

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
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.handler.codec.http.Cookie;
import io.netty.handler.codec.http.CookieDecoder;
import io.netty.handler.codec.http.DefaultCookie;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.ServerCookieEncoder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.glowroot.common.Clock;
import org.glowroot.config.ConfigService;

import static io.netty.handler.codec.http.HttpHeaders.Names.COOKIE;
import static io.netty.handler.codec.http.HttpHeaders.Names.SET_COOKIE;
import static io.netty.handler.codec.http.HttpResponseStatus.INTERNAL_SERVER_ERROR;
import static io.netty.handler.codec.http.HttpResponseStatus.OK;
import static io.netty.handler.codec.http.HttpVersion.HTTP_1_1;
import static java.util.concurrent.TimeUnit.MINUTES;

class HttpSessionManager {

    private static final Logger logger = LoggerFactory.getLogger(HttpSessionManager.class);

    private final ConfigService configService;
    private final Clock clock;
    private final LayoutJsonService layoutJsonService;

    private final SecureRandom secureRandom = new SecureRandom();
    private final Map<String, Long> sessionExpirations = Maps.newConcurrentMap();

    HttpSessionManager(ConfigService configService, Clock clock,
            LayoutJsonService layoutJsonService) {
        this.configService = configService;
        this.clock = clock;
        this.layoutJsonService = layoutJsonService;
    }

    FullHttpResponse login(FullHttpRequest request) throws IOException {
        boolean success;
        String password = request.content().toString(Charsets.ISO_8859_1);
        try {
            success = validatePassword(password,
                    configService.getUserInterfaceConfig().passwordHash());
        } catch (GeneralSecurityException e) {
            logger.error(e.getMessage(), e);
            return new DefaultFullHttpResponse(HTTP_1_1, INTERNAL_SERVER_ERROR);
        }
        if (success) {
            String text = layoutJsonService.getLayout();
            ByteBuf content = Unpooled.copiedBuffer(text, Charsets.ISO_8859_1);
            FullHttpResponse response = new DefaultFullHttpResponse(HTTP_1_1, OK, content);
            createSession(response);
            return response;
        } else {
            String text = "{\"incorrectPassword\":true}";
            ByteBuf content = Unpooled.copiedBuffer(text, Charsets.ISO_8859_1);
            return new DefaultFullHttpResponse(HTTP_1_1, OK, content);
        }
    }

    boolean needsAuthentication(HttpRequest request) {
        if (!configService.getUserInterfaceConfig().passwordEnabled()) {
            return false;
        }
        String sessionId = getSessionId(request);
        if (sessionId == null) {
            return true;
        }
        Long expires = sessionExpirations.get(sessionId);
        if (expires == null) {
            return true;
        }
        if (expires < clock.currentTimeMillis()) {
            return true;
        } else {
            // session is valid and not expired, update expiration
            updateExpiration(sessionId);
            return false;
        }
    }

    FullHttpResponse signOut(HttpRequest request) {
        String sessionId = getSessionId(request);
        if (sessionId != null) {
            sessionExpirations.remove(sessionId);
        }
        FullHttpResponse response = new DefaultFullHttpResponse(HTTP_1_1, OK);
        deleteSessionCookie(response);
        return response;
    }

    void createSession(HttpResponse response) {
        String sessionId = new BigInteger(130, secureRandom).toString(32);
        updateExpiration(sessionId);
        Cookie cookie = new DefaultCookie("GLOWROOT_SESSION_ID", sessionId);
        cookie.setHttpOnly(true);
        cookie.setPath("/");
        response.headers().add(SET_COOKIE, ServerCookieEncoder.encode(cookie));
        purgeExpiredSessions();
    }

    void deleteSessionCookie(HttpResponse response) {
        Cookie cookie = new DefaultCookie("GLOWROOT_SESSION_ID", "");
        cookie.setHttpOnly(true);
        cookie.setMaxAge(0);
        cookie.setPath("/");
        response.headers().add(SET_COOKIE, ServerCookieEncoder.encode(cookie));
    }

    void clearAllSessions() {
        sessionExpirations.clear();
    }

    @Nullable
    String getSessionId(HttpRequest request) {
        String cookieHeader = request.headers().get(COOKIE);
        if (cookieHeader == null) {
            return null;
        }
        Set<Cookie> cookies = CookieDecoder.decode(cookieHeader);
        for (Cookie cookie : cookies) {
            if (cookie.getName().equals("GLOWROOT_SESSION_ID")) {
                return cookie.getValue();
            }
        }
        return null;
    }

    private void updateExpiration(String sessionId) {
        int timeoutMinutes = configService.getUserInterfaceConfig().sessionTimeoutMinutes();
        if (timeoutMinutes == 0) {
            sessionExpirations.put(sessionId, Long.MAX_VALUE);
        } else {
            sessionExpirations.put(sessionId,
                    clock.currentTimeMillis() + MINUTES.toMillis(timeoutMinutes));
        }
    }

    private void purgeExpiredSessions() {
        Iterator<Entry<String, Long>> i = sessionExpirations.entrySet().iterator();
        while (i.hasNext()) {
            if (i.next().getValue() < clock.currentTimeMillis()) {
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
