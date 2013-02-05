/**
 * Copyright 2012-2013 the original author or authors.
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
package io.informant.shaded.slf4j.impl;

import io.informant.shaded.slf4j.helpers.MarkerIgnoringBase;
import checkers.igj.quals.Immutable;
import checkers.nullness.quals.Nullable;

/**
 * @author Trask Stalnaker
 * @since 0.5
 */
@Immutable
@SuppressWarnings("serial")
public class ExtraShadedLoggerAdapter extends MarkerIgnoringBase {

    private final io.informant.shaded.slf4jx.Logger logger;

    ExtraShadedLoggerAdapter(io.informant.shaded.slf4jx.Logger logger) {
        this.logger = logger;
    }

    @Override
    public String getName() {
        return logger.getName();
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

    public void trace(String format, @Nullable Object... arguments) {
        logger.trace(format, arguments);
    }

    public void trace(@Nullable String msg, Throwable t) {
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

    public void debug(String format, @Nullable Object... arguments) {
        logger.debug(format, arguments);
    }

    public void debug(@Nullable String msg, Throwable t) {
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

    public void info(String format, @Nullable Object... arguments) {
        logger.info(format, arguments);
    }

    public void info(@Nullable String msg, Throwable t) {
        logger.info(msg, t);
    }

    public boolean isWarnEnabled() {
        return logger.isWarnEnabled();
    }

    public void warn(String msg) {
        logger.warn(msg);
    }

    public void warn(String format, @Nullable Object arg) {
        logger.warn(format, arg);
    }

    public void warn(String format, @Nullable Object arg1, @Nullable Object arg2) {
        logger.warn(format, arg1, arg2);
    }

    public void warn(String format, @Nullable Object... arguments) {
        logger.warn(format, arguments);
    }

    public void warn(@Nullable String msg, Throwable t) {
        logger.warn(msg, t);
    }

    public boolean isErrorEnabled() {
        return logger.isErrorEnabled();
    }

    public void error(String msg) {
        logger.error(msg);
    }

    public void error(String format, @Nullable Object arg) {
        logger.error(format, arg);
    }

    public void error(String format, @Nullable Object arg1, @Nullable Object arg2) {
        logger.error(format, arg1, arg2);
    }

    public void error(String format, @Nullable Object... arguments) {
        logger.error(format, arguments);
    }

    public void error(@Nullable String msg, Throwable t) {
        logger.error(msg, t);
    }
}
