/**
 * Copyright 2011-2012 the original author or authors.
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
package org.informantproject.plugin.servlet;

import java.util.Set;

import javax.annotation.Nullable;

import org.informantproject.api.PluginServices;
import org.informantproject.shaded.google.common.base.Function;
import org.informantproject.shaded.google.common.base.Objects;
import org.informantproject.shaded.google.common.base.Splitter;
import org.informantproject.shaded.google.common.collect.ImmutableSet;
import org.informantproject.shaded.google.common.collect.Iterables;

/**
 * Convenience methods for accessing servlet plugin property values.
 * 
 * @author Trask Stalnaker
 * @since 0.5
 */
final class ServletPluginProperties {

    private static final String SESSION_USERNAME_ATTRIBUTE_PATH_PROPERTY_NAME =
            "sessionUsernameAttribute";

    // a special single value of "*" means capture all session attributes
    // this can be useful for finding the session attribute that represents the username
    // TODO support "*.*", "*.*.*", etc
    // TODO support partial wildcards, e.g. "context*"
    private static final String SESSION_ATTRIBUTE_PATHS_PROPERTY_NAME = "sessionAttributes";

    private static final PluginServices pluginServices = PluginServices
            .get("org.informantproject.plugins:servlet-plugin");

    // optimization
    private static volatile Set<String> cachedSessionAttributePaths = ImmutableSet.of();
    private static volatile Set<String> cachedSessionAttributeNames = ImmutableSet.of();
    private static volatile boolean isCaptureAllSessionAttributes = false;
    private static volatile String cachedSessionAttributesText;

    @Nullable
    static String sessionUsernameAttributePath() {
        return pluginServices.getStringProperty(SESSION_USERNAME_ATTRIBUTE_PATH_PROPERTY_NAME);
    }

    static Set<String> sessionAttributePaths() {
        checkCache();
        return cachedSessionAttributePaths;
    }

    // only the first-level attribute names (e.g. "one", "abc") as opposed to full paths (e.g.
    // "one.two", "abc.def") returned by getSessionAttributePaths()
    static Set<String> sessionAttributeNames() {
        checkCache();
        return cachedSessionAttributeNames;
    }

    static boolean captureAllSessionAttributes() {
        checkCache();
        return isCaptureAllSessionAttributes;
    }

    private static void checkCache() {
        String sessionAttributesText = pluginServices
                .getStringProperty(SESSION_ATTRIBUTE_PATHS_PROPERTY_NAME);
        if (!Objects.equal(sessionAttributesText, cachedSessionAttributesText)) {
            if (sessionAttributesText == null) {
                // update cached first so that another thread cannot come into this method and get a
                // positive match for text but then get the old cached attributes
                cachedSessionAttributePaths = ImmutableSet.of();
                cachedSessionAttributeNames = ImmutableSet.of();
                isCaptureAllSessionAttributes = false;
                cachedSessionAttributesText = null;
            } else {
                Iterable<String> paths = Splitter.on(',').split(sessionAttributesText);
                // update cached first so that another thread cannot come into this method and get a
                // positive match for text but then get the old cached attributes
                cachedSessionAttributePaths = ImmutableSet.copyOf(paths);
                cachedSessionAttributeNames = ImmutableSet.copyOf(Iterables.transform(paths,
                        substringBefore('.')));
                isCaptureAllSessionAttributes = cachedSessionAttributePaths.size() == 1
                        && cachedSessionAttributePaths.iterator().next().equals("*");
                cachedSessionAttributesText = sessionAttributesText;
            }
        }
    }

    private static Function<String, String> substringBefore(final char separator) {
        return new Function<String, String>() {
            public String apply(String input) {
                return Splitter.on(separator).split(input).iterator().next();
            }
        };
    }

    private ServletPluginProperties() {}
}
