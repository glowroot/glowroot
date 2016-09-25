/*
 * Copyright 2015-2016 the original author or authors.
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
import java.util.concurrent.atomic.AtomicInteger;

import javax.annotation.Nullable;

import com.google.common.base.Strings;
import com.google.common.collect.ImmutableList;
import org.immutables.value.Value;

import org.glowroot.common.util.Styles;
import org.glowroot.wire.api.model.Proto;

@Value.Immutable
@Styles.AllParameters
public abstract class ErrorMessage {

    private static final int TRANSACTION_THROWABLE_FRAME_LIMIT =
            Integer.getInteger("glowroot.transaction.throwable.frame.limit", 100000);

    public abstract String message();
    public abstract @Nullable Proto.Throwable throwable();

    // accepts null values so callers don't have to check if passing it in from elsewhere
    public static ErrorMessage create(@Nullable String message, @Nullable Throwable t,
            AtomicInteger transactionThrowableFrameCount) {
        if (t == null) {
            return ImmutableErrorMessage.of(Strings.nullToEmpty(message), null);
        } else {
            return fromThrowable(message, t, transactionThrowableFrameCount);
        }
    }

    private static ErrorMessage fromThrowable(@Nullable String message, Throwable t,
            AtomicInteger transactionThrowableFrameCount) {
        String msg = Strings.nullToEmpty(message);
        if (msg.isEmpty()) {
            msg = Strings.nullToEmpty(t.getMessage());
        }
        if (msg.isEmpty()) {
            msg = Strings.nullToEmpty(t.getClass().getName());
        }
        return ImmutableErrorMessage.of(msg,
                buildThrowableInfo(t, null, transactionThrowableFrameCount, 0));
    }

    private static Proto.Throwable buildThrowableInfo(Throwable t,
            @Nullable List<StackTraceElement> causedStackTrace,
            AtomicInteger transactionThrowableFrameCount, int recursionDepth) {
        int framesInCommonWithEnclosing = 0;
        ImmutableList<StackTraceElement> stackTrace = ImmutableList.of();
        if (transactionThrowableFrameCount.get() < TRANSACTION_THROWABLE_FRAME_LIMIT) {
            stackTrace = ImmutableList.copyOf(t.getStackTrace());
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
                    stackTrace =
                            stackTrace.subList(0, stackTrace.size() - framesInCommonWithEnclosing);
                }
            }
            transactionThrowableFrameCount.addAndGet(stackTrace.size());
        }
        Proto.Throwable.Builder builder = Proto.Throwable.newBuilder()
                .setClassName(t.getClass().getName());
        String message = t.getMessage();
        if (message != null) {
            builder.setMessage(message);
        }
        for (StackTraceElement element : stackTrace) {
            builder.addStackTraceElement(toProto(element));
        }
        builder.setFramesInCommonWithEnclosing(framesInCommonWithEnclosing);
        Throwable cause = t.getCause();
        if (cause == null) {
            return builder.build();
        }
        if (transactionThrowableFrameCount.get() > TRANSACTION_THROWABLE_FRAME_LIMIT) {
            builder.setCause(Proto.Throwable.newBuilder()
                    .setMessage("Throwable frame capture limit exceeded")
                    .build());
        } else if (recursionDepth == 80) {
            // this was the 80th nested cause
            // protobuf limits to 100 total levels of nesting by default
            builder.setCause(Proto.Throwable.newBuilder()
                    .setMessage(
                            "The rest of the causal chain for this exception has been truncated")
                    .build());
        } else {
            // pass t's original stack trace to construct the nested cause
            // (not stackTraces, which now has common frames removed)
            builder.setCause(buildThrowableInfo(cause, Arrays.asList(t.getStackTrace()),
                    transactionThrowableFrameCount, recursionDepth + 1));
        }
        return builder.build();
    }

    public static Proto.StackTraceElement toProto(StackTraceElement ste) {
        Proto.StackTraceElement.Builder builder = Proto.StackTraceElement.newBuilder()
                .setClassName(ste.getClassName());
        String methodName = ste.getMethodName();
        if (methodName != null) {
            builder.setMethodName(methodName);
        }
        String fileName = ste.getFileName();
        if (fileName != null) {
            builder.setFileName(fileName);
        }
        return builder.setLineNumber(ste.getLineNumber())
                .build();
    }
}
