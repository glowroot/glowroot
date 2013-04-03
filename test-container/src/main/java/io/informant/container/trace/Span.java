/**
 * Copyright 2013 the original author or authors.
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
package io.informant.container.trace;

import java.util.List;

import checkers.nullness.quals.Nullable;
import com.google.common.base.Objects;

/**
 * @author Trask Stalnaker
 * @since 0.5
 */
public class Span {

    private long offset;
    private long duration;
    private boolean active;
    private int index;
    private int nestingLevel;
    // message is null for spans created via PluginServices.addErrorSpan()
    @Nullable
    private Message message;
    @Nullable
    private ErrorMessage error;
    @Nullable
    private List<String> stackTrace;
    private boolean limitExceededMarker;
    private boolean limitExtendedMarker;

    public long getOffset() {
        return offset;
    }

    public long getDuration() {
        return duration;
    }

    public boolean isActive() {
        return active;
    }

    public int getIndex() {
        return index;
    }

    public int getNestingLevel() {
        return nestingLevel;
    }

    @Nullable
    public Message getMessage() {
        return message;
    }

    @Nullable
    public ErrorMessage getError() {
        return error;
    }

    @Nullable
    public List<String> getStackTrace() {
        return stackTrace;
    }

    public boolean isLimitExceededMarker() {
        return limitExceededMarker;
    }

    public boolean isLimitExtendedMarker() {
        return limitExtendedMarker;
    }

    @Override
    public String toString() {
        return Objects.toStringHelper(this)
                .add("offset", offset)
                .add("duration", duration)
                .add("active", active)
                .add("index", index)
                .add("nestingLevel", nestingLevel)
                .add("message", message)
                .add("error", error)
                .add("stackTrace", stackTrace)
                .add("limitExceededMarker", limitExceededMarker)
                .add("limitExtendedMarker", limitExtendedMarker)
                .toString();
    }
}
