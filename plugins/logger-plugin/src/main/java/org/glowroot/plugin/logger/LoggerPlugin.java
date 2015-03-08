/*
 * Copyright 2014-2015 the original author or authors.
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
package org.glowroot.plugin.logger;

import org.glowroot.api.FastThreadLocal;
import org.glowroot.api.PluginServices;

class LoggerPlugin {

    private static final PluginServices pluginServices = PluginServices.get("logger");

    private LoggerPlugin() {}

    @SuppressWarnings("nullness:type.argument.type.incompatible")
    private static final FastThreadLocal<Boolean> inAdvice = new FastThreadLocal<Boolean>() {
        @Override
        protected Boolean initialValue() {
            return false;
        }
    };

    static boolean inAdvice() {
        return inAdvice.get();
    }

    static void inAdvice(boolean inAdvice) {
        LoggerPlugin.inAdvice.set(inAdvice);
    }

    static boolean markTraceAsError(boolean warn, boolean throwable) {
        if (warn && throwable) {
            return pluginServices.getBooleanProperty("traceErrorOnWarningWithThrowable");
        } else if (warn && !throwable) {
            return pluginServices.getBooleanProperty("traceErrorOnWarningWithoutThrowable");
        } else if (!warn && throwable) {
            return pluginServices.getBooleanProperty("traceErrorOnErrorWithThrowable");
        } else {
            return pluginServices.getBooleanProperty("traceErrorOnErrorWithoutThrowable");
        }
    }
}
