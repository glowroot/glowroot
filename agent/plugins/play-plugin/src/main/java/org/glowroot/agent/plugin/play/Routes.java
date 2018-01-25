/*
 * Copyright 2016-2018 the original author or authors.
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
package org.glowroot.agent.plugin.play;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.glowroot.agent.plugin.api.checker.Nullable;

class Routes {

    private static final Pattern routePattern = Pattern.compile("\\$[^<]+<([^>]+)>");

    private static final ConcurrentMap<String, String> simplifiedRoutes =
            new ConcurrentHashMap<String, String>();

    // visible for testing
    static String simplifiedRoute(String route) {
        String simplifiedRoute = simplifiedRoutes.get(route);
        if (simplifiedRoute == null) {
            Matcher matcher = routePattern.matcher(route);
            StringBuilder sb = new StringBuilder();
            int end = 0;
            while (matcher.find()) {
                if (end == 0) {
                    sb.append(route.substring(0, matcher.start()));
                }
                String regex = nullToEmpty(matcher.group(1));
                regex = regex.replace("[^/]+", "*");
                regex = regex.replace(".+", "**");
                sb.append(regex);
                end = matcher.end();
            }
            sb.append(route.substring(end));
            simplifiedRoute = sb.toString();
            simplifiedRoutes.putIfAbsent(route, simplifiedRoute);
        }
        return simplifiedRoute;
    }

    private static String nullToEmpty(@Nullable String s) {
        return s == null ? "" : s;
    }
}
