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
import org.glowroot.agent.plugin.api.weaving.BindTraveler;
import org.glowroot.agent.plugin.api.weaving.OnAfter;
import org.glowroot.agent.plugin.api.weaving.OnBefore;
import org.glowroot.agent.plugin.api.weaving.Pointcut;
import org.glowroot.agent.plugin.api.weaving.Shim;

public class JavaLoggingAspect {

    private static final String TIMER_NAME = "logging";

    // constants from org.apache.log4j.Priority
    private static final int OFF_INT = Integer.MAX_VALUE;
    private static final int ERROR_INT = 1000;
    private static final int WARN_INT = 900;
    private static final int INFO_INT = 800;
    private static final int CONFIG_INT = 700;
    private static final int FINE_INT = 500;
    private static final int FINER_INT = 400;
    private static final int FINEST_INT = 300;
    private static final int ALL_INT = Integer.MIN_VALUE;
    
  @Shim("java.util.logging.LogRecord")
  public interface LogRecord {

      @Shim("java.util.logging.Level getLevel()")
      @Nullable
      Level glowroot$getLevel();

      @Nullable
      String getMessage();

      @Nullable
      String getLoggerName();

      @Shim("java.lang.Throwable getThrown()")
      @Nullable
      Throwable getThrown();
  }

    @Shim("java.util.logging.Level")
    public interface Level {
        int intValue();
    }

    @Pointcut(className = "java.util.logging.Logger", methodName = "log",
            methodParameterTypes = {"java.util.logging.LogRecord"},
            nestingGroup = "logging", timerName = TIMER_NAME)
    public static class CallAppendersAdvice {

        private static final TimerName timerName = Agent.getTimerName(CallAppendersAdvice.class);

        @OnBefore
        public static @Nullable LogAdviceTraveler onBefore(ThreadContext context,
                @BindParameter @Nullable LogRecord loggingEvent) {
            if (loggingEvent == null) {
                return null;
            }
            String formattedMessage = nullToEmpty(loggingEvent.getMessage());
            Level level = loggingEvent.glowroot$getLevel();
            int lvl = level == null ? 0 : level.intValue();
            Throwable t = loggingEvent.getThrown();
            if (LoggerPlugin.markTraceAsError(lvl >= ERROR_INT, lvl >= WARN_INT, t != null)) {
                context.setTransactionError(formattedMessage, t);
            }
            TraceEntry traceEntry;
            String loggerName = LoggerPlugin.getAbbreviatedLoggerName(loggingEvent.getLoggerName());
            traceEntry = context.startTraceEntry(MessageSupplier.create("log {}: {} - {}",
                    getLevelStr(lvl), loggerName, formattedMessage), timerName);
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

	private static String getLevelStr(int lvl) {
		switch (lvl) {
		case ALL_INT:
			return "all";
		case FINEST_INT:
			return "finest";
		case FINER_INT:
			return "finer";
		case FINE_INT:
			return "fine";
		case CONFIG_INT:
			return "config";
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
