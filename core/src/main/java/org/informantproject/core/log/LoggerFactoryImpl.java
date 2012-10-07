/**
 * Copyright 2011-2012 the original author or authors.
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
package org.informantproject.core.log;

import javax.annotation.Nullable;
import javax.annotation.concurrent.ThreadSafe;

import org.informantproject.api.Logger;
import org.informantproject.shaded.slf4j.LoggerFactory;
import org.informantproject.shaded.slf4j.helpers.MessageFormatter;

/**
 * Implementations of LoggerFactory and Logger from the Plugin API.
 * 
 * These are simply wrappers of the SLF4J Logger API without the Marker support.
 * 
 * Currently, Informant uses (a shaded version of) Logback as its SLF4J binding. In the future,
 * however, it may use a custom SLF4J binding to store error messages in its embedded H2 database so
 * that any error messages can be displayed in the embedded UI.
 * 
 * @author Trask Stalnaker
 * @since 0.5
 */
// called via reflection from org.informantproject.api.LoggerFactory
@ThreadSafe
public final class LoggerFactoryImpl {

    @Nullable
    private static volatile LogMessageSink logMessageSink;

    public static Logger getLogger(String name) {
        return new LoggerImpl(name);
    }

    public static void setLogMessageSink(LogMessageSink logMessageSink) {
        LoggerFactoryImpl.logMessageSink = logMessageSink;
    }

    @ThreadSafe
    private static class LoggerImpl implements Logger {

        private final org.informantproject.shaded.slf4j.Logger logger;

        private LoggerImpl(String name) {
            this.logger = LoggerFactory.getLogger(name);
        }
        public boolean isTraceEnabled() {
            return logger.isTraceEnabled();
        }
        public void trace(String msg) {
            logger.trace(msg);
        }
        public void trace(String format, @Nullable Object arg) {
            logger.trace(format, arg);
        }
        public void trace(String format, @Nullable Object arg1, @Nullable Object arg2) {
            logger.trace(format, arg1, arg2);
        }
        public void trace(String format, Object... arguments) {
            logger.trace(format, arguments);
        }
        public void trace(String msg, Throwable t) {
            logger.trace(msg, t);
        }
        public boolean isDebugEnabled() {
            return logger.isDebugEnabled();
        }
        public void debug(String msg) {
            logger.debug(msg);
        }
        public void debug(String format, @Nullable Object arg) {
            logger.debug(format, arg);
        }
        public void debug(String format, @Nullable Object arg1, @Nullable Object arg2) {
            logger.debug(format, arg1, arg2);
        }
        public void debug(String format, Object... arguments) {
            logger.debug(format, arguments);
        }
        public void debug(String msg, Throwable t) {
            logger.debug(msg, t);
        }
        public boolean isInfoEnabled() {
            return logger.isInfoEnabled();
        }
        public void info(String msg) {
            logger.info(msg);
        }
        public void info(String format, @Nullable Object arg) {
            logger.info(format, arg);
        }
        public void info(String format, @Nullable Object arg1, @Nullable Object arg2) {
            logger.info(format, arg1, arg2);
        }
        public void info(String format, Object... arguments) {
            logger.info(format, arguments);
        }
        public void info(String msg, Throwable t) {
            logger.info(msg, t);
        }
        public boolean isWarnEnabled() {
            return logger.isWarnEnabled();
        }
        public void warn(String msg) {
            logger.warn(msg);
            if (logMessageSink != null) {
                logMessageSink.onLogMessage(Level.WARN, logger.getName(), msg, null);
            }
        }
        public void warn(String format, @Nullable Object arg) {
            logger.warn(format, arg);
            if (logMessageSink != null) {
                logMessageSink.onLogMessage(Level.WARN, logger.getName(),
                        MessageFormatter.format(format, arg).getMessage(), null);
            }
        }
        public void warn(String format, @Nullable Object arg1, @Nullable Object arg2) {
            logger.warn(format, arg1, arg2);
            if (logMessageSink != null) {
                logMessageSink.onLogMessage(Level.WARN, logger.getName(),
                        MessageFormatter.format(format, arg1, arg2).getMessage(), null);
            }
        }
        public void warn(String format, Object... arguments) {
            logger.warn(format, arguments);
            if (logMessageSink != null) {
                logMessageSink.onLogMessage(Level.WARN, logger.getName(),
                        MessageFormatter.arrayFormat(format, arguments).getMessage(), null);
            }
        }
        public void warn(String msg, Throwable t) {
            logger.warn(msg, t);
            if (logMessageSink != null) {
                logMessageSink.onLogMessage(Level.WARN, logger.getName(), msg, t);
            }
        }
        public boolean isErrorEnabled() {
            return logger.isErrorEnabled();
        }
        public void error(String msg) {
            logger.error(msg);
            if (logMessageSink != null) {
                logMessageSink.onLogMessage(Level.ERROR, logger.getName(), msg, null);
            }
        }
        public void error(String format, @Nullable Object arg) {
            logger.error(format, arg);
            if (logMessageSink != null) {
                logMessageSink.onLogMessage(Level.ERROR, logger.getName(),
                        MessageFormatter.format(format, arg).getMessage(), null);
            }
        }
        public void error(String format, @Nullable Object arg1, @Nullable Object arg2) {
            logger.error(format, arg1, arg2);
            if (logMessageSink != null) {
                logMessageSink.onLogMessage(Level.ERROR, logger.getName(),
                        MessageFormatter.format(format, arg1, arg2).getMessage(), null);
            }
        }
        public void error(String format, Object... arguments) {
            logger.error(format, arguments);
            if (logMessageSink != null) {
                logMessageSink.onLogMessage(Level.ERROR, logger.getName(),
                        MessageFormatter.arrayFormat(format, arguments).getMessage(), null);
            }
        }
        public void error(String msg, Throwable t) {
            logger.error(msg, t);
            if (logMessageSink != null) {
                logMessageSink.onLogMessage(Level.ERROR, logger.getName(), msg, t);
            }
        }
    }

    private LoggerFactoryImpl() {}
}
