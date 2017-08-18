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
package org.glowroot.agent.plugin.servlet;

import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

import com.google.common.base.Splitter;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Lists;

import org.glowroot.agent.plugin.api.Agent;
import org.glowroot.agent.plugin.api.config.ConfigListener;
import org.glowroot.agent.plugin.api.config.ConfigService;

class ServletPluginProperties {

    static final String HTTP_SESSION_ID_ATTR = "::id";

    private static final String CAPTURE_REQUEST_PARAMS_PROPERTY_NAME = "captureRequestParameters";
    private static final String MASK_REQUEST_PARAMS_PROPERTY_NAME = "maskRequestParameters";
    private static final String CAPTURE_REQUEST_HEADER_PROPERTY_NAME = "captureRequestHeaders";
    private static final String CAPTURE_REQUEST_REMOTE_ADDR_PROPERTY_NAME =
            "captureRequestRemoteAddr";
    private static final String CAPTURE_REQUEST_REMOTE_HOST_PROPERTY_NAME =
            "captureRequestRemoteHost";
    private static final String CAPTURE_RESPONSE_HEADER_PROPERTY_NAME = "captureResponseHeaders";
    private static final String SESSION_USER_ATTRIBUTE_PROPERTY_NAME = "sessionUserAttribute";
    private static final String CAPTURE_SESSION_ATTRIBUTES_PROPERTY_NAME =
            "captureSessionAttributes";
    private static final String TRACE_ERROR_ON_4XX_RESPONSE_CODE = "traceErrorOn4xxResponseCode";

    private static final ConfigService configService = Agent.getConfigService("servlet");

    private static final Splitter splitter = Splitter.on(',').trimResults().omitEmptyStrings();

    private static ImmutableList<Pattern> captureRequestParameters = ImmutableList.of();
    private static ImmutableList<Pattern> maskRequestParameters = ImmutableList.of();
    private static ImmutableList<Pattern> captureRequestHeaders = ImmutableList.of();

    private static boolean captureRequestRemoteAddr;
    private static boolean captureRequestRemoteHost;

    private static ImmutableList<Pattern> captureResponseHeaders = ImmutableList.of();
    private static boolean captureResponseHeadersNonEmpty;

    private static String sessionUserAttributePath = "";
    private static boolean sessionUserAttributeIsId;

    private static ImmutableSet<String> captureSessionAttributePaths = ImmutableSet.of();
    private static ImmutableSet<String> captureSessionAttributeNames = ImmutableSet.of();
    private static boolean captureSessionAttributeNamesContainsId;

    private static boolean traceErrorOn4xxResponseCode;

    static {
        configService.registerConfigListener(new ServletPluginConfigListener());
    }

    private ServletPluginProperties() {}

    static ImmutableList<Pattern> captureRequestParameters() {
        return captureRequestParameters;
    }

    static ImmutableList<Pattern> maskRequestParameters() {
        return maskRequestParameters;
    }

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

    static String sessionUserAttributePath() {
        return sessionUserAttributePath;
    }

    static boolean sessionUserAttributeIsId() {
        return sessionUserAttributeIsId;
    }

    static ImmutableSet<String> captureSessionAttributePaths() {
        return captureSessionAttributePaths;
    }

    // only the first-level attribute names (e.g. "one", "abc") as opposed to full paths (e.g.
    // "one.two", "abc.def") returned by captureSessionAttributePaths()
    static ImmutableSet<String> captureSessionAttributeNames() {
        return captureSessionAttributeNames;
    }

    static boolean captureSessionAttributeNamesContainsId() {
        return captureSessionAttributeNamesContainsId;
    }

    static boolean traceErrorOn4xxResponseCode() {
        return traceErrorOn4xxResponseCode;
    }

    private static class ServletPluginConfigListener implements ConfigListener {

        @Override
        public void onChange() {
            recalculateProperties();
        }

        private static void recalculateProperties() {
            captureRequestParameters = buildPatternList(CAPTURE_REQUEST_PARAMS_PROPERTY_NAME);
            maskRequestParameters = buildPatternList(MASK_REQUEST_PARAMS_PROPERTY_NAME);
            captureRequestHeaders = buildPatternList(CAPTURE_REQUEST_HEADER_PROPERTY_NAME);
            captureRequestRemoteAddr = configService
                    .getBooleanProperty(CAPTURE_REQUEST_REMOTE_ADDR_PROPERTY_NAME).value();
            captureRequestRemoteHost = configService
                    .getBooleanProperty(CAPTURE_REQUEST_REMOTE_HOST_PROPERTY_NAME).value();
            captureResponseHeaders = buildPatternList(CAPTURE_RESPONSE_HEADER_PROPERTY_NAME);
            captureResponseHeadersNonEmpty = !captureResponseHeaders.isEmpty();
            sessionUserAttributePath = configService
                    .getStringProperty(SESSION_USER_ATTRIBUTE_PROPERTY_NAME).value();
            sessionUserAttributeIsId = sessionUserAttributePath.equals(HTTP_SESSION_ID_ATTR);
            String captureSessionAttributesText = configService
                    .getStringProperty(CAPTURE_SESSION_ATTRIBUTES_PROPERTY_NAME).value();
            captureSessionAttributePaths =
                    ImmutableSet.copyOf(splitter.split(captureSessionAttributesText));
            captureSessionAttributeNames = buildCaptureSessionAttributeNames();
            captureSessionAttributeNamesContainsId =
                    captureSessionAttributeNames.contains(HTTP_SESSION_ID_ATTR);
            traceErrorOn4xxResponseCode =
                    configService.getBooleanProperty(TRACE_ERROR_ON_4XX_RESPONSE_CODE).value();
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

        private static ImmutableSet<String> buildCaptureSessionAttributeNames() {
            ImmutableSet.Builder<String> names = ImmutableSet.builder();
            for (String captureSessionAttributePath : captureSessionAttributePaths) {
                int index = captureSessionAttributePath.indexOf('.');
                if (index == -1) {
                    names.add(captureSessionAttributePath);
                } else {
                    names.add(captureSessionAttributePath.substring(0, index));
                }
            }
            return names.build();
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
