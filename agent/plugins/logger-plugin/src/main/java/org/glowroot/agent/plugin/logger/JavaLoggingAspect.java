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

import java.util.logging.Formatter;
import java.util.logging.Level;
import java.util.logging.LogRecord;

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

public class JavaLoggingAspect {

    @Shim("java.util.logging.Logger")
    public interface Logger {

        boolean isLoggable(Level level);
    }

    private JavaLoggingAspect() {
        throw new IllegalAccessError(); // prevent instantiation
    }

    private static final String TIMER_NAME = "logging";

    @Pointcut(className = "java.util.logging.Logger", methodName = "log",
            methodParameterTypes = {"java.util.logging.LogRecord"},
            nestingGroup = "logging", timerName = TIMER_NAME)
    public static class LogAdvice {

        private LogAdvice() {
            throw new IllegalAccessError(); // prevent instantiation
        }

        private static final TimerName timerName = Agent.getTimerName(LogAdvice.class);

        private static final Formatter formatter = new Formatter() {
            @Override
            public String format(LogRecord record) {
                try {
                    return formatMessage(record);
                } catch (final Exception e) { // jul handlers usually do this check
                    return nullToEmpty(record.getMessage());
                }
            }

            private String nullToEmpty(@Nullable String s) {
                return s == null ? "" : s;
            }
        };

        @OnBefore
        public static @Nullable LogAdviceTraveler onBefore(ThreadContext context,
                @BindParameter @Nullable LogRecord record, @BindReceiver Logger logger) {
            if (record == null) {
                return null;
            }
            final Level level = record.getLevel(); // cannot be null
            if (!logger.isLoggable(level)) { // if someone calls directly Logger.log(LogRecord) we have to do this test
                return null;
            }
            // We cannot check Logger.getFilter().isLoggable(LogRecord) because the Filter object could
            // be stateful and we might alter its state (e.g., com.sun.mail.util.logging.DurationFilter).
            final String formattedMessage = formatter.format(record);
            final int lvl = level.intValue();
            final Throwable t = record.getThrown();
            if (LoggerPlugin.markTraceAsError(lvl >= Level.SEVERE.intValue(), lvl >= Level.WARNING.intValue(), t != null)) {
                context.setTransactionError(formattedMessage, t);
            }
            final String loggerName = LoggerPlugin.getAbbreviatedLoggerName(record.getLoggerName());
            final TraceEntry traceEntry = context.startTraceEntry(MessageSupplier.create("log {}: {} - {}",
                    level.getName().toLowerCase(), loggerName, formattedMessage), timerName);
            return new LogAdviceTraveler(traceEntry, lvl, formattedMessage, t);
        }

        @OnAfter
        public static void onAfter(@BindTraveler @Nullable LogAdviceTraveler traveler) {
            if (traveler == null) {
                return;
            }
            final Throwable t = traveler.throwable;
            if (t != null) {
                // intentionally not passing message since it is already the trace entry message
                if (traveler.level >= Level.WARNING.intValue()) {
                    traveler.traceEntry.endWithError(t);
                } else {
                    traveler.traceEntry.endWithInfo(t);
                }
            } else if (traveler.level >= Level.WARNING.intValue()) {
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
}
