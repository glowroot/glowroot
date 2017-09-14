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

import javax.annotation.Nullable;

import org.glowroot.agent.plugin.api.Agent;
import org.glowroot.agent.plugin.api.MessageSupplier;
import org.glowroot.agent.plugin.api.ThreadContext;
import org.glowroot.agent.plugin.api.TimerName;
import org.glowroot.agent.plugin.api.TraceEntry;
import org.glowroot.agent.plugin.api.weaving.BindParameter;
import org.glowroot.agent.plugin.api.weaving.BindReceiver;
import org.glowroot.agent.plugin.api.weaving.BindTraveler;
import org.glowroot.agent.plugin.api.weaving.OnAfter;
import org.glowroot.agent.plugin.api.weaving.OnBefore;
import org.glowroot.agent.plugin.api.weaving.Pointcut;
import org.glowroot.agent.plugin.api.weaving.Shim;

public class Log4j2xAspect {

    private static final String TIMER_NAME = "logging";

    // constants from org.apache.logging.log4j.spi.StandardLevel
    private static final int OFF = 0;
    private static final int FATAL = 100;
    private static final int ERROR = 200;
    private static final int WARN = 300;
    private static final int INFO = 400;
    private static final int DEBUG = 500;
    private static final int TRACE = 600;
    private static final int ALL = Integer.MAX_VALUE;

    @Shim("org.apache.logging.log4j.Logger")
    public interface Logger {
        @Nullable
        String getName();
    }

    @Shim("org.apache.logging.log4j.Level")
    public interface Level {
        int intLevel();
    }

    @Shim("org.apache.logging.log4j.message.Message")
    public interface Message {
        @Nullable
        String getFormattedMessage();
    }

    @Pointcut(className = "org.apache.logging.log4j.spi.ExtendedLogger", methodName = "logMessage",
            methodParameterTypes = {"java.lang.String", "org.apache.logging.log4j.Level",
                    "org.apache.logging.log4j.Marker", "org.apache.logging.log4j.message.Message",
                    "java.lang.Throwable"},
            nestingGroup = "logging", timerName = TIMER_NAME)
    public static class CallAppendersAdvice {

        private static final TimerName timerName = Agent.getTimerName(CallAppendersAdvice.class);

        @OnBefore
        public static LogAdviceTraveler onBefore(ThreadContext context, @BindReceiver Logger logger,
                @SuppressWarnings("unused") @BindParameter @Nullable String fqcn,
                @BindParameter @Nullable Level level,
                @SuppressWarnings("unused") @BindParameter @Nullable Object marker,
                @BindParameter @Nullable Message message, @BindParameter @Nullable Throwable t) {
            String formattedMessage =
                    message == null ? "" : nullToEmpty(message.getFormattedMessage());
            int lvl = level == null ? 0 : level.intLevel();
            if (LoggerPlugin.markTraceAsError(lvl <= ERROR, lvl <= WARN, t != null)) {
                context.setTransactionError(formattedMessage, t);
            }
            // not using LoggerPlugin.getAbbreviatedLoggerName() because log4j2 2.9.0+ uses
            // canonical class name instead of class name for the logger name (see
            // https://issues.apache.org/jira/browse/LOG4J2-2023) and this causes
            // LoggerPlugin.getAbbreviatedLoggerName() to abbreviate outer class names, e.g. a
            // logger for org.example.Outer$Inner has logger name org.example.Outer.Inner and would
            // then be abbreviated as org.example.O.Inner, which seems not ideal
            TraceEntry traceEntry =
                    context.startTraceEntry(MessageSupplier.create("log {}: {} - {}",
                            getLevelStr(lvl), logger.getName(), formattedMessage), timerName);
            return new LogAdviceTraveler(traceEntry, lvl, formattedMessage, t);
        }

        @OnAfter
        public static void onAfter(@BindTraveler LogAdviceTraveler traveler) {
            Throwable t = traveler.throwable;
            if (t != null) {
                // intentionally not passing message since it is already the trace entry message
                if (traveler.level <= WARN) {
                    traveler.traceEntry.endWithError(t);
                } else {
                    traveler.traceEntry.endWithInfo(t);
                }
            } else if (traveler.level <= WARN) {
                traveler.traceEntry.endWithError(traveler.formattedMessage);
            } else {
                traveler.traceEntry.end();
            }
        }

        private static String nullToEmpty(@Nullable String s) {
            return s == null ? "" : s;
        }

        private static String getLevelStr(int lvl) {
            switch (lvl) {
                case ALL:
                    return "all";
                case TRACE:
                    return "trace";
                case DEBUG:
                    return "debug";
                case INFO:
                    return "info";
                case WARN:
                    return "warn";
                case ERROR:
                    return "error";
                case FATAL:
                    return "fatal";
                case OFF:
                    return "off";
                default:
                    return "unknown (" + lvl + ")";
            }
        }
    }

    private static class LogAdviceTraveler {

        private final TraceEntry traceEntry;
        private final int level;
        private final String formattedMessage;
        private final @Nullable Throwable throwable;

        private LogAdviceTraveler(TraceEntry traceEntry, int level, String formattedMessage,
                @Nullable Throwable throwable) {
            this.traceEntry = traceEntry;
            this.level = level;
            this.formattedMessage = formattedMessage;
            this.throwable = throwable;
        }
    }
}
