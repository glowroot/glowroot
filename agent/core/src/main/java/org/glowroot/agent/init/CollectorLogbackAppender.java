/*
 * Copyright 2015-2017 the original author or authors.
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
package org.glowroot.agent.init;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.IThrowableProxy;
import ch.qos.logback.classic.spi.StackTraceElementProxy;
import ch.qos.logback.core.UnsynchronizedAppenderBase;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.glowroot.agent.model.ErrorMessage;
import org.glowroot.wire.api.model.CollectorServiceOuterClass.LogEvent;
import org.glowroot.wire.api.model.Proto;

class CollectorLogbackAppender extends UnsynchronizedAppenderBase<ILoggingEvent> {

    private static final Logger logger = LoggerFactory.getLogger(CollectorLogbackAppender.class);

    private final CollectorProxy collector;

    @SuppressWarnings("nullness:type.argument.type.incompatible")
    private final ThreadLocal<Boolean> inLogging = new ThreadLocal<Boolean>() {
        @Override
        protected Boolean initialValue() {
            return false;
        }
    };

    CollectorLogbackAppender(CollectorProxy collector) {
        this.collector = collector;
    }

    @Override
    protected void append(ILoggingEvent event) {
        if (inLogging.get()) {
            return;
        }
        if (event.getLevel() == Level.DEBUG && event.getLoggerName().startsWith("io.grpc.")) {
            return;
        }
        if (event.getLoggerName().startsWith("org.glowroot.central.")
                || event.getLoggerName().startsWith("org.glowroot.ui.")) {
            // this can happen during integration tests when agent and the central collector are
            // running in the same JVM (using LocalContainer for agent)
            return;
        }
        LogEvent.Builder builder = LogEvent.newBuilder()
                .setTimestamp(event.getTimeStamp())
                .setLevel(toProto(event.getLevel()))
                .setLoggerName(event.getLoggerName())
                .setMessage(event.getFormattedMessage());
        IThrowableProxy throwable = event.getThrowableProxy();
        if (throwable != null) {
            builder.setThrowable(toProto(throwable));
        }
        LogEvent logEvent = builder.build();
        inLogging.set(true);
        try {
            collector.log(logEvent);
        } catch (Exception e) {
            // this won't be recursively sent to collector.log() thanks to ThreadLocal check
            logger.error(e.getMessage(), e);
        } finally {
            inLogging.set(false);
        }
    }

    private static LogEvent.Level toProto(Level level) {
        switch (level.toInt()) {
            case Level.TRACE_INT:
                return LogEvent.Level.TRACE;
            case Level.DEBUG_INT:
                return LogEvent.Level.DEBUG;
            case Level.INFO_INT:
                return LogEvent.Level.INFO;
            case Level.WARN_INT:
                return LogEvent.Level.WARN;
            case Level.ERROR_INT:
                return LogEvent.Level.ERROR;
            default:
                // do not log (could end up in recursive loop)
                return LogEvent.Level.NONE;
        }
    }

    private static Proto.Throwable toProto(IThrowableProxy t) {
        Proto.Throwable.Builder builder = Proto.Throwable.newBuilder()
                .setClassName(t.getClassName());
        String message = t.getMessage();
        if (message != null) {
            builder.setMessage(message);
        }
        for (StackTraceElementProxy element : t.getStackTraceElementProxyArray()) {
            builder.addStackTraceElement(ErrorMessage.toProto(element.getStackTraceElement()));
        }
        builder.setFramesInCommonWithEnclosing(t.getCommonFrames());
        IThrowableProxy cause = t.getCause();
        if (cause != null) {
            builder.setCause(toProto(cause));
        }
        for (IThrowableProxy suppressed : t.getSuppressed()) {
            builder.addSuppressed(toProto(suppressed));
        }
        return builder.build();
    }
}
