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

import java.util.List;

import javax.annotation.Nullable;

import com.google.common.collect.Lists;

/**
 * @author Trask Stalnaker
 * @since 0.5
 */
public class PointcutMessageSupplier extends Supplier<Message> {

    private final String template;
    private final Object[] args;
    // stopwatch is only accessed by the trace thread so doesn't need to be volatile
    @Nullable
    private Stopwatch stopwatch;
    private volatile boolean hasReturnValue;
    @Nullable
    private volatile Object returnValue;
    @Nullable
    private volatile Throwable throwable;

    public PointcutMessageSupplier(String template, Object... args) {
        this.template = template;
        this.args = args;
    }

    public void setStopwatch(Stopwatch stopwatch) {
        this.stopwatch = stopwatch;
    }

    public void setReturnValue(Object returnValue) {
        this.returnValue = returnValue;
        hasReturnValue = true;
    }

    public void setThrowable(Throwable throwable) {
        this.throwable = throwable;
    }

    @Nullable
    public Stopwatch getStopwatch() {
        return stopwatch;
    }

    @Override
    public Message get() {
        if (hasReturnValue) {
            List<Object> messageArgs = Lists.newArrayList(args);
            messageArgs.add(returnValue);
            return Message.of(template + " => {{returnValue}}", messageArgs);
        } else if (throwable != null) {
            List<Object> messageArgs = Lists.newArrayList(args);
            messageArgs.add(throwable.getClass().getName());
            messageArgs.add(throwable.getMessage());
            return Message.of(template + " => {{throwable}}: {{throwableMessage}}", messageArgs);
        } else {
            return Message.of(template, args);
        }
    }
}
