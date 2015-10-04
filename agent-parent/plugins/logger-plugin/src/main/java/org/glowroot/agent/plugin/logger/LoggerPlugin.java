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
package org.glowroot.agent.plugin.logger;

import org.glowroot.agent.plugin.api.Agent;
import org.glowroot.agent.plugin.api.config.BooleanProperty;
import org.glowroot.agent.plugin.api.config.ConfigService;
import org.glowroot.agent.plugin.api.util.FastThreadLocal;

class LoggerPlugin {

    private static final ConfigService configService = Agent.getConfigService("logger");

    private static final BooleanProperty traceErrorOnWarningWithThrowable =
            configService.getEnabledProperty("traceErrorOnWarningWithThrowable");
    private static final BooleanProperty traceErrorOnWarningWithoutThrowable =
            configService.getEnabledProperty("traceErrorOnWarningWithoutThrowable");
    private static final BooleanProperty traceErrorOnErrorWithThrowable =
            configService.getEnabledProperty("traceErrorOnErrorWithThrowable");
    private static final BooleanProperty traceErrorOnErrorWithoutThrowable =
            configService.getEnabledProperty("traceErrorOnErrorWithoutThrowable");

    @SuppressWarnings("nullness:type.argument.type.incompatible")
    private static final FastThreadLocal<Boolean> inAdvice = new FastThreadLocal<Boolean>() {
        @Override
        protected Boolean initialValue() {
            return false;
        }
    };

    private LoggerPlugin() {}

    static boolean inAdvice() {
        return inAdvice.get();
    }

    static void inAdvice(boolean inAdvice) {
        LoggerPlugin.inAdvice.set(inAdvice);
    }

    static boolean markTraceAsError(boolean warn, boolean throwable) {
        if (warn && throwable) {
            return traceErrorOnWarningWithThrowable.value();
        } else if (warn && !throwable) {
            return traceErrorOnWarningWithoutThrowable.value();
        } else if (!warn && throwable) {
            return traceErrorOnErrorWithThrowable.value();
        } else {
            return traceErrorOnErrorWithoutThrowable.value();
        }
    }
}
