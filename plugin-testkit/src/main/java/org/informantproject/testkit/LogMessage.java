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
package org.informantproject.testkit;

import javax.annotation.Nullable;

import org.informantproject.testkit.Trace.CapturedException;

import com.google.common.base.Objects;

/**
 * @author Trask Stalnaker
 * @since 0.5
 */
public class LogMessage {

    private long timestamp;
    private Level level;
    private String loggerName;
    private String text;
    @Nullable
    private CapturedException exception;

    public long getTimestamp() {
        return timestamp;
    }

    public Level getLevel() {
        return level;
    }

    public String getLoggerName() {
        return loggerName;
    }

    public String getText() {
        return text;
    }

    public CapturedException getException() {
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

    public enum Level {
        TRACE, DEBUG, INFO, WARN, ERROR;
    }
}
