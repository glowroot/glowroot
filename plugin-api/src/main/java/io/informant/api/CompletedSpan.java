/**
 * Copyright 2013 the original author or authors.
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

import io.informant.api.weaving.OnAfter;
import io.informant.api.weaving.OnReturn;
import io.informant.api.weaving.OnThrow;
import io.informant.api.weaving.Pointcut;

/**
 * @author Trask Stalnaker
 * @since 0.5
 */
public interface CompletedSpan {

    /**
     * Captures a stack trace and attaches it to the span.
     * 
     * This method must be called directly from {@link OnReturn}, {@link OnThrow} or {@link OnAfter}
     * so it can roll back the correct number of frames in the stack trace that it captures, making
     * the stack trace point to the method execution picked out by the {@link Pointcut} instead of
     * pointing to the Informant code that performs the stack trace capture.
     */
    void captureSpanStackTrace();
}
