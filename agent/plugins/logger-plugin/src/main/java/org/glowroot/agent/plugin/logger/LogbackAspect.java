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
import org.glowroot.agent.plugin.api.Message;
import org.glowroot.agent.plugin.api.MessageSupplier;
import org.glowroot.agent.plugin.api.ThreadContext;
import org.glowroot.agent.plugin.api.TimerName;
import org.glowroot.agent.plugin.api.TraceEntry;
import org.glowroot.agent.plugin.api.weaving.BindClassMeta;
import org.glowroot.agent.plugin.api.weaving.BindParameter;
import org.glowroot.agent.plugin.api.weaving.BindReceiver;
import org.glowroot.agent.plugin.api.weaving.BindTraveler;
import org.glowroot.agent.plugin.api.weaving.OnAfter;
import org.glowroot.agent.plugin.api.weaving.OnBefore;
import org.glowroot.agent.plugin.api.weaving.Pointcut;
import org.glowroot.agent.plugin.api.weaving.Shim;

public class LogbackAspect {

    private static final String TIMER_NAME = "logging";

    // constants from ch.qos.logback.classic.Level
    private static final int OFF_INT = Integer.MAX_VALUE;
    private static final int ERROR_INT = 40000;
    private static final int WARN_INT = 30000;
    private static final int INFO_INT = 20000;
    private static final int DEBUG_INT = 10000;
    private static final int TRACE_INT = 5000;
    private static final int ALL_INT = Integer.MIN_VALUE;

    @Shim("ch.qos.logback.classic.spi.ILoggingEvent")
    public interface ILoggingEvent {

        @Shim("ch.qos.logback.classic.Level getLevel()")
        @Nullable
        Level glowroot$getLevel();

        @Nullable
        String getFormattedMessage();

        @Nullable
        String getLoggerName();

        @Shim("ch.qos.logback.classic.spi.IThrowableProxy getThrowableProxy()")
        @Nullable
        Object glowroot$getThrowableProxy();
    }

    @Shim("ch.qos.logback.classic.Level")
    public interface Level {
        int toInt();
    }

    @Shim("ch.qos.logback.classic.spi.ThrowableProxy")
    public interface ThrowableProxy {
        @Nullable
        Throwable getThrowable();
    }

    @Pointcut(className = "ch.qos.logback.classic.Logger", methodName = "callAppenders",
            methodParameterTypes = {"ch.qos.logback.classic.spi.ILoggingEvent"},
            nestingGroup = "logging", timerName = TIMER_NAME)
    public static class CallAppendersAdvice {

        private static final TimerName timerName = Agent.getTimerName(CallAppendersAdvice.class);

        @OnBefore
        public static @Nullable LogAdviceTraveler onBefore(ThreadContext context,
                @BindParameter @Nullable ILoggingEvent loggingEvent) {
            if (loggingEvent == null) {
                return null;
            }
            String formattedMessage = nullToEmpty(loggingEvent.getFormattedMessage());
            Level level = loggingEvent.glowroot$getLevel();
            int lvl = level == null ? 0 : level.toInt();
            Object throwableProxy = loggingEvent.glowroot$getThrowableProxy();
            Throwable t = null;
            if (throwableProxy instanceof ThrowableProxy) {
                // there is only one other subclass of ch.qos.logback.classic.spi.IThrowableProxy
                // and it is only used for logging exceptions over the wire
                t = ((ThrowableProxy) throwableProxy).getThrowable();
            }
            if (LoggerPlugin.markTraceAsError(lvl >= ERROR_INT, lvl >= WARN_INT, t != null)) {
                context.setTransactionError(formattedMessage, t);
            }
            TraceEntry traceEntry;
            traceEntry = context.startTraceEntry(
                    new LogMessageSupplier(lvl, loggingEvent.getLoggerName(), formattedMessage),
                    timerName);
            return new LogAdviceTraveler(traceEntry, lvl, formattedMessage, t);
        }

        @OnAfter
        public static void onAfter(@BindTraveler @Nullable LogAdviceTraveler traveler) {
            if (traveler == null) {
                return;
            }
            Throwable t = traveler.throwable;
            if (t != null) {
                // intentionally not passing message since it is already the trace entry message
                if (traveler.level >= WARN_INT) {
                    traveler.traceEntry.endWithError(t);
                } else {
                    traveler.traceEntry.endWithInfo(t);
                }
            } else if (traveler.level >= WARN_INT) {
                traveler.traceEntry.endWithError(traveler.formattedMessage);
            } else {
                traveler.traceEntry.end();
            }
        }

        private static String nullToEmpty(@Nullable String s) {
            return s == null ? "" : s;
        }
    }

    // this is for logback prior to 0.9.16
    @Pointcut(className = "ch.qos.logback.classic.Logger", methodName = "callAppenders",
            methodParameterTypes = {"ch.qos.logback.classic.spi.LoggingEvent"},
            nestingGroup = "logging", timerName = TIMER_NAME)
    public static class CallAppenders0xAdvice {

        private static final TimerName timerName = Agent.getTimerName(CallAppenders0xAdvice.class);

        @OnBefore
        public static @Nullable LogAdviceTraveler onBefore(ThreadContext context,
                @BindReceiver Object logger, @BindParameter @Nullable Object loggingEvent,
                @BindClassMeta LoggingEventInvoker invoker) {
            if (loggingEvent == null) {
                return null;
            }
            String formattedMessage = invoker.getFormattedMessage(loggingEvent);
            int lvl = invoker.getLevel(loggingEvent);
            Throwable t = invoker.getThrowable(loggingEvent);
            if (LoggerPlugin.markTraceAsError(lvl >= ERROR_INT, lvl >= WARN_INT, t != null)) {
                context.setTransactionError(formattedMessage, t);
            }
            TraceEntry traceEntry = context.startTraceEntry(
                    new LogMessageSupplier(lvl, invoker.getLoggerName(logger), formattedMessage),
                    timerName);
            return new LogAdviceTraveler(traceEntry, lvl, formattedMessage, t);
        }

        @OnAfter
        public static void onAfter(@BindTraveler @Nullable LogAdviceTraveler traveler) {
            if (traveler == null) {
                return;
            }
            Throwable t = traveler.throwable;
            if (t != null) {
                // intentionally not passing message since it is already the trace entry message
                if (traveler.level >= WARN_INT) {
                    traveler.traceEntry.endWithError(t);
                } else {
                    traveler.traceEntry.endWithInfo(t);
                }
            } else if (traveler.level >= WARN_INT) {
                traveler.traceEntry.endWithError(traveler.formattedMessage);
            } else {
                traveler.traceEntry.end();
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
                case TRACE_INT:
                    return "trace";
                case DEBUG_INT:
                    return "debug";
                case INFO_INT:
                    return "info";
                case WARN_INT:
                    return "warn";
                case ERROR_INT:
                    return "error";
                case OFF_INT:
                    return "off";
                default:
                    return "unknown (" + lvl + ")";
            }
        }
    }
}
