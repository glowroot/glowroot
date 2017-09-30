/*
 * Copyright 2015-2017 the original author or authors.
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
package org.glowroot.agent.it.harness.impl;

import java.util.List;
import java.util.concurrent.TimeUnit;

import javax.annotation.Nullable;

import com.google.common.base.Joiner;
import com.google.common.base.Stopwatch;
import com.google.common.collect.Lists;
import org.immutables.value.Value;

import org.glowroot.wire.api.model.CollectorServiceOuterClass.LogEvent;
import org.glowroot.wire.api.model.TraceOuterClass.Trace;

import static java.util.concurrent.TimeUnit.SECONDS;

class TraceCollector {

    private volatile @Nullable Trace trace;

    private final List<ExpectedLogMessage> expectedMessages = Lists.newCopyOnWriteArrayList();
    private final List<LogEvent> unexpectedMessages = Lists.newCopyOnWriteArrayList();

    Trace getCompletedTrace(int timeout, TimeUnit unit) throws InterruptedException {
        Stopwatch stopwatch = Stopwatch.createStarted();
        while (stopwatch.elapsed(unit) < timeout) {
            Trace trace = this.trace;
            if (trace != null && !trace.getHeader().getPartial()) {
                return trace;
            }
            Thread.sleep(10);
        }
        throw new IllegalStateException("No trace was collected");
    }

    Trace getCompletedTrace(String transactionType, int timeout, TimeUnit unit)
            throws InterruptedException {
        Stopwatch stopwatch = Stopwatch.createStarted();
        while (stopwatch.elapsed(unit) < timeout) {
            Trace trace = this.trace;
            if (trace != null && !trace.getHeader().getPartial()
                    && trace.getHeader().getTransactionType().equals(transactionType)) {
                return trace;
            }
            Thread.sleep(10);
        }
        throw new IllegalStateException(
                "No trace was collected for transaction type: " + transactionType);
    }

    Trace getPartialTrace(int timeout, TimeUnit unit) throws InterruptedException {
        Stopwatch stopwatch = Stopwatch.createStarted();
        while (stopwatch.elapsed(unit) < timeout) {
            Trace trace = this.trace;
            if (trace != null) {
                if (!trace.getHeader().getPartial()) {
                    throw new IllegalStateException("Trace was collected but is not partial");
                }
                return trace;
            }
            Thread.sleep(10);
        }
        throw new IllegalStateException("No trace was collected");
    }

    boolean hasTrace() {
        return trace != null;
    }

    void clearTrace() {
        trace = null;
    }

    void addExpectedLogMessage(String loggerName, String partialMessage) {
        expectedMessages.add(ImmutableExpectedLogMessage.of(loggerName, partialMessage));
    }

    public void checkAndResetLogMessages() throws InterruptedException {
        Stopwatch stopwatch = Stopwatch.createStarted();
        while (stopwatch.elapsed(SECONDS) < 10 && !expectedMessages.isEmpty()
                && unexpectedMessages.isEmpty()) {
            Thread.sleep(10);
        }
        try {
            if (!unexpectedMessages.isEmpty()) {
                throw new AssertionError("Unexpected messages were logged:\n\n"
                        + Joiner.on("\n").join(unexpectedMessages));
            }
            if (!expectedMessages.isEmpty()) {
                throw new AssertionError("One or more expected messages were not logged");
            }
        } finally {
            expectedMessages.clear();
            unexpectedMessages.clear();
        }
    }

    public void collectTrace(Trace trace) {
        Trace currTrace = this.trace;
        if (currTrace == null
                || currTrace.getHeader().getCaptureTime() <= trace.getHeader().getCaptureTime()) {
            this.trace = trace;
        }
    }

    public void log(LogEvent logEvent) {
        if (isExpected(logEvent)) {
            return;
        }
        if (logEvent.getLoggerName().equals("org.apache.catalina.loader.WebappClassLoaderBase")
                && logEvent.getMessage().matches(
                        "The web application \\[.*\\] appears to have started a thread named"
                                + " \\[.*\\] but has failed to stop it\\. This is very likely to"
                                + " create a memory leak\\.")) {
            return;
        }
        if (logEvent.getLevel().ordinal() >= LogEvent.Level.WARN_VALUE) {
            unexpectedMessages.add(logEvent);
        }
    }

    private boolean isExpected(LogEvent logEvent) {
        if (expectedMessages.isEmpty()) {
            return false;
        }
        ExpectedLogMessage expectedMessage = expectedMessages.get(0);
        if (expectedMessage.loggerName().equals(logEvent.getLoggerName())
                && logEvent.getMessage().contains(expectedMessage.partialMessage())) {
            expectedMessages.remove(0);
            return true;
        }
        return false;
    }

    @Value.Immutable
    @Value.Style(allParameters = true)
    interface ExpectedLogMessage {
        String loggerName();
        String partialMessage();
    }
}
