/*
 * Copyright 2011-2018 the original author or authors.
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

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

import org.glowroot.agent.plugin.api.Agent;
import org.glowroot.agent.plugin.api.checker.Nullable;
import org.glowroot.agent.plugin.api.config.ConfigListener;
import org.glowroot.agent.plugin.api.config.ConfigService;
import org.glowroot.agent.plugin.api.util.ImmutableList;
import org.glowroot.agent.plugin.api.util.ImmutableSet;

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

    private static List<Pattern> captureRequestParameters = Collections.emptyList();
    private static List<Pattern> maskRequestParameters = Collections.emptyList();
    private static List<Pattern> captureRequestHeaders = Collections.emptyList();

    private static boolean captureRequestRemoteAddr;
    private static boolean captureRequestRemoteHost;

    private static List<Pattern> captureResponseHeaders = Collections.emptyList();
    private static boolean captureResponseHeadersNonEmpty;

    private static @Nullable SessionAttributePath userAttributePath;

    private static List<SessionAttributePath> captureSessionAttributePaths =
            Collections.emptyList();
    private static Set<String> captureSessionAttributeNames = Collections.emptySet();
    private static boolean captureSessionAttributeNamesContainsId;

    private static boolean traceErrorOn4xxResponseCode;

    static {
        configService.registerConfigListener(new ServletPluginConfigListener());
    }

    private ServletPluginProperties() {}

    static List<Pattern> captureRequestParameters() {
        return captureRequestParameters;
    }

    static List<Pattern> maskRequestParameters() {
        return maskRequestParameters;
    }

    static List<Pattern> captureRequestHeaders() {
        return captureRequestHeaders;
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

    static boolean captureResponseHeadersNonEmpty() {
        return captureResponseHeadersNonEmpty;
    }

    static @Nullable SessionAttributePath userAttributePath() {
        return userAttributePath;
    }

    static boolean sessionUserAttributeIsId() {
        return userAttributePath != null && userAttributePath.isSessionId();
    }

    static List<SessionAttributePath> captureSessionAttributePaths() {
        return captureSessionAttributePaths;
    }

    // only the first-level attribute names (e.g. "one", "abc") as opposed to full paths (e.g.
    // "one.two", "abc.def") returned by captureSessionAttributePaths()
    static Set<String> captureSessionAttributeNames() {
        return captureSessionAttributeNames;
    }

    static boolean captureSessionAttributeNamesContainsId() {
        return captureSessionAttributeNamesContainsId;
    }

    static boolean traceErrorOn4xxResponseCode() {
        return traceErrorOn4xxResponseCode;
    }

    static class SessionAttributePath {

        private final String attributeName;
        private final List<String> nestedPath;
        private final boolean wildcard;

        private final String fullPath; // attributeName + nestedPath, cached for optimization

        private SessionAttributePath(String attributeName, List<String> nestedPath,
                boolean wildcard, String fullPath) {
            this.attributeName = attributeName;
            this.nestedPath = nestedPath;
            this.wildcard = wildcard;
            this.fullPath = fullPath;
        }

        String getAttributeName() {
            return attributeName;
        }

        List<String> getNestedPath() {
            return nestedPath;
        }

        boolean isWildcard() {
            return wildcard;
        }

        String getFullPath() {
            return fullPath;
        }

        boolean isAttributeNameWildcard() {
            return attributeName.equals("*") && nestedPath.isEmpty() && !wildcard;
        }

        boolean isSessionId() {
            return attributeName.equals(ServletPluginProperties.HTTP_SESSION_ID_ATTR)
                    && nestedPath.isEmpty() && !wildcard;
        }
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
            userAttributePath = buildSessionAttributePath(
                    configService.getStringProperty(SESSION_USER_ATTRIBUTE_PROPERTY_NAME).value());
            captureSessionAttributePaths = buildSessionAttributePaths(configService
                    .getStringProperty(CAPTURE_SESSION_ATTRIBUTES_PROPERTY_NAME).value());
            captureSessionAttributeNames = buildCaptureSessionAttributeNames();
            captureSessionAttributeNamesContainsId =
                    captureSessionAttributeNames.contains(HTTP_SESSION_ID_ATTR);
            traceErrorOn4xxResponseCode =
                    configService.getBooleanProperty(TRACE_ERROR_ON_4XX_RESPONSE_CODE).value();
        }

        private static List<Pattern> buildPatternList(String propertyName) {
            String captureRequestParametersText =
                    configService.getStringProperty(propertyName).value();
            List<Pattern> captureParameters = new ArrayList<Pattern>();
            for (String parameter : Strings.split(captureRequestParametersText, ',')) {
                // converted to lower case for case-insensitive matching
                captureParameters.add(buildRegexPattern(parameter.toLowerCase(Locale.ENGLISH)));
            }
            return ImmutableList.copyOf(captureParameters);
        }

        private static List<SessionAttributePath> buildSessionAttributePaths(
                String sessionAttributes) {
            List<SessionAttributePath> attributePaths = new ArrayList<SessionAttributePath>();
            for (String sessionAttribute : Strings.split(sessionAttributes, ',')) {
                attributePaths.add(buildSessionAttributePath(sessionAttribute));
            }
            return ImmutableList.copyOf(attributePaths);
        }

        private static SessionAttributePath buildSessionAttributePath(String sessionAttribute) {
            boolean wildcard = sessionAttribute.endsWith(".*");
            String sessionAttr = sessionAttribute;
            if (wildcard) {
                sessionAttr = sessionAttribute.substring(0, sessionAttribute.length() - 2);
            }
            int index = sessionAttr.indexOf('.');
            if (index == -1) {
                return new SessionAttributePath(sessionAttr, Collections.<String>emptyList(),
                        wildcard, sessionAttr);
            } else {
                String attributeName = sessionAttr.substring(0, index);
                String remaining = sessionAttr.substring(index + 1);
                List<String> nestedPath = Strings.split(remaining, '.');
                return new SessionAttributePath(attributeName, nestedPath, wildcard, sessionAttr);
            }
        }

        private static Set<String> buildCaptureSessionAttributeNames() {
            Set<String> names = new HashSet<String>();
            for (SessionAttributePath sessionAttributePath : captureSessionAttributePaths) {
                names.add(sessionAttributePath.attributeName);
            }
            return ImmutableSet.copyOf(names);
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
