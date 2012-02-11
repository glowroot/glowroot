/**
 * Copyright 2011-2012 the original author or authors.
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

/**
 * This can be used to store details associated with a Span (e.g. the sql statement and row count
 * for a jdbc call). Implementations should be as lazy as possible since {@link #getDescription()}
 * and {@link #getContextMap()} are only called for traces which end up being persisted. For
 * example, SpanDetails should store relevant data and then use it to build the description and the
 * context map only on demand.
 * 
 * {@link #getDescription()} and {@link #getContextMap()} can also be called if someone requests to
 * view in-flight traces, and therefore possibly a second time if the trace ends up being persisted.
 * Because of the relevant infrequency of viewing in-flight traces, it should generally be fine to
 * just regenerate the data each time one of these methods is called.
 * 
 * @author Trask Stalnaker
 * @since 0.5
 */
public interface SpanDetail {

    CharSequence getDescription();

    SpanContextMap getContextMap();
}
