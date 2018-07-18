/*
 * Copyright 2015-2018 the original author or authors.
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

import com.google.common.base.MoreObjects;
import com.google.common.base.Strings;
import com.google.common.collect.ImmutableList;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.immutables.value.Value;

import org.glowroot.wire.api.model.Proto;
import org.glowroot.wire.api.model.Proto.Throwable;

@Value.Immutable
public abstract class ErrorMessage {

    private static final int TRANSACTION_THROWABLE_FRAME_LIMIT =
            Integer.getInteger("glowroot.transaction.throwable.frame.limit", 100000);

    @Value.Parameter
    public abstract String message();

    // cannot use Proto. /*@Nullable*/ Throwable
    // or org.glowroot.wire.api.model.Proto. /*@Nullable*/ Throwable here because Immutables needs
    // to be able to see the annotation
    @Value.Parameter
    public abstract @Nullable Throwable throwable();

    // accepts null values so callers don't have to check if passing it in from elsewhere
    public static ErrorMessage create(@Nullable String message,
            java.lang. /*@Nullable*/ Throwable t, AtomicInteger transactionThrowableFrameCount) {
        if (t == null) {
            return ImmutableErrorMessage.of(Strings.nullToEmpty(message), null);
        } else {
            return fromThrowable(message, t, transactionThrowableFrameCount);
        }
    }

    private static ErrorMessage fromThrowable(@Nullable String message, java.lang.Throwable t,
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

    private static Proto.Throwable buildThrowableInfo(java.lang.Throwable t,
            @Nullable List<StackTraceElement> causedStackTrace,
            AtomicInteger transactionThrowableFrameCount, int recursionDepth) {
        StackTraceElement[] stackTraceElements =
                MoreObjects.firstNonNull(t.getStackTrace(), new StackTraceElement[0]);
        StackTraceWithoutCommonFrames stackTraceWithoutCommonFrames =
                getStackTraceAndFramesInCommon(stackTraceElements, causedStackTrace,
                        transactionThrowableFrameCount);
        transactionThrowableFrameCount.addAndGet(stackTraceWithoutCommonFrames.stackTrace().size());
        Proto.Throwable.Builder builder = Proto.Throwable.newBuilder()
                .setClassName(t.getClass().getName());
        String message = t.getMessage();
        if (message != null) {
            builder.setMessage(message);
        }
        for (StackTraceElement element : stackTraceWithoutCommonFrames.stackTrace()) {
            builder.addStackTraceElement(toProto(element));
        }
        builder.setFramesInCommonWithEnclosing(
                stackTraceWithoutCommonFrames.framesInCommonWithEnclosing());
        java.lang.Throwable cause = t.getCause();
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
            // (not stackTraceWithoutCommonFrames, which now has common frames removed)
            builder.setCause(buildThrowableInfo(cause, Arrays.asList(stackTraceElements),
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

    private static StackTraceWithoutCommonFrames getStackTraceAndFramesInCommon(
            StackTraceElement[] stackTraceElements,
            @Nullable List<StackTraceElement> causedStackTrace,
            AtomicInteger transactionThrowableFrameCount) {
        if (transactionThrowableFrameCount.get() >= TRANSACTION_THROWABLE_FRAME_LIMIT) {
            return ImmutableStackTraceWithoutCommonFrames.builder()
                    .build();
        }
        if (causedStackTrace == null) {
            return ImmutableStackTraceWithoutCommonFrames.builder()
                    .addStackTrace(stackTraceElements)
                    .build();
        }
        ImmutableList<StackTraceElement> stackTrace = ImmutableList.copyOf(stackTraceElements);
        int framesInCommonWithEnclosing = 0;
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
        return ImmutableStackTraceWithoutCommonFrames.builder()
                .stackTrace(stackTrace)
                .framesInCommonWithEnclosing(framesInCommonWithEnclosing)
                .build();
    }

    @Value.Immutable
    abstract static class StackTraceWithoutCommonFrames {

        abstract List<StackTraceElement> stackTrace();

        @Value.Default
        public int framesInCommonWithEnclosing() {
            return 0;
        }
    }
}
