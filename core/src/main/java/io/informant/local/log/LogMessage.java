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
package io.informant.local.log;

import io.informant.core.log.Level;
import checkers.igj.quals.Immutable;
import checkers.nullness.quals.Nullable;

import com.google.common.base.Objects;

/**
 * @author Trask Stalnaker
 * @since 0.5
 */
@Immutable
public class LogMessage {

    private final long timestamp;
    private final Level level;
    private final String loggerName;
    @Nullable
    private final String text;
    @Nullable
    private final String exception; // json data

    public static LogMessage from(long timestamp, Level level, String loggerName,
            @Nullable String text) {
        return from(timestamp, level, loggerName, text, null);
    }

    public static LogMessage from(long timestamp, Level level, String loggerName,
            @Nullable String text, @Nullable String exception) {
        return new LogMessage(timestamp, level, loggerName, text, exception);
    }

    private LogMessage(long timestamp, Level level, String loggerName, @Nullable String text,
            @Nullable String exception) {
        this.timestamp = timestamp;
        this.level = level;
        this.loggerName = loggerName;
        this.text = text;
        this.exception = exception;
    }

    public long getTimestamp() {
        return timestamp;
    }

    public Level getLevel() {
        return level;
    }

    public String getLoggerName() {
        return loggerName;
    }

    @Nullable
    public String getText() {
        return text;
    }

    @Nullable
    public String getException() {
        return exception;
    }

    @Override
    public String toString() {
        return Objects.toStringHelper(this)
                .add("timestamp", timestamp)
                .add("level", level)
                .add("loggerName", loggerName)
                .add("text", text)
                .add("exception", exception)
                .toString();
    }
}
