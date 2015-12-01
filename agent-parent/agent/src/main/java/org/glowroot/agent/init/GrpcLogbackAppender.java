/*
 * Copyright 2015 the original author or authors.
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
import ch.qos.logback.core.AppenderBase;
import com.google.common.reflect.Reflection;
import io.netty.buffer.AbstractByteBuf;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.glowroot.wire.api.Collector;
import org.glowroot.wire.api.model.LogEventOuterClass.LogEvent;

class GrpcLogbackAppender extends AppenderBase<ILoggingEvent> {

    private static final Logger logger = LoggerFactory.getLogger(GrpcLogbackAppender.class);

    static {
        // explicit initializations are to work around NullPointerExceptions caused by class init
        // ordering, e.g. when running UiSandboxMain against central collector (and debug logging
        // enabled at root logger)
        Reflection.initialize(AbstractByteBuf.class);
        try {
            Class.forName("io.netty.channel.DefaultChannelHandlerInvoker$WriteTask", true,
                    GrpcLogbackAppender.class.getClassLoader());
        } catch (ClassNotFoundException e) {
            throw new AssertionError(e);
        }
    }

    private final Collector collector;

    @SuppressWarnings("nullness:type.argument.type.incompatible")
    private final ThreadLocal<Boolean> inLogging = new ThreadLocal<Boolean>() {
        @Override
        protected Boolean initialValue() {
            return false;
        }
    };

    GrpcLogbackAppender(Collector collector) {
        this.collector = collector;
    }

    @Override
    protected void append(ILoggingEvent event) {
        if (inLogging.get()) {
            return;
        }
        if (Thread.currentThread().getName().startsWith("Glowroot-grpc-worker-ELG-")) {
            return;
        }
        LogEvent logEvent = LogEvent.newBuilder()
                .setTimestamp(event.getTimeStamp())
                .setLevel(convert(event.getLevel()))
                .setLoggerName(event.getLoggerName())
                .setFormattedMessage(event.getFormattedMessage())
                .build();
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

    private static LogEvent.Level convert(Level level) {
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
}
