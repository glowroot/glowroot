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
import io.informant.api.weaving.OnBefore;

/**
 * Convenience super interface of {@link Span} and {@link MetricTimer} to facilitate the common
 * pattern of starting either {@link Span} or {@link MetricTimer} in an @ {@link OnBefore} method,
 * depending on a configuration property.
 * 
 * This super interface allows the @ {@link OnAfter} method to be written to accept <tt>Endable</tt>
 * and call {@link #end()} on it, instead of having to accept {@link Object} and conditionally
 * casting it to {@link Span} or {@link MetricTimer}.
 * 
 * @author Trask Stalnaker
 * @since 0.5
 */
public interface Endable {

    /**
     * End this {@link Span} or {@link MetricTimer}.
     */
    void end();
}
