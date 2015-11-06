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

import org.glowroot.wire.api.Collector;
import org.glowroot.wire.api.model.LogEventOuterClass.LogEvent;

class GrpcLogbackAppender extends AppenderBase<ILoggingEvent> {

    private final Collector collector;

    GrpcLogbackAppender(Collector collector) {
        this.collector = collector;
    }

    @Override
    protected void append(ILoggingEvent event) {
        if (event.getLoggerName().equals(GrpcCollector.class.getName())) {
            // don't send this server error back to server
            return;
        }
        LogEvent logEvent = LogEvent.newBuilder()
                .setTimestamp(event.getTimeStamp())
                .setLevel(convert(event.getLevel()))
                .setLoggerName(event.getLoggerName())
                .setFormattedMessage(event.getFormattedMessage())
                .build();
        try {
            collector.log(logEvent);
        } catch (Exception e) {
            // do not log (could end up in recursive loop)
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
