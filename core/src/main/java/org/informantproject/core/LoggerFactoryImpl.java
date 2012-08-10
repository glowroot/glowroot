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
package org.informantproject.core;

import javax.annotation.concurrent.ThreadSafe;

import org.informantproject.api.Logger;
import org.informantproject.core.util.Static;
import org.slf4j.LoggerFactory;

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
@Static
public final class LoggerFactoryImpl {

    public static Logger getLogger(String name) {
        return new LoggerImpl(LoggerFactory.getLogger(name));
    }

    @ThreadSafe
    private static class LoggerImpl implements Logger {

        private final org.slf4j.Logger logger;

        private LoggerImpl(org.slf4j.Logger logger) {
            this.logger = logger;
        }
        public boolean isTraceEnabled() {
            return logger.isTraceEnabled();
        }
        public void trace(String msg) {
            logger.trace(msg);
        }
        public void trace(String format, Object arg) {
            logger.trace(format, arg);
        }
        public void trace(String format, Object arg1, Object arg2) {
            logger.trace(format, arg1, arg2);
        }
        public void trace(String format, Object[] argArray) {
            logger.trace(format, argArray);
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
        public void debug(String format, Object arg) {
            logger.debug(format, arg);
        }
        public void debug(String format, Object arg1, Object arg2) {
            logger.debug(format, arg1, arg2);
        }
        public void debug(String format, Object[] argArray) {
            logger.debug(format, argArray);
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
        public void info(String format, Object arg) {
            logger.info(format, arg);
        }
        public void info(String format, Object arg1, Object arg2) {
            logger.info(format, arg1, arg2);
        }
        public void info(String format, Object[] argArray) {
            logger.info(format, argArray);
        }
        public void info(String msg, Throwable t) {
            logger.info(msg, t);
        }
        public boolean isWarnEnabled() {
            return logger.isWarnEnabled();
        }
        public void warn(String msg) {
            logger.warn(msg);
        }
        public void warn(String format, Object arg) {
            logger.warn(format, arg);
        }
        public void warn(String format, Object[] argArray) {
            logger.warn(format, argArray);
        }
        public void warn(String format, Object arg1, Object arg2) {
            logger.warn(format, arg1, arg2);
        }
        public void warn(String msg, Throwable t) {
            logger.warn(msg, t);
        }
        public boolean isErrorEnabled() {
            return logger.isErrorEnabled();
        }
        public void error(String msg) {
            logger.error(msg);
        }
        public void error(String format, Object arg) {
            logger.error(format, arg);
        }
        public void error(String format, Object arg1, Object arg2) {
            logger.error(format, arg1, arg2);
        }
        public void error(String format, Object[] argArray) {
            logger.error(format, argArray);
        }
        public void error(String msg, Throwable t) {
            logger.error(msg, t);
        }
    }

    private LoggerFactoryImpl() {}
}
