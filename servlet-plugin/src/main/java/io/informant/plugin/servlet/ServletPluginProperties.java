/**
 * Copyright 2011-2013 the original author or authors.
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
package io.informant.plugin.servlet;

import io.informant.api.PluginServices;
import io.informant.api.PluginServices.ConfigListener;
import io.informant.shaded.google.common.collect.ImmutableSet;

/**
 * Convenience methods for accessing servlet plugin property values.
 * 
 * @author Trask Stalnaker
 * @since 0.5
 */
class ServletPluginProperties {

    private static final String SESSION_USER_ID_ATTRIBUTE_PROPERTY_NAME =
            "sessionUserIdAttribute";

    // a special single value of "*" means capture all session attributes
    // this can be useful for finding the session attribute that represents the user id
    // TODO support "*.*", "*.*.*", etc
    // TODO support partial wildcards, e.g. "context*"
    private static final String CAPTURE_SESSION_ATTRIBUTES_PROPERTY_NAME =
            "captureSessionAttributes";

    private static final String CAPTURE_SESSION_ID_PROPERTY_NAME = "captureSessionId";
    private static final String CAPTURE_STARTUP_PROPERTY_NAME = "captureStartup";

    private static final PluginServices pluginServices =
            PluginServices.get("io.informant.plugins:servlet-plugin");

    private static volatile String sessionUserIdAttributePath;
    private static volatile ImmutableSet<String> captureSessionAttributePaths = ImmutableSet.of();
    private static volatile ImmutableSet<String> captureSessionAttributeNames = ImmutableSet.of();
    private static volatile boolean captureSessionId;
    private static volatile boolean captureStartup;

    static {
        pluginServices.registerConfigListener(new ConfigListener() {
            public void onChange() {
                updateCache();
            }
        });
        updateCache();
    }

    private ServletPluginProperties() {}

    static String sessionUserIdAttributePath() {
        return sessionUserIdAttributePath;
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

    public static void setCaptureStartup(boolean captureStartup) {
        ServletPluginProperties.captureStartup = captureStartup;
    }

    private static void updateCache() {
        sessionUserIdAttributePath = pluginServices
                .getStringProperty(SESSION_USER_ID_ATTRIBUTE_PROPERTY_NAME);
        String captureSessionAttributesText = pluginServices
                .getStringProperty(CAPTURE_SESSION_ATTRIBUTES_PROPERTY_NAME);
        // can't use guava Splitter at the moment due to severe initialization performance of
        // guava-jdk5's CharMatcher on JDK5
        ImmutableSet.Builder<String> paths = ImmutableSet.builder();
        for (String path : captureSessionAttributesText.split(",")) {
            path = path.trim();
            if (!path.equals("")) {
                paths.add(path);
            }
        }
        // update cached first so that another thread cannot come into this method and get a
        // positive match for text but then get the old cached attributes
        captureSessionAttributePaths = paths.build();
        captureSessionAttributeNames = buildCaptureSessionAttributeNames();
        captureSessionId = pluginServices.getBooleanProperty(CAPTURE_SESSION_ID_PROPERTY_NAME);
        captureStartup = pluginServices.getBooleanProperty(CAPTURE_STARTUP_PROPERTY_NAME);
    }

    private static ImmutableSet<String> buildCaptureSessionAttributeNames() {
        ImmutableSet.Builder<String> captureSessionAttributeNames = ImmutableSet.builder();
        for (String captureSessionAttributePath : captureSessionAttributePaths) {
            int index = captureSessionAttributePath.indexOf('.');
            if (index == -1) {
                captureSessionAttributeNames.add(captureSessionAttributePath);
            } else {
                captureSessionAttributeNames.add(captureSessionAttributePath.substring(0, index));
            }
        }
        return captureSessionAttributeNames.build();
    }
}
