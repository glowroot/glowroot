/*
 * Copyright 2014 the original author or authors.
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

public class Logger {

    private final org.slf4j.Logger logger;

    public static Logger getLogger(String name) {
        return new Logger(name);
    }

    private Logger(String name) {
        logger = org.slf4j.LoggerFactory.getLogger(name);
    }

    public void severe(String msg) {
        logger.error(msg);
    }

    public void warning(String msg) {
        logger.warn(msg);
    }

    public void info(String msg) {
        logger.info(msg);
    }

    public void config(String msg) {
        logger.info(msg);
    }

    public void fine(String msg) {
        logger.debug(msg);
    }

    public void finer(String msg) {
        logger.trace(msg);
    }

    public void finest(String msg) {
        logger.trace(msg);
    }

    public void log(Level level, String msg) {
        if (level.intValue() >= Level.SEVERE.intValue()) {
            logger.error(msg);
        } else if (level.intValue() >= Level.WARNING.intValue()) {
            logger.warn(msg);
        } else if (level.intValue() >= Level.CONFIG.intValue()) {
            logger.info(msg);
        } else if (level.intValue() >= Level.FINE.intValue()) {
            logger.debug(msg);
        } else {
            logger.trace(msg);
        }
    }

    public void log(Level level, String msg, Object param1) {
        if (level.intValue() >= Level.SEVERE.intValue()) {
            if (logger.isErrorEnabled()) {
                logger.error(MessageFormat.format(msg, param1));
            }
        } else if (level.intValue() >= Level.WARNING.intValue()) {
            if (logger.isWarnEnabled()) {
                logger.warn(MessageFormat.format(msg, param1));
            }
        } else if (level.intValue() >= Level.CONFIG.intValue()) {
            if (logger.isInfoEnabled()) {
                logger.info(MessageFormat.format(msg, param1));
            }
        } else if (level.intValue() >= Level.FINE.intValue()) {
            if (logger.isDebugEnabled()) {
                logger.debug(MessageFormat.format(msg, param1));
            }
        } else {
            if (logger.isTraceEnabled()) {
                logger.trace(MessageFormat.format(msg, param1));
            }
        }
    }

    public void log(Level level, String msg, Object[] params) {
        if (level.intValue() >= Level.SEVERE.intValue()) {
            if (logger.isErrorEnabled()) {
                logger.error(MessageFormat.format(msg, params));
            }
        } else if (level.intValue() >= Level.WARNING.intValue()) {
            if (logger.isWarnEnabled()) {
                logger.warn(MessageFormat.format(msg, params));
            }
        } else if (level.intValue() >= Level.CONFIG.intValue()) {
            if (logger.isInfoEnabled()) {
                logger.info(MessageFormat.format(msg, params));
            }
        } else if (level.intValue() >= Level.FINE.intValue()) {
            if (logger.isDebugEnabled()) {
                logger.debug(MessageFormat.format(msg, params));
            }
        } else {
            if (logger.isTraceEnabled()) {
                logger.trace(MessageFormat.format(msg, params));
            }
        }
    }

    public void log(Level level, String msg, Throwable thrown) {
        if (level.intValue() >= Level.SEVERE.intValue()) {
            logger.error(msg, thrown);
        } else if (level.intValue() >= Level.WARNING.intValue()) {
            logger.warn(msg, thrown);
        } else if (level.intValue() >= Level.CONFIG.intValue()) {
            logger.info(msg, thrown);
        } else if (level.intValue() >= Level.FINE.intValue()) {
            logger.debug(msg, thrown);
        } else {
            logger.trace(msg, thrown);
        }
    }

    public boolean isLoggable(Level level) {
        if (level.intValue() >= Level.SEVERE.intValue()) {
            return logger.isErrorEnabled();
        } else if (level.intValue() >= Level.WARNING.intValue()) {
            return logger.isWarnEnabled();
        } else if (level.intValue() >= Level.CONFIG.intValue()) {
            return logger.isInfoEnabled();
        } else if (level.intValue() >= Level.FINE.intValue()) {
            return logger.isDebugEnabled();
        } else {
            return logger.isTraceEnabled();
        }
    }
}
