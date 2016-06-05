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

import java.util.Set;

import javax.annotation.Nullable;

import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.cookie.Cookie;
import io.netty.handler.codec.http.cookie.DefaultCookie;
import io.netty.handler.codec.http.cookie.ServerCookieDecoder;
import io.netty.handler.codec.http.cookie.ServerCookieEncoder;
import org.apache.shiro.SecurityUtils;
import org.apache.shiro.authc.AuthenticationException;
import org.apache.shiro.authc.UsernamePasswordToken;
import org.apache.shiro.subject.Subject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.glowroot.storage.config.UserConfig;
import org.glowroot.storage.repo.ConfigRepository;

import static io.netty.handler.codec.http.HttpResponseStatus.OK;

class SessionHelper {

    private static final Logger logger = LoggerFactory.getLogger(SessionHelper.class);

    private final ConfigRepository configRepository;
    private final LayoutService layoutService;

    SessionHelper(ConfigRepository configRepository, LayoutService layoutService) {
        this.configRepository = configRepository;
        this.layoutService = layoutService;
    }

    FullHttpResponse login(String username, String password) throws Exception {
        if (username.equalsIgnoreCase("anonymous")) {
            String text = "{\"incorrectLogin\":true}";
            return HttpServices.createJsonResponse(text, OK);
        }
        UserConfig userConfig = configRepository.getUserConfigCaseInsensitive(username);
        if (userConfig == null) {
            String text = "{\"incorrectLogin\":true}";
            return HttpServices.createJsonResponse(text, OK);
        }
        Subject subject = SecurityUtils.getSubject();
        try {
            subject.login(new UsernamePasswordToken(username, password));
        } catch (AuthenticationException e) {
            logger.debug(e.getMessage(), e);
            String text = "{\"incorrectLogin\":true}";
            return HttpServices.createJsonResponse(text, OK);
        }
        String text = layoutService.getLayout();
        FullHttpResponse response = HttpServices.createJsonResponse(text, OK);
        Cookie cookie =
                new DefaultCookie("GLOWROOT_SESSION_ID", (String) subject.getSession().getId());
        cookie.setHttpOnly(true);
        cookie.setPath("/");
        response.headers().add(HttpHeaderNames.SET_COOKIE,
                ServerCookieEncoder.STRICT.encode(cookie));
        return response;
    }

    void deleteSessionCookie(HttpResponse response) {
        Cookie cookie = new DefaultCookie("GLOWROOT_SESSION_ID", "");
        cookie.setHttpOnly(true);
        cookie.setMaxAge(0);
        cookie.setPath("/");
        response.headers().add(HttpHeaderNames.SET_COOKIE,
                ServerCookieEncoder.STRICT.encode(cookie));
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
}
