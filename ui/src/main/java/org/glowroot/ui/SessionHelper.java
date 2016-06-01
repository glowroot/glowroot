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

import java.util.Locale;
import java.util.Set;

import javax.annotation.Nullable;

import io.netty.handler.codec.http.DefaultFullHttpResponse;
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

import org.glowroot.storage.config.UserConfig;
import org.glowroot.storage.repo.ConfigRepository;

import static io.netty.handler.codec.http.HttpResponseStatus.OK;
import static io.netty.handler.codec.http.HttpVersion.HTTP_1_1;

class SessionHelper {

    private final ConfigRepository configRepository;
    private final LayoutService layoutJsonService;

    SessionHelper(ConfigRepository configRepository, LayoutService layoutJsonService) {
        this.configRepository = configRepository;
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
        Subject subject = SecurityUtils.getSubject();
        try {
            subject.login(new UsernamePasswordToken(username, password));
        } catch (AuthenticationException e) {
            String text = "{\"incorrectLogin\":true}";
            return HttpServices.createJsonResponse(text, OK);
        }
        String text = layoutJsonService.getLayout();
        FullHttpResponse response = HttpServices.createJsonResponse(text, OK);
        Cookie cookie =
                new DefaultCookie("GLOWROOT_SESSION_ID", (String) subject.getSession().getId());
        cookie.setHttpOnly(true);
        cookie.setPath("/");
        response.headers().add(HttpHeaderNames.SET_COOKIE,
                ServerCookieEncoder.STRICT.encode(cookie));
        return response;
    }

    FullHttpResponse signOut() {
        SecurityUtils.getSubject().logout();
        FullHttpResponse response = new DefaultFullHttpResponse(HTTP_1_1, OK);
        deleteSessionCookie(response);
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

    private @Nullable UserConfig getUserConfigCaseInsensitive(String username) {
        for (UserConfig userConfig : configRepository.getUserConfigs()) {
            if (userConfig.username().equalsIgnoreCase(username)) {
                return userConfig;
            }
        }
        return null;
    }
}
