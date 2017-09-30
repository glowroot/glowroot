/*
 * Copyright 2017 the original author or authors.
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
import java.util.logging.Logger;

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
import org.glowroot.agent.plugin.api.weaving.OnAfter;
import org.glowroot.agent.plugin.api.weaving.OnBefore;
import org.glowroot.agent.plugin.api.weaving.Pointcut;

public class JavaLoggingAspect {

    private static final String TIMER_NAME = "logging";

    @Pointcut(className = "java.util.logging.Logger", methodName = "log",
            methodParameterTypes = {"java.util.logging.LogRecord"}, nestingGroup = "logging",
            timerName = TIMER_NAME)
    public static class LogAdvice {

        private static final TimerName timerName = Agent.getTimerName(LogAdvice.class);

        private static final Formatter formatter = new DummyFormatter();

        // cannot use java.util.logging.Logger in the signature of this method because that triggers
        // java.util.logging.Logger to be loaded before weaving is put in place (from inside
        // org.glowroot.agent.weaving.AdviceBuilder)
        @OnBefore
        public static @Nullable LogAdviceTraveler onBefore(ThreadContext context,
                @BindParameter @Nullable LogRecord record, @BindReceiver Object logger) {
            if (record == null) {
                return null;
            }
            Level level = record.getLevel();
            if (!((Logger) logger).isLoggable(level)) {
                // Logger.log(LogRecord) was called directly
                return null;
            }
            // cannot check Logger.getFilter().isLoggable(LogRecord) because the Filter object
            // could be stateful and might alter its state (e.g.
            // com.sun.mail.util.logging.DurationFilter)
            String formattedMessage = nullToEmpty(formatter.formatMessage(record));
            int lvl = level.intValue();
            Throwable t = record.getThrown();
            if (LoggerPlugin.markTraceAsError(lvl >= Level.SEVERE.intValue(),
                    lvl >= Level.WARNING.intValue(), t != null)) {
                context.setTransactionError(formattedMessage, t);
            }
            TraceEntry traceEntry =
                    context.startTraceEntry(new LogMessageSupplier(level.getName().toLowerCase(),
                            record.getLoggerName(), formattedMessage), timerName);
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

        private static String nullToEmpty(@Nullable String s) {
            return s == null ? "" : s;
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

    // this is just needed for calling formatMessage in abstract super class
    private static class DummyFormatter extends Formatter {
        @Override
        public String format(LogRecord record) {
            throw new UnsupportedOperationException();
        }
    }

    private static class LogMessageSupplier extends MessageSupplier {

        private final String level;
        private final String loggerName;
        private final String messageText;

        private LogMessageSupplier(String level, String loggerName, String messageText) {
            this.level = level;
            this.loggerName = loggerName;
            this.messageText = messageText;
        }

        @Override
        public Message get() {
            return Message.create("log {}: {} - {}", level,
                    LoggerPlugin.getAbbreviatedLoggerName(loggerName), messageText);
        }
    }
}
