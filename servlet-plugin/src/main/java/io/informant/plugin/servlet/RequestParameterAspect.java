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
import io.informant.api.weaving.BindTarget;
import io.informant.api.weaving.IsEnabled;
import io.informant.api.weaving.OnAfter;
import io.informant.api.weaving.Pointcut;

/**
 * @author Trask Stalnaker
 * @since 0.5
 */
public class RequestParameterAspect {

    private static final PluginServices pluginServices =
            PluginServices.get("io.informant.plugins:servlet-plugin");

    private static volatile boolean captureRequestParameters;

    static {
        pluginServices.registerConfigListener(new ConfigListener() {
            public void onChange() {
                captureRequestParameters =
                        pluginServices.getBooleanProperty("captureRequestParameters");
            }
        });
        captureRequestParameters = pluginServices.getBooleanProperty("captureRequestParameters");
    }

    @Pointcut(typeName = "javax.servlet.ServletRequest", methodName = "getParameter*",
            methodArgs = {".."}, captureNested = false)
    public static class GetParameterAdvice {
        @IsEnabled
        public static boolean isEnabled() {
            return pluginServices.isEnabled() && captureRequestParameters;
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
                messageSupplier.captureRequestParameterMap(request.getParameterMap());
            }
        }
    }
}
