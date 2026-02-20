/*
 * Copyright 2014-2026 the original author or authors.
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

import java.lang.reflect.Method;

import org.glowroot.agent.plugin.api.ClassInfo;
import org.glowroot.agent.plugin.api.Logger;
import org.glowroot.agent.plugin.api.checker.Nullable;
import org.glowroot.agent.plugin.api.util.Reflection;

// this is used instead of @Shim for ch.qos.logback.classic.spi.ILoggingEvent because Logback 1.5.x
// changed ILoggingEvent's class hierarchy (it now extends DeferredProcessingAware), which causes
// an IncompatibleClassChangeError when the @Shim checkcast is applied at weave time
public class ILoggingEventInvoker {

    private static final Logger logger = Logger.getLogger(ILoggingEventInvoker.class);

    private final @Nullable Method getFormattedMessageMethod;
    private final @Nullable Method getLevelMethod;
    private final @Nullable Method getLoggerNameMethod;
    private final @Nullable Method getThrowableProxyMethod;
    private final @Nullable Method getThrowableMethod;
    private final @Nullable Method toIntMethod;

    public ILoggingEventInvoker(ClassInfo classInfo) {
        ClassLoader loader = classInfo.getLoader();
        Class<?> iLoggingEventClass = Reflection.getClassWithWarnIfNotFound(
                "ch.qos.logback.classic.spi.ILoggingEvent", loader);
        getFormattedMessageMethod = Reflection.getMethod(iLoggingEventClass, "getFormattedMessage");
        getLevelMethod = Reflection.getMethod(iLoggingEventClass, "getLevel");
        getLoggerNameMethod = Reflection.getMethod(iLoggingEventClass, "getLoggerName");
        getThrowableProxyMethod = Reflection.getMethod(iLoggingEventClass, "getThrowableProxy");
        Class<?> levelClass =
                Reflection.getClassWithWarnIfNotFound("ch.qos.logback.classic.Level", loader);
        toIntMethod = Reflection.getMethod(levelClass, "toInt");
        getThrowableMethod = resolveGetThrowableMethod(loader);
    }

    private static @Nullable Method resolveGetThrowableMethod(ClassLoader loader) {
        try {
            Class<?> cls =
                    Class.forName("ch.qos.logback.classic.spi.ThrowableProxy", false, loader);
            return cls.getMethod("getThrowable");
        } catch (Throwable t) {
            logger.debug(t.getMessage(), t);
        }
        try {
            Class<?> cls = Class.forName("ch.qos.logback.classic.spi.ThrowableInformation", false,
                    loader);
            return cls.getMethod("getThrowable");
        } catch (Throwable t) {
            logger.debug(t.getMessage(), t);
        }
        return null;
    }

    String getFormattedMessage(Object loggingEvent) {
        return Reflection.invokeWithDefault(getFormattedMessageMethod, loggingEvent, "");
    }

    int getLevel(Object loggingEvent) {
        Object level = Reflection.invoke(getLevelMethod, loggingEvent);
        if (level == null) {
            return 0;
        }
        return Reflection.invokeWithDefault(toIntMethod, level, 0);
    }

    @Nullable
    String getLoggerName(Object loggingEvent) {
        return Reflection.</*@Nullable*/ String>invoke(getLoggerNameMethod, loggingEvent);
    }

    @Nullable
    Throwable getThrowable(Object loggingEvent) {
        Object throwableProxy = Reflection.invoke(getThrowableProxyMethod, loggingEvent);
        if (throwableProxy == null) {
            return null;
        }
        return Reflection.</*@Nullable*/ Throwable>invoke(getThrowableMethod, throwableProxy);
    }
}
