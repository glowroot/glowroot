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

import javax.annotation.Nullable;
import javax.annotation.concurrent.Immutable;

/**
 * This class primarily exists because Exceptions are not thread safe.
 * 
 * @author Trask Stalnaker
 * @since 0.5
 */
@Immutable
public class CapturedException {

    private final String display;
    // for inner cause exceptions, stackTrace only includes frames not in common with caused
    private final StackTraceElement[] stackTrace;
    // this is for printing '... 18 more' at end of cause instead of entire stack trace
    private final int framesInCommonWithCaused;
    @Nullable
    private final CapturedException cause;

    public static CapturedException from(Throwable t) {
        return from(t, null);
    }

    private static CapturedException from(Throwable t,
            @Nullable StackTraceElement[] causedStackTrace) {

        int framesInCommon = 0;
        StackTraceElement[] stackTrace = t.getStackTrace();
        if (causedStackTrace != null) {
            while (framesInCommon < stackTrace.length && framesInCommon < causedStackTrace.length) {
                StackTraceElement element = stackTrace[stackTrace.length - 1 - framesInCommon];
                StackTraceElement causedElement = causedStackTrace[causedStackTrace.length - 1
                        - framesInCommon];
                if (!element.equals(causedElement)) {
                    break;
                }
                framesInCommon++;
            }
            if (framesInCommon > 0) {
                // strip off common frames
                StackTraceElement[] stackTraceWithoutCommon =
                        new StackTraceElement[stackTrace.length - framesInCommon];
                System.arraycopy(stackTrace, 0, stackTraceWithoutCommon, 0,
                        stackTraceWithoutCommon.length);
                stackTrace = stackTraceWithoutCommon;
            }
        }
        Throwable cause = t.getCause();
        if (cause == null) {
            return new CapturedException(t.toString(), stackTrace, null, framesInCommon);
        } else {
            // pass t's original stack trace to construct the nested cause
            // (not stackTraces, which now has common frames removed)
            return new CapturedException(t.toString(), stackTrace, from(cause, t.getStackTrace()),
                    framesInCommon);
        }
    }
    private CapturedException(String display, StackTraceElement[] stackTrace,
            CapturedException cause, int framesInCommon) {

        this.display = display;
        this.stackTrace = stackTrace;
        this.cause = cause;
        this.framesInCommonWithCaused = framesInCommon;
    }

    public String getDisplay() {
        return display;
    }

    public StackTraceElement[] getStackTrace() {
        return stackTrace;
    }

    public int getFramesInCommonWithCaused() {
        return framesInCommonWithCaused;
    }

    @Nullable
    public CapturedException getCause() {
        return cause;
    }
}
