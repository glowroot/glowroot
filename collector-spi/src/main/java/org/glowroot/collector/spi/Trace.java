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

public interface Trace {

    String id();

    boolean partial();

    boolean slow();

    boolean error();

    long startTime();

    long captureTime();

    long duration(); // nanoseconds

    String transactionType();

    String transactionName();

    String headline();

    @Nullable
    String user();

    Map<String, ? extends Collection<String>> customAttributes();

    Map<String, ? extends /*@Nullable*/Object> customDetail();

    @Nullable
    String errorMessage();

    @Nullable
    ThrowableInfo errorThrowable();

    TraceTimerNode rootTimer();

    long threadCpuTime(); // nanoseconds, -1 means N/A

    long threadBlockedTime(); // nanoseconds, -1 means N/A

    long threadWaitedTime(); // nanoseconds, -1 means N/A

    long threadAllocatedBytes(); // -1 means N/A

    Map<String, ? extends GarbageCollectionActivity> gcActivity();

    Collection<? extends TraceEntry> entries();

    @Nullable
    ProfileNode syntheticRootProfileNode();
}
