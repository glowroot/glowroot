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

import org.glowroot.api.PluginServices;
import org.glowroot.api.weaving.BindReceiver;
import org.glowroot.api.weaving.IsEnabled;
import org.glowroot.api.weaving.OnAfter;
import org.glowroot.api.weaving.Pointcut;

/**
 * @author Trask Stalnaker
 * @since 0.5
 */
public class RequestParameterAspect {

    private static final PluginServices pluginServices = PluginServices.get("servlet");

    @Pointcut(type = "javax.servlet.ServletRequest", methodName = "getParameter*",
            methodArgTypes = {".."}, ignoreSameNested = true)
    public static class GetParameterAdvice {
        @IsEnabled
        public static boolean isEnabled() {
            return pluginServices.isEnabled();
        }
        @OnAfter
        public static void onAfter(@BindReceiver Object realRequest) {
            // only now is it safe to get parameters (if parameters are retrieved before this, it
            // could prevent a servlet from choosing to read the underlying stream instead of using
            // the getParameter* methods) see SRV.3.1.1 "When Parameters Are Available"
            ServletMessageSupplier messageSupplier = ServletAspect.getServletMessageSupplier();
            if (messageSupplier != null && !messageSupplier.isRequestParametersCaptured()) {
                // the request is being traced and the parameter map hasn't been captured yet
                HttpServletRequest request = HttpServletRequest.from(realRequest);
                messageSupplier.setCaptureRequestParameters(
                        DetailCapture.captureRequestParameters(request));
            }
        }
    }
}
