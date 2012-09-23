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
package org.informantproject.local.log;

import javax.annotation.Nullable;
import javax.annotation.concurrent.Immutable;

import org.informantproject.core.log.Level;

/**
 * @author Trask Stalnaker
 * @since 0.5
 */
@Immutable
public class LogMessage {

    private final long timestamp;
    private final Level level;
    private final String text;
    @Nullable
    private final String exception; // json data

    public static LogMessage from(long timestamp, Level level, String text) {
        return from(timestamp, level, text, null);
    }

    public static LogMessage from(long timestamp, Level level, String text,
            @Nullable String exception) {

        return new LogMessage(timestamp, level, text, exception);
    }

    private LogMessage(long timestamp, Level level, String text, @Nullable String exception) {
        this.timestamp = timestamp;
        this.level = level;
        this.text = text;
        this.exception = exception;
    }

    public long getTimestamp() {
        return timestamp;
    }

    public Level getLevel() {
        return level;
    }

    public String getText() {
        return text;
    }

    @Nullable
    public String getException() {
        return exception;
    }
}
