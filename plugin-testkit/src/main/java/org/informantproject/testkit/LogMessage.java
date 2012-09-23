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

/**
 * @author Trask Stalnaker
 * @since 0.5
 */
public class LogMessage {

    private long timestamp;
    private Level level;
    private String text;
    @Nullable
    private String stackTraceHeader;
    @Nullable
    private String stackTrace;

    public long getTimestamp() {
        return timestamp;
    }

    public void setTimestamp(long timestamp) {
        this.timestamp = timestamp;
    }

    public Level getLevel() {
        return level;
    }

    public void setLevel(Level level) {
        this.level = level;
    }

    public String getText() {
        return text;
    }

    public void setText(String text) {
        this.text = text;
    }

    public String getStackTraceHeader() {
        return stackTraceHeader;
    }

    public void setStackTraceHeader(String stackTraceHeader) {
        this.stackTraceHeader = stackTraceHeader;
    }

    public String getStackTrace() {
        return stackTrace;
    }

    public void setStackTrace(String stackTrace) {
        this.stackTrace = stackTrace;
    }

    public enum Level {
        TRACE, DEBUG, INFO, WARN, ERROR;
    }
}
