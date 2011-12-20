/**
 * Copyright 2011 the original author or authors.
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
 * Root spans also have the option of providing a username. This is often a very useful piece of
 * information to have in the trace, and it will be exposed as a filtering option in the future,
 * e.g. to persist all traces for a given username, which could be used for debugging an issue for a
 * specific user while not slowing down the system by persisting traces for all users.
 * 
 * The notes about {@link #getDescription()} and {@link #getContextMap()} being as lazy as possible
 * are also relevant for {@link #getUsername()}
 * 
 * @author Trask Stalnaker
 * @since 0.5
 */
public interface RootSpanDetail extends SpanDetail {
    String getUsername();
}
