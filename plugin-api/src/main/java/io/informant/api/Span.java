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
package io.informant.api;

import io.informant.api.weaving.OnReturn;

import java.util.concurrent.TimeUnit;

/**
 * See {@link PluginServices#startSpan(MessageSupplier, Metric)} for how to create and use
 * {@code Span} instances.
 * 
 * @author Trask Stalnaker
 * @since 0.5
 */
public interface Span {

    /**
     * End the span.
     */
    void end();

    /**
     * End the span and capture a stack trace if the span duration exceeds the specified
     * {@code threshold}.
     * 
     * @param threshold
     * @param unit
     */
    void endWithStackTrace(long threshold, TimeUnit unit);

    /**
     * End the span and add the specified {@code errorMessage} to the span.
     * 
     * If this is the root span, then the error flag on the trace is set. Traces can be filtered by
     * their error flag on the trace explorer page.
     * 
     * @param errorMessage
     */
    void endWithError(ErrorMessage errorMessage);

    /**
     * Returns the {@code MessageSupplier} that was supplied when the {@code Span} was created.
     * 
     * This can be useful (for example) to retrieve the {@code MessageSupplier} in @
     * {@link OnReturn} so that the return value can be added to the message produced by the
     * {@code MessageSupplier}.
     * 
     * @return the {@code MessageSupplier} that was supplied when the {@code Span} was created
     */
    MessageSupplier getMessageSupplier();
}
