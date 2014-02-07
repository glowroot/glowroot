/*
 * Copyright 2011-2014 the original author or authors.
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
package org.glowroot.plugin.servlet;

import java.util.Locale;
import java.util.regex.Pattern;

import com.google.common.base.Splitter;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;

import org.glowroot.api.PluginServices;
import org.glowroot.api.PluginServices.ConfigListener;

/**
 * @author Trask Stalnaker
 * @since 0.5
 */
class ServletPluginProperties {

    private static final String CAPTURE_REQUEST_PARAMS_PROPERTY_NAME = "captureRequestParameters";
    private static final String MASK_REQUEST_PARAMS_PROPERTY_NAME = "maskRequestParameters";
    private static final String SESSION_USER_ATTRIBUTE_PROPERTY_NAME = "sessionUserAttribute";
    private static final String CAPTURE_SESSION_ATTRIBUTES_PROPERTY_NAME =
            "captureSessionAttributes";
    private static final String CAPTURE_SESSION_ID_PROPERTY_NAME = "captureSessionId";
    private static final String CAPTURE_STARTUP_PROPERTY_NAME = "captureStartup";

    private static final PluginServices pluginServices = PluginServices.get("servlet");

    private static final Splitter splitter = Splitter.on(',').trimResults().omitEmptyStrings();

    private static volatile ImmutableList<Pattern> captureRequestParameters = ImmutableList.of();
    private static volatile ImmutableList<Pattern> maskRequestParameters = ImmutableList.of();

    private static volatile String sessionUserAttributePath = "";
    private static volatile ImmutableSet<String> captureSessionAttributePaths = ImmutableSet.of();
    private static volatile ImmutableSet<String> captureSessionAttributeNames = ImmutableSet.of();
    private static volatile boolean captureSessionId;
    private static volatile boolean captureStartup;

    static {
        pluginServices.registerConfigListener(new ConfigListener() {
            @Override
            public void onChange() {
                updateCache();
            }
        });
        updateCache();
    }

    private ServletPluginProperties() {}

    static ImmutableList<Pattern> captureRequestParameters() {
        return captureRequestParameters;
    }

    static ImmutableList<Pattern> maskRequestParameters() {
        return maskRequestParameters;
    }

    static String sessionUserAttributePath() {
        return sessionUserAttributePath;
    }

    static ImmutableSet<String> captureSessionAttributePaths() {
        return captureSessionAttributePaths;
    }

    // only the first-level attribute names (e.g. "one", "abc") as opposed to full paths (e.g.
    // "one.two", "abc.def") returned by captureSessionAttributePaths()
    static ImmutableSet<String> captureSessionAttributeNames() {
        return captureSessionAttributeNames;
    }

    static boolean captureSessionId() {
        return captureSessionId;
    }

    static boolean captureStartup() {
        return captureStartup;
    }

    private static void updateCache() {
        String captureRequestParametersText =
                pluginServices.getStringProperty(CAPTURE_REQUEST_PARAMS_PROPERTY_NAME);
        ImmutableList.Builder<Pattern> captureParameters = ImmutableList.builder();
        for (String parameter : splitter.split(captureRequestParametersText)) {
            // converted to lower case for case-insensitive matching
            captureParameters.add(buildRegexPattern(parameter.toLowerCase(Locale.ENGLISH)));
        }
        captureRequestParameters = captureParameters.build();

        String maskRequestParametersText =
                pluginServices.getStringProperty(MASK_REQUEST_PARAMS_PROPERTY_NAME);
        ImmutableList.Builder<Pattern> maskParameters = ImmutableList.builder();
        for (String parameter : splitter.split(maskRequestParametersText)) {
            // converted to lower case for case-insensitive matching
            maskParameters.add(buildRegexPattern(parameter.toLowerCase(Locale.ENGLISH)));
        }
        maskRequestParameters = maskParameters.build();

        sessionUserAttributePath =
                pluginServices.getStringProperty(SESSION_USER_ATTRIBUTE_PROPERTY_NAME);
        String captureSessionAttributesText =
                pluginServices.getStringProperty(CAPTURE_SESSION_ATTRIBUTES_PROPERTY_NAME);
        captureSessionAttributePaths = ImmutableSet.copyOf(splitter
                .split(captureSessionAttributesText));
        captureSessionAttributeNames = buildCaptureSessionAttributeNames();
        captureSessionId = pluginServices.getBooleanProperty(CAPTURE_SESSION_ID_PROPERTY_NAME);
        captureStartup = pluginServices.getBooleanProperty(CAPTURE_STARTUP_PROPERTY_NAME);
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
