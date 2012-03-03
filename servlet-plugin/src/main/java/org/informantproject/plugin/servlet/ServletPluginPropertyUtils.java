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

import java.util.HashSet;
import java.util.Set;

import org.informantproject.api.PluginServices;

/**
 * Convenience methods for accessing servlet plugin configuration property values.
 * 
 * @author Trask Stalnaker
 * @since 0.5
 */
final class ServletPluginPropertyUtils {

    private static final String SERVLET_PLUGIN_NAME = "servlet";

    // TODO support nested paths
    private static final String USERNAME_SESSION_ATTRIBUTE_PATH_PROPERTY_NAME =
            "usernameSessionAttribute";

    // a special single value of "*" means capture all session attributes
    // this can be useful for finding the session attribute that represents the username
    // TODO support nested paths
    // TODO support "*.*", "*.*.*", etc
    // TODO support partial wildcards, e.g. "context*"
    private static final String SESSION_ATTRIBUTE_PATHS_PROPERTY_NAME = "sessionAttributes";

    // optimization
    private static volatile Set<String> cachedSessionAttributes;
    private static volatile String cachedSessionAttributesText;

    // utility class
    private ServletPluginPropertyUtils() {}

    static String getUsernameSessionAttributePath() {
        return PluginServices.get().getStringProperty(SERVLET_PLUGIN_NAME,
                USERNAME_SESSION_ATTRIBUTE_PATH_PROPERTY_NAME);
    }

    static Set<String> getSessionAttributePaths() {
        String sessionAttributesText = PluginServices.get().getStringProperty(SERVLET_PLUGIN_NAME,
                SESSION_ATTRIBUTE_PATHS_PROPERTY_NAME);
        if (!sessionAttributesText.equals(cachedSessionAttributesText)) {
            String[] sessionAttributesArray = sessionAttributesText.split(",");
            Set<String> sessionAttributes = new HashSet<String>();
            for (String sessionAttribute : sessionAttributesArray) {
                sessionAttributes.add(sessionAttribute);
            }
            // update cachedSessionAttributes first so that another thread cannot come into this
            // method and get a positive match for text but then get the old cached attributes
            cachedSessionAttributes = sessionAttributes;
            cachedSessionAttributesText = sessionAttributesText;
        }
        return cachedSessionAttributes;
    }
}
