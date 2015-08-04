/*
 * Copyright 2012-2015 the original author or authors.
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
package org.glowroot.plugin.api.internal;

import java.util.Arrays;
import java.util.List;
import java.util.ListIterator;

import javax.annotation.Nullable;

import com.google.common.collect.ImmutableList;
import org.immutables.value.Value;

// this class primarily exists because Throwables are not thread safe
@Value.Immutable
public abstract class ThrowableInfo {

    public abstract String display();
    // for inner cause throwable, stackTrace only includes frames not in common with caused
    public abstract List<StackTraceElement> stackTrace();
    // this is for printing '... 18 more' at end of cause instead of entire stack trace
    public abstract int framesInCommonWithCaused();
    public abstract @Nullable ThrowableInfo cause();

    public static ThrowableInfo from(Throwable t) {
        return from(t, null);
    }

    private static ThrowableInfo from(Throwable t,
            @Nullable List<StackTraceElement> causedStackTrace) {
        int framesInCommon = 0;
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
                framesInCommon++;
            }
            if (framesInCommon > 0) {
                // strip off common frames
                stackTrace = stackTrace.subList(0, stackTrace.size() - framesInCommon);
            }
        }
        ImmutableThrowableInfo.Builder builder = ImmutableThrowableInfo.builder()
                .display(t.toString())
                .addAllStackTrace(stackTrace)
                .framesInCommonWithCaused(framesInCommon);
        Throwable cause = t.getCause();
        if (cause != null) {
            // pass t's original stack trace to construct the nested cause
            // (not stackTraces, which now has common frames removed)
            builder.cause(from(cause, Arrays.asList(t.getStackTrace())));
        }
        return builder.build();
    }
}
