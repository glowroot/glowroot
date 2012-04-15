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
package org.informantproject.api;

/**
 * @author Trask Stalnaker
 * @since 0.5
 */
public class MessageSpanDetail implements SpanDetail {

    private final String format;
    private final Object[] args;
    // span is only accessed by the trace thread so doesn't need to be volatile
    private Span span;
    private volatile boolean hasReturnValue;
    private volatile Object returnValue;
    private volatile Throwable throwable;

    public MessageSpanDetail(String format, Object... args) {
        this.format = format;
        this.args = args;
    }

    public void setSpan(Span span) {
        this.span = span;
    }

    public void setReturnValue(Object returnValue) {
        this.returnValue = returnValue;
        hasReturnValue = true;
    }

    public void setThrowable(Throwable throwable) {
        this.throwable = throwable;
    }

    public Span getSpan() {
        return span;
    }

    public String getDescription() {
        if (hasReturnValue) {
            return String.format(format, args) + " => " + String.valueOf(returnValue);
        } else if (throwable != null) {
            return String.format(format, args) + " => " + throwable.getClass().getName() + ": "
                    + throwable.getMessage();
        } else {
            return String.format(format, args);
        }
    }

    public SpanContextMap getContextMap() {
        return null;
    }
}
