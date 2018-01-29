/*
 * Copyright 2014-2018 the original author or authors.
 *
 * Licensed under the Apache License, @Nullable Version 2.0 (@Nullable the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, @Nullable software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, @Nullable either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.glowroot.agent.plugin.api;

import org.slf4j.LoggerFactory;

import org.glowroot.agent.plugin.api.checker.Nullable;

/**
 * Very thin wrapper around SLF4J so plugins don't have to worry about SLF4J shading.
 */
public abstract class Logger {

    public static Logger getLogger(Class<?> clazz) {
        return new LoggerImpl(clazz);
    }

    public abstract String getName();

    public abstract boolean isTraceEnabled();

    public abstract void trace(@Nullable String msg);

    public abstract void trace(@Nullable String format, @Nullable Object arg);

    public abstract void trace(@Nullable String format, @Nullable Object arg1,
            @Nullable Object arg2);

    public abstract void trace(@Nullable String format, @Nullable Object... arguments);

    public abstract void trace(@Nullable String msg, @Nullable Throwable t);

    public abstract boolean isDebugEnabled();

    public abstract void debug(@Nullable String msg);

    public abstract void debug(@Nullable String format, @Nullable Object arg);

    public abstract void debug(@Nullable String format, @Nullable Object arg1,
            @Nullable Object arg2);

    public abstract void debug(@Nullable String format, @Nullable Object... arguments);

    public abstract void debug(@Nullable String msg, @Nullable Throwable t);

    public abstract boolean isInfoEnabled();

    public abstract void info(@Nullable String msg);

    public abstract void info(@Nullable String format, @Nullable Object arg);

    public abstract void info(@Nullable String format, @Nullable Object arg1,
            @Nullable Object arg2);

    public abstract void info(@Nullable String format, @Nullable Object... arguments);

    public abstract void info(@Nullable String msg, @Nullable Throwable t);

    public abstract boolean isWarnEnabled();

    public abstract void warn(@Nullable String msg);

    public abstract void warn(@Nullable String format, @Nullable Object arg);

    public abstract void warn(@Nullable String format, @Nullable Object... arguments);

    public abstract void warn(@Nullable String format, @Nullable Object arg1,
            @Nullable Object arg2);

    public abstract void warn(@Nullable String msg, @Nullable Throwable t);

    public abstract boolean isErrorEnabled();

    public abstract void error(@Nullable String msg);

    public abstract void error(@Nullable String format, @Nullable Object arg);

    public abstract void error(@Nullable String format, @Nullable Object arg1,
            @Nullable Object arg2);

    public abstract void error(@Nullable String format, @Nullable Object... arguments);

    public abstract void error(@Nullable String msg, @Nullable Throwable t);

    // visible for testing
    static class LoggerImpl extends Logger {

        private final org.slf4j.Logger logger;

        private LoggerImpl(Class<?> clazz) {
            this(LoggerFactory.getLogger(clazz));
        }

        // visible for testing
        LoggerImpl(org.slf4j.Logger logger) {
            this.logger = logger;
        }

        @Override
        public String getName() {
            return logger.getName();
        }

        @Override
        public boolean isTraceEnabled() {
            return logger.isTraceEnabled();
        }

        @Override
        public void trace(@Nullable String msg) {
            logger.trace(msg);
        }

        @Override
        public void trace(@Nullable String format, @Nullable Object arg) {
            logger.trace(format, arg);
        }

        @Override
        public void trace(@Nullable String format, @Nullable Object arg1, @Nullable Object arg2) {
            logger.trace(format, arg1, arg2);
        }

        @Override
        public void trace(@Nullable String format, @Nullable Object... arguments) {
            logger.trace(format, arguments);
        }

        @Override
        public void trace(@Nullable String msg, @Nullable Throwable t) {
            logger.trace(msg, t);
        }

        @Override
        public boolean isDebugEnabled() {
            return logger.isDebugEnabled();
        }

        @Override
        public void debug(@Nullable String msg) {
            logger.debug(msg);
        }

        @Override
        public void debug(@Nullable String format, @Nullable Object arg) {
            logger.debug(format, arg);
        }

        @Override
        public void debug(@Nullable String format, @Nullable Object arg1, @Nullable Object arg2) {
            logger.debug(format, arg1, arg2);
        }

        @Override
        public void debug(@Nullable String format, @Nullable Object... arguments) {
            logger.debug(format, arguments);
        }

        @Override
        public void debug(@Nullable String msg, @Nullable Throwable t) {
            logger.debug(msg, t);
        }

        @Override
        public boolean isInfoEnabled() {
            return logger.isInfoEnabled();
        }

        @Override
        public void info(@Nullable String msg) {
            logger.info(msg);
        }

        @Override
        public void info(@Nullable String format, @Nullable Object arg) {
            logger.info(format, arg);
        }

        @Override
        public void info(@Nullable String format, @Nullable Object arg1, @Nullable Object arg2) {
            logger.info(format, arg1, arg2);
        }

        @Override
        public void info(@Nullable String format, @Nullable Object... arguments) {
            logger.info(format, arguments);
        }

        @Override
        public void info(@Nullable String msg, @Nullable Throwable t) {
            logger.info(msg, t);
        }

        @Override
        public boolean isWarnEnabled() {
            return logger.isWarnEnabled();
        }

        @Override
        public void warn(@Nullable String msg) {
            logger.warn(msg);
        }

        @Override
        public void warn(@Nullable String format, @Nullable Object arg) {
            logger.warn(format, arg);
        }

        @Override
        public void warn(@Nullable String format, @Nullable Object... arguments) {
            logger.warn(format, arguments);
        }

        @Override
        public void warn(@Nullable String format, @Nullable Object arg1, @Nullable Object arg2) {
            logger.warn(format, arg1, arg2);
        }

        @Override
        public void warn(@Nullable String msg, @Nullable Throwable t) {
            logger.warn(msg, t);
        }

        @Override
        public boolean isErrorEnabled() {
            return logger.isErrorEnabled();
        }

        @Override
        public void error(@Nullable String msg) {
            logger.error(msg);
        }

        @Override
        public void error(@Nullable String format, @Nullable Object arg) {
            logger.error(format, arg);
        }

        @Override
        public void error(@Nullable String format, @Nullable Object arg1, @Nullable Object arg2) {
            logger.error(format, arg1, arg2);
        }

        @Override
        public void error(@Nullable String format, @Nullable Object... arguments) {
            logger.error(format, arguments);
        }

        @Override
        public void error(@Nullable String msg, @Nullable Throwable t) {
            logger.error(msg, t);
        }
    }
}
