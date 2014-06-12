/*
 * Copyright 2012-2014 the original author or authors.
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
package org.glowroot.api.internal;

import java.util.Arrays;
import java.util.List;
import java.util.ListIterator;

import com.google.common.collect.ImmutableList;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * This class primarily exists because Exceptions are not thread safe.
 * 
 * @author Trask Stalnaker
 * @since 0.5
 */
public class ExceptionInfo {

    private final String display;
    // for inner cause exceptions, stackTrace only includes frames not in common with caused
    private final ImmutableList<StackTraceElement> stackTrace;
    // this is for printing '... 18 more' at end of cause instead of entire stack trace
    private final int framesInCommonWithCaused;
    @Nullable
    private final ExceptionInfo cause;

    public static ExceptionInfo from(Throwable t) {
        return from(t, null);
    }

    private static ExceptionInfo from(Throwable t,
            @Nullable List<StackTraceElement> causedStackTrace) {

        int framesInCommon = 0;
        ImmutableList<StackTraceElement> stackTrace = ImmutableList.copyOf(t.getStackTrace());
        if (causedStackTrace != null) {
            ListIterator<StackTraceElement> i = stackTrace.listIterator(stackTrace.size());
            ListIterator<StackTraceElement> j = causedStackTrace.listIterator(causedStackTrace
                    .size());
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
        Throwable cause = t.getCause();
        if (cause == null) {
            return new ExceptionInfo(t.toString(), stackTrace, null, framesInCommon);
        } else {
            // pass t's original stack trace to construct the nested cause
            // (not stackTraces, which now has common frames removed)
            return new ExceptionInfo(t.toString(), stackTrace, from(cause,
                    Arrays.asList(t.getStackTrace())), framesInCommon);
        }
    }

    private ExceptionInfo(String display, List<StackTraceElement> stackTrace,
            @Nullable ExceptionInfo cause, int framesInCommon) {
        this.display = display;
        this.stackTrace = ImmutableList.copyOf(stackTrace);
        this.cause = cause;
        this.framesInCommonWithCaused = framesInCommon;
    }

    public String getDisplay() {
        return display;
    }

    // don't return ImmutableList since tests have maven dependency on plugin-api, they will always
    // include unshaded plugin-api, while core may be shaded, in which case NoSuchMethodError will
    // occur since shaded core thinks this method returns shaded ImmutableList
    public List<StackTraceElement> getStackTrace() {
        return stackTrace;
    }

    public int getFramesInCommonWithCaused() {
        return framesInCommonWithCaused;
    }

    @Nullable
    public ExceptionInfo getCause() {
        return cause;
    }
}
