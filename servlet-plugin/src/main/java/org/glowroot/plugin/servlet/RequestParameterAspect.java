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

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Map.Entry;
import java.util.regex.Pattern;

import checkers.igj.quals.ReadOnly;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;

import org.glowroot.api.PluginServices;
import org.glowroot.api.weaving.BindTarget;
import org.glowroot.api.weaving.IsEnabled;
import org.glowroot.api.weaving.OnAfter;
import org.glowroot.api.weaving.Pointcut;

/**
 * @author Trask Stalnaker
 * @since 0.5
 */
public class RequestParameterAspect {

    private static final PluginServices pluginServices = PluginServices.get("servlet");

    @Pointcut(typeName = "javax.servlet.ServletRequest", methodName = "getParameter*",
            methodArgs = {".."}, captureNested = false)
    public static class GetParameterAdvice {
        @IsEnabled
        public static boolean isEnabled() {
            return pluginServices.isEnabled();
        }
        @OnAfter
        public static void onAfter(@BindTarget Object realRequest) {
            // only now is it safe to get parameters (if parameters are retrieved before this, it
            // could prevent a servlet from choosing to read the underlying stream instead of using
            // the getParameter* methods) see SRV.3.1.1 "When Parameters Are Available"
            ServletMessageSupplier messageSupplier = ServletAspect.getServletMessageSupplier();
            if (messageSupplier != null && !messageSupplier.isRequestParameterMapCaptured()) {
                // the request is being traced and the parameter map hasn't been captured yet
                HttpServletRequest request = HttpServletRequest.from(realRequest);
                messageSupplier.captureRequestParameterMap(build(request.getParameterMap()));
            }
        }
    }

    private static ImmutableMap<String, String[]> build(@ReadOnly Map<?, ?> requestParameterMap) {
        // shallow copy is necessary because request may not be thread safe
        // shallow copy is also necessary because of the note about tomcat above
        //
        // so may as well filter here
        ImmutableList<Pattern> capturePatterns = ServletPluginProperties.captureRequestParameters();
        ImmutableList<Pattern> maskPatterns = ServletPluginProperties.maskRequestParameters();

        ImmutableMap.Builder<String, String[]> map = ImmutableMap.builder();
        for (Entry<?, ?> entry : requestParameterMap.entrySet()) {
            String key = (String) entry.getKey();
            if (key == null) {
                // null check just to be safe in case this is a very strange servlet container
                continue;
            }
            // converted to lower case for case-insensitive matching (patterns are lower case)
            String keyLowerCase = key.toLowerCase(Locale.ENGLISH);
            if (!matchesOneOf(keyLowerCase, capturePatterns)) {
                continue;
            }
            if (matchesOneOf(keyLowerCase, maskPatterns)) {
                map.put(key, new String[] {"****"});
                continue;
            }
            String/*@Nullable*/[] value = (String/*@Nullable*/[]) entry.getValue();
            if (value == null) {
                // just to be safe since ImmutableMap won't accept nulls
                map.put(key, new String[0]);
            } else {
                // the clone() is just to be safe to ensure immutability
                map.put(key, value.clone());
            }
        }
        return map.build();
    }

    private static boolean matchesOneOf(String key, @ReadOnly List<Pattern> patterns) {
        for (Pattern pattern : patterns) {
            if (pattern.matcher(key).matches()) {
                return true;
            }
        }
        return false;
    }
}
