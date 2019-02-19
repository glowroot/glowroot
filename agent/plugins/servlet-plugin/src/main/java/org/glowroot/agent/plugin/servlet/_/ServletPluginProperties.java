/*
 * Copyright 2011-2019 the original author or authors.
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
package org.glowroot.agent.plugin.servlet._;

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
import org.glowroot.agent.plugin.servlet.DetailCapture;

public class ServletPluginProperties {

    public static final String HTTP_SESSION_ID_ATTR = "::id";

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
    private static boolean captureContentLengthResponseHeader;
    private static boolean captureContentTypeResponseHeader;
    private static boolean captureContentLanguageResponseHeader;

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

    public static List<Pattern> captureRequestParameters() {
        return captureRequestParameters;
    }

    public static List<Pattern> maskRequestParameters() {
        return maskRequestParameters;
    }

    public static List<Pattern> captureRequestHeaders() {
        return captureRequestHeaders;
    }

    public static boolean captureSomeRequestHostAndPortDetail() {
        return someRequestHostAndPortDetail;
    }

    public static boolean captureRequestRemoteAddress() {
        return captureRequestRemoteAddress;
    }

    public static boolean captureRequestRemoteHostname() {
        return captureRequestRemoteHostname;
    }

    public static boolean captureRequestRemotePort() {
        return captureRequestRemotePort;
    }

    public static boolean captureRequestLocalAddress() {
        return captureRequestLocalAddress;
    }

    public static boolean captureRequestLocalHostname() {
        return captureRequestLocalHostname;
    }

    public static boolean captureRequestLocalPort() {
        return captureRequestLocalPort;
    }

    public static boolean captureRequestServerHostname() {
        return captureRequestServerHostname;
    }

    public static boolean captureRequestServerPort() {
        return captureRequestServerPort;
    }

    public static List<Pattern> captureResponseHeaders() {
        return captureResponseHeaders;
    }

    public static boolean captureResponseHeadersNonEmpty() {
        return captureResponseHeadersNonEmpty;
    }

    public static boolean captureContentLengthResponseHeader() {
        return captureContentLengthResponseHeader;
    }

    public static boolean captureContentTypeResponseHeader() {
        return captureContentTypeResponseHeader;
    }

    public static boolean captureContentLanguageResponseHeader() {
        return captureContentLanguageResponseHeader;
    }

    public static @Nullable SessionAttributePath userAttributePath() {
        return userAttributePath;
    }

    public static boolean sessionUserAttributeIsId() {
        return userAttributePath != null && userAttributePath.isSessionId();
    }

    public static List<SessionAttributePath> captureSessionAttributePaths() {
        return captureSessionAttributePaths;
    }

    // only the first-level attribute names (e.g. "one", "abc") as opposed to full paths (e.g.
    // "one.two", "abc.def") returned by captureSessionAttributePaths()
    public static Set<String> captureSessionAttributeNames() {
        return captureSessionAttributeNames;
    }

    public static boolean captureSessionAttributeNamesContainsId() {
        return captureSessionAttributeNamesContainsId;
    }

    public static boolean traceErrorOn4xxResponseCode() {
        return traceErrorOn4xxResponseCode;
    }

    public static class SessionAttributePath {

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

        public String getAttributeName() {
            return attributeName;
        }

        public List<String> getNestedPath() {
            return nestedPath;
        }

        public boolean isWildcard() {
            return wildcard;
        }

        public String getFullPath() {
            return fullPath;
        }

        public boolean isAttributeNameWildcard() {
            return attributeName.equals("*") && nestedPath.isEmpty() && !wildcard;
        }

        public boolean isSessionId() {
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
            captureContentLengthResponseHeader =
                    DetailCapture.matchesOneOf("content-length", captureResponseHeaders);
            captureContentTypeResponseHeader =
                    DetailCapture.matchesOneOf("content-type", captureResponseHeaders);
            captureContentLanguageResponseHeader =
                    DetailCapture.matchesOneOf("content-language", captureResponseHeaders);
            userAttributePath = buildSessionAttributePath(
                    configService.getStringProperty("sessionUserAttribute").value());
            captureSessionAttributePaths = buildSessionAttributePaths(
                    configService.getListProperty("captureSessionAttributes").value());
            captureSessionAttributeNames = buildCaptureSessionAttributeNames();
            captureSessionAttributeNamesContainsId =
                    captureSessionAttributeNames.contains(HTTP_SESSION_ID_ATTR);
            traceErrorOn4xxResponseCode =
                    configService.getBooleanProperty("traceErrorOn4xxResponseCode").value();
        }

        private static List<Pattern> buildPatternList(String propertyName) {
            List<String> values = configService.getListProperty(propertyName).value();
            List<Pattern> patterns = new ArrayList<Pattern>();
            for (String value : values) {
                // converted to lower case for case-insensitive matching
                patterns.add(buildRegexPattern(value.trim().toLowerCase(Locale.ENGLISH)));
            }
            return ImmutableList.copyOf(patterns);
        }

        private static List<SessionAttributePath> buildSessionAttributePaths(
                List<String> sessionAttributes) {
            List<SessionAttributePath> attributePaths = new ArrayList<SessionAttributePath>();
            for (String sessionAttribute : sessionAttributes) {
                attributePaths.add(buildSessionAttributePath(sessionAttribute.trim()));
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
