/**
 * Copyright 2011-2013 the original author or authors.
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
package io.informant.core;

import java.util.Collection;

/**
 * Interface for storing traces.
 * 
 * @author Trask Stalnaker
 * @since 0.5
 */
public interface TraceSink {

    void onCompletedTrace(Trace trace);

    // implementations must assume another thread is concurrently still writing to trace
    void onStuckTrace(Trace trace);

    // this is used to cover the gap between active traces and stored traces (e.g. so that traces
    // don't go missing from the ui for the short time between completion and storage)
    Collection<Trace> getPendingCompleteTraces();
}
