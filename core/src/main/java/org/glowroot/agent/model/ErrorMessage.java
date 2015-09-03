/*
 * Copyright 2015 the original author or authors.
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
package org.glowroot.agent.model;

import java.util.Arrays;
import java.util.List;
import java.util.ListIterator;

import javax.annotation.Nullable;

import com.google.common.base.Strings;
import com.google.common.collect.ImmutableList;
import org.immutables.value.Value;

import org.glowroot.collector.spi.model.TraceOuterClass.Trace;
import org.glowroot.common.util.Styles;

@Value.Immutable
@Styles.AllParameters
public abstract class ErrorMessage {

    public abstract String message();
    public abstract @Nullable Trace.Throwable throwable();

    public static ErrorMessage from(Throwable t) {
        return from(null, t);
    }

    // accepts null message so callers don't have to check if passing it in from elsewhere
    public static ErrorMessage from(@Nullable String message) {
        return from(message, null);
    }

    // accepts null values so callers don't have to check if passing it in from elsewhere
    public static ErrorMessage from(@Nullable String message, @Nullable Throwable t) {
        String msg = Strings.nullToEmpty(message);
        if (t == null) {
            return ImmutableErrorMessage.of(msg, null);
        }
        if (msg.isEmpty()) {
            msg = Strings.nullToEmpty(t.getMessage());
        }
        return ImmutableErrorMessage.of(msg, buildThrowableInfo(t, null));
    }

    private static Trace.Throwable buildThrowableInfo(Throwable t,
            @Nullable List<StackTraceElement> causedStackTrace) {
        int framesInCommonWithEnclosing = 0;
        ImmutableList<StackTraceElement> stackTrace = ImmutableList.copyOf(t.getStackTrace());
        if (causedStackTrace != null) {
            ListIterator<StackTraceElement> i = stackTrace.listIterator(stackTrace.size());
            ListIterator<StackTraceElement> j =
                    causedStackTrace.listIterator(causedStackTrace.size());
            while (i.hasPrevious() && j.hasPrevious()) {
                StackTraceElement element = i.previous();
                StackTraceElement causedElement = j.previous();
                if (!element.equals(causedElement)) {
                    break;
                }
                framesInCommonWithEnclosing++;
            }
            if (framesInCommonWithEnclosing > 0) {
                // strip off common frames
                stackTrace = stackTrace.subList(0, stackTrace.size() - framesInCommonWithEnclosing);
            }
        }
        Trace.Throwable.Builder builder = Trace.Throwable.newBuilder()
                .setDisplay(t.toString());
        for (StackTraceElement element : stackTrace) {
            builder.addElement(toProtobuf(element));
        }
        builder.setFramesInCommonWithEnclosing(framesInCommonWithEnclosing);
        Throwable cause = t.getCause();
        if (cause != null) {
            // pass t's original stack trace to construct the nested cause
            // (not stackTraces, which now has common frames removed)
            builder.setCause(buildThrowableInfo(cause, Arrays.asList(t.getStackTrace())));
        }
        return builder.build();
    }

    private static Trace.StackTraceElement toProtobuf(StackTraceElement ste) {
        return Trace.StackTraceElement.newBuilder()
                .setClassName(ste.getClassName())
                .setMethodName(Strings.nullToEmpty(ste.getMethodName()))
                .setFileName(Strings.nullToEmpty(ste.getFileName()))
                .setLineNumber(ste.getLineNumber())
                .build();
    }
}
