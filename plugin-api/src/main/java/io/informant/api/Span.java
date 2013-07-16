/*
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

import java.util.concurrent.TimeUnit;

import checkers.nullness.quals.Nullable;

import io.informant.api.weaving.OnAfter;
import io.informant.api.weaving.OnReturn;
import io.informant.api.weaving.OnThrow;
import io.informant.api.weaving.Pointcut;

/**
 * See {@link PluginServices#startSpan(MessageSupplier, MetricName)} for how to create and use
 * {@code Span} instances.
 * 
 * @author Trask Stalnaker
 * @since 0.5
 */
public interface Span {

    /**
     * End the span.
     */
    CompletedSpan end();

    /**
     * End the span and capture a stack trace if the span duration exceeds the specified
     * {@code threshold}.
     * 
     * This method must be called directly from {@link OnReturn}, {@link OnThrow} or {@link OnAfter}
     * so it can roll back the correct number of frames in the stack trace that it captures, making
     * the stack trace point to the method execution picked out by the {@link Pointcut} instead of
     * pointing to the Informant code that performs the stack trace capture.
     * 
     * In case the trace has accumulated {@code maxSpans} spans and this is a dummy span and its
     * duration exceeds the specified threshold, then this dummy span is escalated into a real span.
     * A hard cap ({@code maxSpans * 2}) on the total number of (real) spans is applied when
     * escalating dummy spans to real spans.
     * 
     * @param threshold
     * @param unit
     */
    CompletedSpan endWithStackTrace(long threshold, TimeUnit unit);

    /**
     * End the span and add the specified {@code errorMessage} to the span.
     * 
     * If this is the root span, then the error flag on the trace is set. Traces can be filtered by
     * their error flag on the trace explorer page.
     * 
     * In case the trace has accumulated {@code maxSpans} spans and this is a dummy span, then this
     * dummy span is escalated into a real span. A hard cap ({@code maxSpans * 2}) on the total
     * number of (real) spans is applied when escalating dummy spans to real spans.
     * 
     * @param errorMessage
     */
    CompletedSpan endWithError(ErrorMessage errorMessage);

    /**
     * Returns the {@code MessageSupplier} that was supplied when the {@code Span} was created.
     * 
     * This can be useful (for example) to retrieve the {@code MessageSupplier} in @
     * {@link OnReturn} so that the return value can be added to the message produced by the
     * {@code MessageSupplier}.
     * 
     * This returns the {@code MessageSupplier} even if the trace has accumulated {@code maxSpans}
     * spans and this is a dummy span.
     * 
     * Under some error conditions this can return {@code null}.
     * 
     * @return the {@code MessageSupplier} that was supplied when the {@code Span} was created
     */
    @Nullable
    MessageSupplier getMessageSupplier();
}
