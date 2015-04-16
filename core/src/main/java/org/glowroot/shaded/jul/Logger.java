/*
 * Copyright 2014-2015 the original author or authors.
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
package org.glowroot.shaded.jul;

import java.text.MessageFormat;
import java.util.ResourceBundle;

import javax.annotation.Nullable;

import com.google.common.annotations.VisibleForTesting;

public class Logger {

    private final org.slf4j.Logger slf4jLogger;

    public static Logger getLogger(String name) {
        return new Logger(name);
    }

    private Logger(String name) {
        this(org.slf4j.LoggerFactory.getLogger(name));
    }

    @VisibleForTesting
    Logger(org.slf4j.Logger logger) {
        this.slf4jLogger = logger;
    }

    @VisibleForTesting
    org.slf4j.Logger getSlf4jLogger() {
        return slf4jLogger;
    }

    public String getName() {
        return slf4jLogger.getName();
    }

    public void severe(String msg) {
        slf4jLogger.error(msg);
    }

    public void warning(String msg) {
        slf4jLogger.warn(msg);
    }

    public void info(String msg) {
        slf4jLogger.info(msg);
    }

    public void config(String msg) {
        slf4jLogger.info(msg);
    }

    public void fine(String msg) {
        slf4jLogger.debug(msg);
    }

    public void finer(String msg) {
        slf4jLogger.trace(msg);
    }

    public void finest(String msg) {
        slf4jLogger.trace(msg);
    }

    public void log(Level level, String msg) {
        if (level.intValue() >= Level.SEVERE.intValue()) {
            slf4jLogger.error(msg);
        } else if (level.intValue() >= Level.WARNING.intValue()) {
            slf4jLogger.warn(msg);
        } else if (level.intValue() >= Level.CONFIG.intValue()) {
            slf4jLogger.info(msg);
        } else if (level.intValue() >= Level.FINE.intValue()) {
            slf4jLogger.debug(msg);
        } else {
            slf4jLogger.trace(msg);
        }
    }

    public void log(Level level, String msg, Object param1) {
        if (level.intValue() >= Level.SEVERE.intValue()) {
            if (slf4jLogger.isErrorEnabled()) {
                slf4jLogger.error(MessageFormat.format(msg, param1));
            }
        } else if (level.intValue() >= Level.WARNING.intValue()) {
            if (slf4jLogger.isWarnEnabled()) {
                slf4jLogger.warn(MessageFormat.format(msg, param1));
            }
        } else if (level.intValue() >= Level.CONFIG.intValue()) {
            if (slf4jLogger.isInfoEnabled()) {
                slf4jLogger.info(MessageFormat.format(msg, param1));
            }
        } else if (level.intValue() >= Level.FINE.intValue()) {
            if (slf4jLogger.isDebugEnabled()) {
                slf4jLogger.debug(MessageFormat.format(msg, param1));
            }
        } else {
            if (slf4jLogger.isTraceEnabled()) {
                slf4jLogger.trace(MessageFormat.format(msg, param1));
            }
        }
    }

    public void log(Level level, String msg, Object[] params) {
        if (level.intValue() >= Level.SEVERE.intValue()) {
            if (slf4jLogger.isErrorEnabled()) {
                slf4jLogger.error(MessageFormat.format(msg, params));
            }
        } else if (level.intValue() >= Level.WARNING.intValue()) {
            if (slf4jLogger.isWarnEnabled()) {
                slf4jLogger.warn(MessageFormat.format(msg, params));
            }
        } else if (level.intValue() >= Level.CONFIG.intValue()) {
            if (slf4jLogger.isInfoEnabled()) {
                slf4jLogger.info(MessageFormat.format(msg, params));
            }
        } else if (level.intValue() >= Level.FINE.intValue()) {
            if (slf4jLogger.isDebugEnabled()) {
                slf4jLogger.debug(MessageFormat.format(msg, params));
            }
        } else {
            if (slf4jLogger.isTraceEnabled()) {
                slf4jLogger.trace(MessageFormat.format(msg, params));
            }
        }
    }

    public void log(Level level, String msg, Throwable thrown) {
        if (level.intValue() >= Level.SEVERE.intValue()) {
            slf4jLogger.error(msg, thrown);
        } else if (level.intValue() >= Level.WARNING.intValue()) {
            slf4jLogger.warn(msg, thrown);
        } else if (level.intValue() >= Level.CONFIG.intValue()) {
            slf4jLogger.info(msg, thrown);
        } else if (level.intValue() >= Level.FINE.intValue()) {
            slf4jLogger.debug(msg, thrown);
        } else {
            slf4jLogger.trace(msg, thrown);
        }
    }

    public boolean isLoggable(Level level) {
        if (level.intValue() >= Level.SEVERE.intValue()) {
            return slf4jLogger.isErrorEnabled();
        } else if (level.intValue() >= Level.WARNING.intValue()) {
            return slf4jLogger.isWarnEnabled();
        } else if (level.intValue() >= Level.CONFIG.intValue()) {
            return slf4jLogger.isInfoEnabled();
        } else if (level.intValue() >= Level.FINE.intValue()) {
            return slf4jLogger.isDebugEnabled();
        } else {
            return slf4jLogger.isTraceEnabled();
        }
    }

    public Level getLevel() {
        if (slf4jLogger.isErrorEnabled()) {
            return Level.SEVERE;
        } else if (slf4jLogger.isWarnEnabled()) {
            return Level.WARNING;
        } else if (slf4jLogger.isInfoEnabled()) {
            return Level.CONFIG;
        } else if (slf4jLogger.isDebugEnabled()) {
            return Level.FINE;
        } else if (slf4jLogger.isTraceEnabled()) {
            return Level.FINEST;
        } else {
            return Level.OFF;
        }
    }

    @SuppressWarnings("unused")
    public void logp(Level level, String sourceClass, String sourceMethod, String msg) {
        log(level, msg);
    }

    @SuppressWarnings("unused")
    public void logp(Level level, String sourceClass, String sourceMethod, String msg,
            Object param1) {
        log(level, msg, param1);
    }

    @SuppressWarnings("unused")
    public void logp(Level level, String sourceClass, String sourceMethod, String msg,
            Object[] params) {
        log(level, msg, params);
    }

    @SuppressWarnings("unused")
    public void logp(Level level, String sourceClass, String sourceMethod, String msg,
            Throwable thrown) {
        log(level, msg, thrown);
    }

    @SuppressWarnings("unused")
    public void logrb(Level level, String sourceClass, String sourceMethod, String bundleName,
            String msg) {
        log(level, msg);
    }

    @SuppressWarnings("unused")
    public void logrb(Level level, String sourceClass, String sourceMethod, String bundleName,
            String msg, Object param1) {
        log(level, msg, param1);
    }

    @SuppressWarnings("unused")
    public void logrb(Level level, String sourceClass, String sourceMethod, String bundleName,
            String msg, Object[] params) {
        log(level, msg, params);
    }

    @SuppressWarnings("unused")
    public void logrb(Level level, String sourceClass, String sourceMethod, String bundleName,
            String msg, Throwable thrown) {
        log(level, msg, thrown);
    }

    @SuppressWarnings("unused")
    public void entering(String sourceClass, String sourceMethod) {}

    @SuppressWarnings("unused")
    public void entering(String sourceClass, String sourceMethod, Object param1) {}

    @SuppressWarnings("unused")
    public void entering(String sourceClass, String sourceMethod, Object[] params) {}

    @SuppressWarnings("unused")
    public void exiting(String sourceClass, String sourceMethod) {}

    @SuppressWarnings("unused")
    public void exiting(String sourceClass, String sourceMethod, Object result) {}

    @SuppressWarnings("unused")
    public void throwing(String sourceClass, String sourceMethod, Throwable thrown) {}

    public @Nullable ResourceBundle getResourceBundle() {
        return null;
    }

    public @Nullable String getResourceBundleName() {
        return null;
    }
}
