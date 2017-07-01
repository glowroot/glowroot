/*
 * Copyright 2017 the original author or authors.
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
package org.glowroot.agent.jul;

import java.util.ResourceBundle;

import javax.annotation.Nullable;

public class LogRecord {

    private @Nullable String loggerName;
    private @Nullable ResourceBundle resourceBundle;
    private @Nullable String resourceBundleName;
    private Level level;
    private long sequenceNumber;
    private @Nullable String sourceClassName;
    private @Nullable String sourceMethodName;
    private @Nullable String message;
    private @Nullable Object /*@Nullable*/ [] parameters;
    private int threadID;
    private long millis;
    private @Nullable Throwable thrown;

    public LogRecord(Level level, String message) {
        this.level = level;
        this.message = message;
    }

    public @Nullable String getLoggerName() {
        return loggerName;
    }

    public void setLoggerName(@Nullable String loggerName) {
        this.loggerName = loggerName;
    }

    public @Nullable ResourceBundle getResourceBundle() {
        return resourceBundle;
    }

    public void setResourceBundle(@Nullable ResourceBundle resourceBundle) {
        this.resourceBundle = resourceBundle;
    }

    public @Nullable String getResourceBundleName() {
        return resourceBundleName;
    }

    public void setResourceBundleName(@Nullable String resourceBundleName) {
        this.resourceBundleName = resourceBundleName;
    }

    public Level getLevel() {
        return level;
    }

    public void setLevel(Level level) {
        this.level = level;
    }

    public long getSequenceNumber() {
        return sequenceNumber;
    }

    public void setSequenceNumber(long sequenceNumber) {
        this.sequenceNumber = sequenceNumber;
    }

    public @Nullable String getSourceClassName() {
        return sourceClassName;
    }

    public void setSourceClassName(@Nullable String sourceClassName) {
        this.sourceClassName = sourceClassName;
    }

    public @Nullable String getSourceMethodName() {
        return sourceMethodName;
    }

    public void setSourceMethodName(@Nullable String sourceMethodName) {
        this.sourceMethodName = sourceMethodName;
    }

    public @Nullable String getMessage() {
        return message;
    }

    public void setMessage(@Nullable String message) {
        this.message = message;
    }

    public @Nullable Object /*@Nullable*/ [] getParameters() {
        return parameters;
    }

    public void setParameters(@Nullable Object /*@Nullable*/ [] parameters) {
        this.parameters = parameters;
    }

    public int getThreadID() {
        return threadID;
    }

    public void setThreadID(int threadID) {
        this.threadID = threadID;
    }

    public long getMillis() {
        return millis;
    }

    public void setMillis(long millis) {
        this.millis = millis;
    }

    public @Nullable Throwable getThrown() {
        return thrown;
    }

    public void setThrown(@Nullable Throwable thrown) {
        this.thrown = thrown;
    }
}
