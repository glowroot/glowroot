/*
 * Copyright 2015 the original author or authors.
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
package org.glowroot.collector.spi;

import java.util.Collection;
import java.util.Map;

import javax.annotation.Nullable;

public interface TraceEntry {

    long offset();

    long duration();

    boolean active();

    int nestingLevel();

    // message text is null for trace entries added using addEntryEntry()
    @Nullable
    String messageText();

    // messageText null implies messageDetail is empty
    // (if messageDetail is not null, messageText will at least be empty string)
    Map<String, ? extends /*@Nullable*/Object> messageDetail();

    @Nullable
    String errorMessage();

    // errorMessage null implies errorThrowable is null
    // (if errorThrowable is not null, errorMessage will at least be empty string)
    @Nullable
    ThrowableInfo errorThrowable();

    // reasoning for @Nullable here compared to empty collection is that a stack trace is really a
    // single thing as opposed to a collection of things
    @Nullable
    Collection<StackTraceElement> stackTrace();
}
