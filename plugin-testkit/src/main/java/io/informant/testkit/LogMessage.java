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
package io.informant.testkit;

import io.informant.testkit.Trace.CapturedException;
import checkers.nullness.quals.Nullable;

import com.google.common.base.Objects;

/**
 * @author Trask Stalnaker
 * @since 0.5
 */
public class LogMessage {

    private long timestamp;
    @Nullable
    private Level level;
    @Nullable
    private String loggerName;
    @Nullable
    private String text;
    @Nullable
    private CapturedException exception;

    public long getTimestamp() {
        return timestamp;
    }

    @Nullable
    public Level getLevel() {
        return level;
    }

    @Nullable
    public String getLoggerName() {
        return loggerName;
    }

    @Nullable
    public String getText() {
        return text;
    }

    @Nullable
    public CapturedException getException() {
        return exception;
    }

    @Override
    public boolean equals(@Nullable Object obj) {
        if (obj instanceof LogMessage) {
            LogMessage that = (LogMessage) obj;
            return Objects.equal(timestamp, that.timestamp)
                    && Objects.equal(level, that.level)
                    && Objects.equal(loggerName, that.loggerName)
                    && Objects.equal(text, that.text)
                    && Objects.equal(exception, that.exception);
        }
        return false;
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(timestamp, level, loggerName, text, exception);
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

    public enum Level {
        TRACE, DEBUG, INFO, WARN, ERROR;
    }
}
