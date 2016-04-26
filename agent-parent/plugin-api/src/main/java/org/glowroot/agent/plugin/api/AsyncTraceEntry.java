/*
 * Copyright 2016 the original author or authors.
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
package org.glowroot.agent.plugin.api;

/**
 * See {@link ThreadContext#startAsyncTraceEntry(MessageSupplier, TimerName)} for how to create and
 * use {@code TraceEntry} instances.
 */
public interface AsyncTraceEntry extends TraceEntry {

    void stopSyncTimer();

    // extend main thread timer without extending trace entry
    // only if this async trace entry is under currThreadContext
    // otherwise returns a no-op Timer
    Timer extendSyncTimer(ThreadContext currThreadContext);
}
