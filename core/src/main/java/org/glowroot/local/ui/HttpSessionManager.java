/*
 * Copyright 2013-2014 the original author or authors.
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
import java.util.Map;
import java.util.Set;

import javax.annotation.Nullable;

import com.google.common.base.Charsets;
import com.google.common.collect.Maps;
import org.jboss.netty.buffer.ChannelBuffers;
import org.jboss.netty.handler.codec.http.Cookie;
import org.jboss.netty.handler.codec.http.CookieDecoder;
import org.jboss.netty.handler.codec.http.CookieEncoder;
import org.jboss.netty.handler.codec.http.DefaultCookie;
import org.jboss.netty.handler.codec.http.DefaultHttpResponse;
import org.jboss.netty.handler.codec.http.HttpRequest;
import org.jboss.netty.handler.codec.http.HttpResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.glowroot.common.Clock;
import org.glowroot.config.ConfigService;

import static java.util.concurrent.TimeUnit.MINUTES;
import static org.jboss.netty.handler.codec.http.HttpHeaders.Names.COOKIE;
import static org.jboss.netty.handler.codec.http.HttpHeaders.Names.SET_COOKIE;
import static org.jboss.netty.handler.codec.http.HttpResponseStatus.INTERNAL_SERVER_ERROR;
import static org.jboss.netty.handler.codec.http.HttpResponseStatus.OK;
import static org.jboss.netty.handler.codec.http.HttpVersion.HTTP_1_1;

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

    HttpResponse login(HttpRequest request) throws IOException {
        boolean success;
        String password = request.getContent().toString(Charsets.ISO_8859_1);
        try {
            success = validatePassword(password,
                    configService.getUserInterfaceConfig().passwordHash());
        } catch (GeneralSecurityException e) {
            logger.error(e.getMessage(), e);
            return new DefaultHttpResponse(HTTP_1_1, INTERNAL_SERVER_ERROR);
        }
        if (success) {
            HttpResponse response = new DefaultHttpResponse(HTTP_1_1, OK);
            createSession(response);
            String text = layoutJsonService.getLayout();
            response.setContent(ChannelBuffers.copiedBuffer(text, Charsets.ISO_8859_1));
            return response;
        } else {
            HttpResponse response = new DefaultHttpResponse(HTTP_1_1, OK);
            String text = "{\"incorrectPassword\":true}";
            response.setContent(ChannelBuffers.copiedBuffer(text, Charsets.ISO_8859_1));
            return response;
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

    HttpResponse signOut(HttpRequest request) {
        String sessionId = getSessionId(request);
        if (sessionId != null) {
            sessionExpirations.remove(sessionId);
        }
        HttpResponse response = new DefaultHttpResponse(HTTP_1_1, OK);
        deleteSession(response);
        return response;
    }

    void createSession(HttpResponse response) {
        CookieEncoder cookieEncoder = new CookieEncoder(true);
        String sessionId = new BigInteger(130, secureRandom).toString(32);
        updateExpiration(sessionId);
        Cookie cookie = new DefaultCookie("GLOWROOT_SESSION_ID", sessionId);
        cookie.setHttpOnly(true);
        cookie.setPath("/");
        cookieEncoder.addCookie(cookie);
        response.headers().add(SET_COOKIE, cookieEncoder.encode());
        // TODO clean up expired sessions
    }

    void deleteSession(HttpResponse response) {
        CookieEncoder cookieEncoder = new CookieEncoder(true);
        Cookie cookie = new DefaultCookie("GLOWROOT_SESSION_ID", "");
        cookie.setHttpOnly(true);
        cookie.setMaxAge(0);
        cookie.setPath("/");
        cookieEncoder.addCookie(cookie);
        response.headers().add(SET_COOKIE, cookieEncoder.encode());
    }

    @Nullable
    String getSessionId(HttpRequest request) {
        String cookieHeader = request.headers().get(COOKIE);
        if (cookieHeader == null) {
            return null;
        }
        Set<Cookie> cookies = new CookieDecoder().decode(cookieHeader);
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
