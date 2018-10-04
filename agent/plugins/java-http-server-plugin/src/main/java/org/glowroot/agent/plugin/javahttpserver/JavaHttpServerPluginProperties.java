/*
 * Copyright 2017-2018 the original author or authors.
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

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

import org.glowroot.agent.plugin.api.Agent;
import org.glowroot.agent.plugin.api.config.ConfigListener;
import org.glowroot.agent.plugin.api.config.ConfigService;
import org.glowroot.agent.plugin.api.util.ImmutableList;

class JavaHttpServerPluginProperties {

    private static final String CAPTURE_REQUEST_HEADER_PROPERTY_NAME = "captureRequestHeaders";
    private static final String MASK_REQUEST_HEADER_PROPERTY_NAME = "maskRequestHeaders";
    private static final String CAPTURE_REQUEST_REMOTE_ADDR_PROPERTY_NAME =
            "captureRequestRemoteAddr";
    private static final String CAPTURE_REQUEST_REMOTE_HOST_PROPERTY_NAME =
            "captureRequestRemoteHost";
    private static final String CAPTURE_RESPONSE_HEADER_PROPERTY_NAME = "captureResponseHeaders";
    private static final String TRACE_ERROR_ON_4XX_RESPONSE_CODE = "traceErrorOn4xxResponseCode";

    private static final ConfigService configService = Agent.getConfigService("java-http-server");

    private static List<Pattern> captureRequestHeaders = Collections.emptyList();
    private static List<Pattern> maskRequestHeaders = Collections.emptyList();

    private static boolean captureRequestRemoteAddr;
    private static boolean captureRequestRemoteHost;

    private static List<Pattern> captureResponseHeaders = Collections.emptyList();

    private static boolean traceErrorOn4xxResponseCode;

    static {
        configService.registerConfigListener(new JavaHttpServerPluginConfigListener());
    }

    private JavaHttpServerPluginProperties() {}

    static List<Pattern> captureRequestHeaders() {
        return captureRequestHeaders;
    }

    static List<Pattern> maskRequestHeaders() {
        return maskRequestHeaders;
    }

    static boolean captureRequestRemoteAddr() {
        return captureRequestRemoteAddr;
    }

    static boolean captureRequestRemoteHost() {
        return captureRequestRemoteHost;
    }

    static List<Pattern> captureResponseHeaders() {
        return captureResponseHeaders;
    }

    static boolean traceErrorOn4xxResponseCode() {
        return traceErrorOn4xxResponseCode;
    }

    private static class JavaHttpServerPluginConfigListener implements ConfigListener {

        @Override
        public void onChange() {
            recalculateProperties();
        }

        private static void recalculateProperties() {
            captureRequestHeaders = buildPatternList(CAPTURE_REQUEST_HEADER_PROPERTY_NAME);
            maskRequestHeaders = buildPatternList(MASK_REQUEST_HEADER_PROPERTY_NAME);
            captureRequestRemoteAddr = configService
                    .getBooleanProperty(CAPTURE_REQUEST_REMOTE_ADDR_PROPERTY_NAME).value();
            captureRequestRemoteHost = configService
                    .getBooleanProperty(CAPTURE_REQUEST_REMOTE_HOST_PROPERTY_NAME).value();
            captureResponseHeaders = buildPatternList(CAPTURE_RESPONSE_HEADER_PROPERTY_NAME);
            traceErrorOn4xxResponseCode =
                    configService.getBooleanProperty(TRACE_ERROR_ON_4XX_RESPONSE_CODE).value();
        }

        private static List<Pattern> buildPatternList(String propertyName) {
            List<String> items = configService.getListProperty(propertyName).value();
            List<Pattern> patterns = new ArrayList<Pattern>();
            for (String parameter : items) {
                // converted to lower case for case-insensitive matching
                patterns.add(buildRegexPattern(parameter.trim().toLowerCase(Locale.ENGLISH)));
            }
            return ImmutableList.copyOf(patterns);
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
