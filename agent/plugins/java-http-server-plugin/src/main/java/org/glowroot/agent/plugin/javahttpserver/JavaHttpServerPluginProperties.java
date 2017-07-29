/*
 * Copyright 2011-2017 the original author or authors.
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
package org.glowroot.agent.plugin.javahttpserver;

import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

import org.glowroot.agent.plugin.api.Agent;
import org.glowroot.agent.plugin.api.config.ConfigListener;
import org.glowroot.agent.plugin.api.config.ConfigService;

import com.google.common.base.Splitter;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;

class JavaHttpServerPluginProperties {

    private static final String CAPTURE_REQUEST_HEADER_PROPERTY_NAME = "captureRequestHeaders";
    private static final String CAPTURE_REQUEST_REMOTE_ADDR_PROPERTY_NAME =
            "captureRequestRemoteAddr";
    private static final String CAPTURE_REQUEST_REMOTE_HOST_PROPERTY_NAME =
            "captureRequestRemoteHost";
    private static final String CAPTURE_RESPONSE_HEADER_PROPERTY_NAME = "captureResponseHeaders";

    private static final ConfigService configService = Agent.getConfigService("javahttpserver");

    private static final Splitter splitter = Splitter.on(',').trimResults().omitEmptyStrings();

    private static ImmutableList<Pattern> captureRequestHeaders = ImmutableList.of();

    private static boolean captureRequestRemoteAddr;
    private static boolean captureRequestRemoteHost;

    private static ImmutableList<Pattern> captureResponseHeaders = ImmutableList.of();
    private static boolean captureResponseHeadersNonEmpty;

    static {
        configService.registerConfigListener(new ServletPluginConfigListener());
    }

    private JavaHttpServerPluginProperties() {}

    static ImmutableList<Pattern> captureRequestHeaders() {
        return captureRequestHeaders;
    }

    static boolean captureRequestRemoteAddr() {
        return captureRequestRemoteAddr;
    }

    static boolean captureRequestRemoteHost() {
        return captureRequestRemoteHost;
    }

    static ImmutableList<Pattern> captureResponseHeaders() {
        return captureResponseHeaders;
    }

    static boolean captureResponseHeadersNonEmpty() {
        return captureResponseHeadersNonEmpty;
    }

    private static class ServletPluginConfigListener implements ConfigListener {

        @Override
        public void onChange() {
            recalculateProperties();
        }

        private static void recalculateProperties() {
            captureRequestHeaders = buildPatternList(CAPTURE_REQUEST_HEADER_PROPERTY_NAME);
            captureRequestRemoteAddr = configService
                    .getBooleanProperty(CAPTURE_REQUEST_REMOTE_ADDR_PROPERTY_NAME).value();
            captureRequestRemoteHost = configService
                    .getBooleanProperty(CAPTURE_REQUEST_REMOTE_HOST_PROPERTY_NAME).value();
            captureResponseHeaders = buildPatternList(CAPTURE_RESPONSE_HEADER_PROPERTY_NAME);
            captureResponseHeadersNonEmpty = !captureResponseHeaders.isEmpty();
        }

        private static ImmutableList<Pattern> buildPatternList(String propertyName) {
            String captureRequestParametersText =
                    configService.getStringProperty(propertyName).value();
            List<Pattern> captureParameters = Lists.newArrayList();
            for (String parameter : splitter.split(captureRequestParametersText)) {
                // converted to lower case for case-insensitive matching
                captureParameters.add(buildRegexPattern(parameter.toLowerCase(Locale.ENGLISH)));
            }
            return ImmutableList.copyOf(captureParameters);
        }

        private static Pattern buildRegexPattern(String wildcardPattern) {
            // convert * into .* and quote the rest of the text using \Q...\E
            String regex = "\\Q" + wildcardPattern.replace("*", "\\E.*\\Q") + "\\E";
            // strip off unnecessary \\Q\\E in case * appeared at beginning or end of part
            regex = regex.replace("\\Q\\E", "");
            return Pattern.compile(regex);
        }
    }
}
