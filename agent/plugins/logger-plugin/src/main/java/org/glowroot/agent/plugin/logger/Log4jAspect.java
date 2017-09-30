/*
 * Copyright 2014-2017 the original author or authors.
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

import java.util.Enumeration;

import javax.annotation.Nullable;

import org.glowroot.agent.plugin.api.Agent;
import org.glowroot.agent.plugin.api.Message;
import org.glowroot.agent.plugin.api.MessageSupplier;
import org.glowroot.agent.plugin.api.ThreadContext;
import org.glowroot.agent.plugin.api.TimerName;
import org.glowroot.agent.plugin.api.TraceEntry;
import org.glowroot.agent.plugin.api.weaving.BindParameter;
import org.glowroot.agent.plugin.api.weaving.BindReceiver;
import org.glowroot.agent.plugin.api.weaving.BindTraveler;
import org.glowroot.agent.plugin.api.weaving.IsEnabled;
import org.glowroot.agent.plugin.api.weaving.OnAfter;
import org.glowroot.agent.plugin.api.weaving.OnBefore;
import org.glowroot.agent.plugin.api.weaving.Pointcut;
import org.glowroot.agent.plugin.api.weaving.Shim;

public class Log4jAspect {

    private static final String TIMER_NAME = "logging";

    // constants from org.apache.log4j.Priority
    private static final int OFF_INT = Integer.MAX_VALUE;
    private static final int FATAL_INT = 50000;
    private static final int ERROR_INT = 40000;
    private static final int WARN_INT = 30000;
    private static final int INFO_INT = 20000;
    private static final int DEBUG_INT = 10000;
    private static final int ALL_INT = Integer.MIN_VALUE;

    @Shim("org.apache.log4j.Category")
    public interface Logger {

        @Nullable
        String getName();

        @Shim("org.apache.log4j.Category getParent()")
        @Nullable
        Logger glowroot$getParent();

        @Nullable
        Enumeration<?> getAllAppenders();
    }

    @Shim("org.apache.log4j.Priority")
    public interface Level {
        int toInt();
    }

    @Pointcut(className = "org.apache.log4j.Category", methodName = "forcedLog",
            methodParameterTypes = {"java.lang.String", "org.apache.log4j.Priority",
                    "java.lang.Object", "java.lang.Throwable"},
            nestingGroup = "logging", timerName = TIMER_NAME)
    public static class ForcedLogAdvice {

        private static final TimerName timerName = Agent.getTimerName(ForcedLogAdvice.class);

        @IsEnabled
        @SuppressWarnings("unboxing.of.nullable")
        public static boolean isEnabled(@BindReceiver Logger logger) {
            // check to see if no appenders, then don't capture (this is just to avoid confusion)
            // log4j itself will log a warning:
            // "No appenders could be found for logger, Please initialize the log4j system properly"
            // (see org.apache.log4j.Hierarchy.emitNoAppenderWarning())
            Logger curr = logger;
            while (true) {
                Enumeration<?> e = curr.getAllAppenders();
                if (e != null && e.hasMoreElements()) {
                    // has at least one appender
                    return true;
                }
                curr = curr.glowroot$getParent();
                if (curr == null) {
                    return false;
                }
            }
        }

        @OnBefore
        @SuppressWarnings("unused")
        public static TraceEntry onBefore(ThreadContext context, @BindReceiver Logger logger,
                @BindParameter @Nullable String fqcn, @BindParameter @Nullable Level level,
                @BindParameter @Nullable Object message, @BindParameter @Nullable Throwable t) {
            String messageText = String.valueOf(message);
            int lvl = level == null ? 0 : level.toInt();
            if (LoggerPlugin.markTraceAsError(lvl >= ERROR_INT, lvl >= WARN_INT, t != null)) {
                context.setTransactionError(messageText, t);
            }
            return context.startTraceEntry(
                    new LogMessageSupplier(lvl, logger.getName(), messageText), timerName);
        }

        @OnAfter
        @SuppressWarnings("unused")
        public static void onAfter(@BindTraveler TraceEntry traceEntry,
                @BindParameter @Nullable String fqcn, @BindParameter @Nullable Level level,
                @BindParameter @Nullable Object message, @BindParameter @Nullable Throwable t) {
            int lvl = level == null ? 0 : level.toInt();
            if (t != null) {
                // intentionally not passing message since it is already the trace entry message
                if (lvl >= WARN_INT) {
                    traceEntry.endWithError(t);
                } else {
                    traceEntry.endWithInfo(t);
                }
            } else if (lvl >= WARN_INT) {
                traceEntry.endWithError(String.valueOf(message));
            } else {
                traceEntry.end();
            }
        }
    }

    private static class LogMessageSupplier extends MessageSupplier {

        private final int level;
        private final @Nullable String loggerName;
        private final String messageText;

        private LogMessageSupplier(int level, @Nullable String loggerName, String messageText) {
            this.level = level;
            this.loggerName = loggerName;
            this.messageText = messageText;
        }

        @Override
        public Message get() {
            return Message.create("log {}: {} - {}", getLevelStr(level),
                    LoggerPlugin.getAbbreviatedLoggerName(loggerName), messageText);
        }

        private static String getLevelStr(int lvl) {
            switch (lvl) {
                case ALL_INT:
                    return "all";
                case DEBUG_INT:
                    return "debug";
                case INFO_INT:
                    return "info";
                case WARN_INT:
                    return "warn";
                case ERROR_INT:
                    return "error";
                case FATAL_INT:
                    return "fatal";
                case OFF_INT:
                    return "off";
                default:
                    return "unknown (" + lvl + ")";
            }
        }
    }
}
