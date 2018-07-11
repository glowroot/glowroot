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

    private static final ConfigService configService = Agent.getConfigService("servlet");

    private static List<Pattern> captureRequestParameters = Collections.emptyList();
    private static List<Pattern> maskRequestParameters = Collections.emptyList();
    private static List<Pattern> captureRequestHeaders = Collections.emptyList();

    private static boolean someRequestHostAndPortDetail;
    private static boolean captureRequestRemoteAddress;
    private static boolean captureRequestRemoteHostname;
    private static boolean captureRequestRemotePort;
    private static boolean captureRequestLocalAddress;
    private static boolean captureRequestLocalHostname;
    private static boolean captureRequestLocalPort;
    private static boolean captureRequestServerHostname;
    private static boolean captureRequestServerPort;

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

    static boolean captureSomeRequestHostAndPortDetail() {
        return someRequestHostAndPortDetail;
    }

    static boolean captureRequestRemoteAddress() {
        return captureRequestRemoteAddress;
    }

    static boolean captureRequestRemoteHostname() {
        return captureRequestRemoteHostname;
    }

    static boolean captureRequestRemotePort() {
        return captureRequestRemotePort;
    }

    static boolean captureRequestLocalAddress() {
        return captureRequestLocalAddress;
    }

    static boolean captureRequestLocalHostname() {
        return captureRequestLocalHostname;
    }

    static boolean captureRequestLocalPort() {
        return captureRequestLocalPort;
    }

    static boolean captureRequestServerHostname() {
        return captureRequestServerHostname;
    }

    static boolean captureRequestServerPort() {
        return captureRequestServerPort;
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
            captureRequestParameters = buildPatternList("captureRequestParameters");
            maskRequestParameters = buildPatternList("maskRequestParameters");
            captureRequestHeaders = buildPatternList("captureRequestHeaders");
            captureRequestRemoteAddress =
                    configService.getBooleanProperty("captureRequestRemoteAddr").value();
            captureRequestRemoteHostname =
                    configService.getBooleanProperty("captureRequestRemoteHostname").value();
            captureRequestRemotePort =
                    configService.getBooleanProperty("captureRequestRemotePort").value();
            captureRequestLocalAddress =
                    configService.getBooleanProperty("captureRequestLocalAddr").value();
            captureRequestLocalHostname =
                    configService.getBooleanProperty("captureRequestLocalHostname").value();
            captureRequestLocalPort =
                    configService.getBooleanProperty("captureRequestLocalPort").value();
            captureRequestServerHostname =
                    configService.getBooleanProperty("captureRequestServerHostname").value();
            captureRequestServerPort =
                    configService.getBooleanProperty("captureRequestServerPort").value();
            someRequestHostAndPortDetail =
                    captureRequestRemoteAddress || captureRequestRemoteHostname
                            || captureRequestRemotePort || captureRequestLocalAddress
                            || captureRequestLocalHostname || captureRequestLocalPort
                            || captureRequestServerHostname || captureRequestServerPort;
            captureResponseHeaders = buildPatternList("captureResponseHeaders");
            captureResponseHeadersNonEmpty = !captureResponseHeaders.isEmpty();
            userAttributePath = buildSessionAttributePath(
                    configService.getStringProperty("sessionUserAttribute").value());
            captureSessionAttributePaths = buildSessionAttributePaths(configService
                    .getStringProperty("captureSessionAttributes").value());
            captureSessionAttributeNames = buildCaptureSessionAttributeNames();
            captureSessionAttributeNamesContainsId =
                    captureSessionAttributeNames.contains(HTTP_SESSION_ID_ATTR);
            traceErrorOn4xxResponseCode =
                    configService.getBooleanProperty("traceErrorOn4xxResponseCode").value();
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
