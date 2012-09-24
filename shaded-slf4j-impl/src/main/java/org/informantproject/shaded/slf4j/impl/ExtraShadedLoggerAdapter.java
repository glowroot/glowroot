/**
 * Copyright 2012 the original author or authors.
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
package org.informantproject.shaded.slf4j.impl;

import org.informantproject.shaded.slf4j.helpers.MarkerIgnoringBase;

/**
 * @author Trask Stalnaker
 * @since 0.5
 */
@SuppressWarnings("serial")
public class ExtraShadedLoggerAdapter extends MarkerIgnoringBase {

    private final org.informantproject.shaded.slf4jx.Logger logger;

    ExtraShadedLoggerAdapter(org.informantproject.shaded.slf4jx.Logger logger) {
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

    public void trace(String format, Object arg) {
        logger.trace(format, arg);
    }

    public void trace(String format, Object arg1, Object arg2) {
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

    public void debug(String format, Object arg) {
        logger.debug(format, arg);
    }

    public void debug(String format, Object arg1, Object arg2) {
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

    public void info(String format, Object arg) {
        logger.info(format, arg);
    }

    public void info(String format, Object arg1, Object arg2) {
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
    }

    public void warn(String format, Object arg) {
        logger.warn(format, arg);
    }

    public void warn(String format, Object... arguments) {
        logger.warn(format, arguments);
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

    public void error(String format, Object... arguments) {
        logger.error(format, arguments);
    }

    public void error(String msg, Throwable t) {
        logger.error(msg, t);
    }
}
