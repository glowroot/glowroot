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
import io.informant.shaded.google.common.base.Function;
import io.informant.shaded.google.common.collect.ImmutableSet;
import io.informant.shaded.google.common.collect.Iterables;

import java.util.Set;

import javax.annotation.concurrent.ThreadSafe;

/**
 * Convenience methods for accessing servlet plugin property values.
 * 
 * @author Trask Stalnaker
 * @since 0.5
 */
@ThreadSafe
final class ServletPluginProperties {

    private static final String SESSION_USER_ID_ATTRIBUTE_PATH_PROPERTY_NAME =
            "sessionUserIdAttribute";

    // a special single value of "*" means capture all session attributes
    // this can be useful for finding the session attribute that represents the user id
    // TODO support "*.*", "*.*.*", etc
    // TODO support partial wildcards, e.g. "context*"
    private static final String SESSION_ATTRIBUTE_PATHS_PROPERTY_NAME = "sessionAttributes";

    private static final String CAPTURE_SESSION_ID_PROPERTY_NAME = "captureSessionId";
    private static final String CAPTURE_STARTUP_PROPERTY_NAME = "captureStartup";

    private static final PluginServices pluginServices = PluginServices
            .get("io.informant.plugins:servlet-plugin");

    private static volatile String sessionUserIdAttributePath;
    private static volatile ImmutableSet<String> sessionAttributePaths = ImmutableSet.of();
    private static volatile ImmutableSet<String> sessionAttributeNames = ImmutableSet.of();
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

    static String sessionUserIdAttributePath() {
        return sessionUserIdAttributePath;
    }

    static Set<String> sessionAttributePaths() {
        return sessionAttributePaths;
    }

    // only the first-level attribute names (e.g. "one", "abc") as opposed to full paths (e.g.
    // "one.two", "abc.def") returned by getSessionAttributePaths()
    static Set<String> sessionAttributeNames() {
        return sessionAttributeNames;
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
                .getStringProperty(SESSION_USER_ID_ATTRIBUTE_PATH_PROPERTY_NAME);
        String sessionAttributesText = pluginServices
                .getStringProperty(SESSION_ATTRIBUTE_PATHS_PROPERTY_NAME);
        // can't use guava Splitter at the moment due to severe initialization performance of
        // guava-jdk5's CharMatcher on JDK5
        ImmutableSet.Builder<String> paths = ImmutableSet.builder();
        for (String path : sessionAttributesText.split(",")) {
            path = path.trim();
            if (!path.equals("")) {
                paths.add(path);
            }
        }
        // update cached first so that another thread cannot come into this method and get a
        // positive match for text but then get the old cached attributes
        sessionAttributePaths = paths.build();
        sessionAttributeNames = ImmutableSet.copyOf(Iterables.transform(sessionAttributePaths,
                substringBefore('.')));
        captureSessionId = pluginServices.getBooleanProperty(CAPTURE_SESSION_ID_PROPERTY_NAME);
        captureStartup = pluginServices.getBooleanProperty(CAPTURE_STARTUP_PROPERTY_NAME);
    }

    private static Function<String, String> substringBefore(final char separator) {
        return new Function<String, String>() {
            public String apply(String input) {
                int index = input.indexOf(separator);
                if (index == -1) {
                    return input;
                } else {
                    return input.substring(0, index);
                }
            }
        };
    }

    private ServletPluginProperties() {}
}
