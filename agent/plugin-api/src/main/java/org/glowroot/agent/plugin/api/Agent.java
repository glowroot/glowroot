/*
 * Copyright 2015-2016 the original author or authors.
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
package org.glowroot.agent.plugin.api;

import java.lang.reflect.Method;

import javax.annotation.Nullable;

import com.google.common.annotations.VisibleForTesting;
import org.slf4j.LoggerFactory;

import org.glowroot.agent.plugin.api.config.ConfigService;
import org.glowroot.agent.plugin.api.internal.NopConfigService;
import org.glowroot.agent.plugin.api.internal.NopTransactionService.NopTimerName;
import org.glowroot.agent.plugin.api.internal.ServiceRegistry;
import org.glowroot.agent.plugin.api.weaving.Pointcut;

public class Agent {

    private static final org.slf4j.Logger logger = LoggerFactory.getLogger(Agent.class);

    private static final Class<?> registryClass;
    private static final Method getInstanceMethod;

    static {
        try {
            registryClass = Class.forName("org.glowroot.agent.impl.ServiceRegistryImpl");
            getInstanceMethod = registryClass.getMethod("getInstance");
        } catch (Exception e) {
            // this really really really shouldn't happen
            logger.error(e.getMessage(), e);
            throw new AssertionError(e);
        }
    }

    private Agent() {}

    /**
     * Returns the {@code TimerName} instance for the specified {@code adviceClass}.
     * 
     * {@code adviceClass} must be a {@code Class} with a {@link Pointcut} annotation that has a
     * non-empty {@link Pointcut#timerName()}. This is how the {@code TimerName} is named.
     * 
     * The same {@code TimerName} is always returned for a given {@code adviceClass}.
     * 
     * The return value can (and should) be cached by the plugin for the life of the jvm to avoid
     * looking it up every time it is needed (which is often).
     */
    public static TimerName getTimerName(Class<?> adviceClass) {
        ServiceRegistry serviceRegistry = getServiceRegistry();
        if (serviceRegistry == null) {
            return NopTimerName.INSTANCE;
        } else {
            return serviceRegistry.getTimerName(adviceClass);
        }
    }

    public static TimerName getTimerName(String name) {
        ServiceRegistry serviceRegistry = getServiceRegistry();
        if (serviceRegistry == null) {
            return NopTimerName.INSTANCE;
        } else {
            return serviceRegistry.getTimerName(name);
        }
    }

    /**
     * Returns the {@code ConfigService} instance for the specified {@code pluginId}.
     * 
     * The return value can (and should) be cached by the plugin for the life of the jvm to avoid
     * looking it up every time it is needed (which is often).
     */
    public static ConfigService getConfigService(String pluginId) {
        ServiceRegistry serviceRegistry = getServiceRegistry();
        if (serviceRegistry == null) {
            return NopConfigService.INSTANCE;
        } else {
            return serviceRegistry.getConfigService(pluginId);
        }
    }

    public static Logger getLogger(Class<?> clazz) {
        return new LoggerImpl(LoggerFactory.getLogger(clazz));
    }

    private static @Nullable ServiceRegistry getServiceRegistry() {
        try {
            return (ServiceRegistry) getInstanceMethod.invoke(null);
        } catch (Exception e) {
            // this really really really shouldn't happen
            logger.error(e.getMessage(), e);
            return null;
        }
    }

    @VisibleForTesting
    static class LoggerImpl implements Logger {

        private final org.slf4j.Logger logger;

        @VisibleForTesting
        public LoggerImpl(org.slf4j.Logger logger) {
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
