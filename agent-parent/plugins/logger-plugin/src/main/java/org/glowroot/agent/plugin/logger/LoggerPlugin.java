/*
 * Copyright 2014-2016 the original author or authors.
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

import javax.annotation.Nullable;

import org.glowroot.agent.plugin.api.Agent;
import org.glowroot.agent.plugin.api.config.BooleanProperty;
import org.glowroot.agent.plugin.api.config.ConfigService;

class LoggerPlugin {

    private static final ConfigService configService = Agent.getConfigService("logger");

    private static final BooleanProperty traceErrorOnWarningWithThrowable =
            configService.getBooleanProperty("traceErrorOnWarningWithThrowable");
    private static final BooleanProperty traceErrorOnWarningWithoutThrowable =
            configService.getBooleanProperty("traceErrorOnWarningWithoutThrowable");
    private static final BooleanProperty traceErrorOnErrorWithThrowable =
            configService.getBooleanProperty("traceErrorOnErrorWithThrowable");
    private static final BooleanProperty traceErrorOnErrorWithoutThrowable =
            configService.getBooleanProperty("traceErrorOnErrorWithoutThrowable");

    // TODO expose targetLength as plugin property
    private static final LoggerNameAbbreviator loggerNameAbbreviator =
            new LoggerNameAbbreviator(36);

    private LoggerPlugin() {}

    static boolean markTraceAsError(boolean isErrorOrHigher, boolean isWarnOrHigher,
            boolean throwable) {
        if (isErrorOrHigher) {
            return throwable ? traceErrorOnErrorWithThrowable.value()
                    : traceErrorOnErrorWithoutThrowable.value();
        }
        if (isWarnOrHigher) {
            return throwable ? traceErrorOnWarningWithThrowable.value()
                    : traceErrorOnWarningWithoutThrowable.value();
        }
        return false;
    }

    static String getAbbreviatedLoggerName(@Nullable String loggerName) {
        if (loggerName == null) {
            return "null";
        }
        return loggerNameAbbreviator.abbreviate(loggerName);
    }
}
