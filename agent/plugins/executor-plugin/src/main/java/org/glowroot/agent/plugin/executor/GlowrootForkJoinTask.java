/*
 * Copyright 2026 the original author or authors.
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
package org.glowroot.agent.plugin.executor;

import java.util.concurrent.ForkJoinTask;

import org.glowroot.agent.plugin.api.AuxThreadContext;
import org.glowroot.agent.plugin.api.TraceEntry;

// wraps a ForkJoinTask that was loaded before the class file transformer was added to jvm
// (e.g. AdaptedCallable, AdaptedRunnable in Java 25+), which prevents exec() from being
// instrumented; by wrapping in this class, exec() can be instrumented instead
//
// NOTE: does NOT implement RunnableEtcMixin directly because the class file transformer will
// inject the mixin into this class (since it extends ForkJoinTask which is a @Mixin target),
// and implementing the interface directly would cause a ClassFormatError due to duplicate
// field "glowroot$auxContext"; tracing is managed directly in exec() instead
@SuppressWarnings("serial")
class GlowrootForkJoinTask<V> extends ForkJoinTask<V> {

    private final ForkJoinTask<V> delegate;
    private final AuxThreadContext auxContext;

    GlowrootForkJoinTask(ForkJoinTask<V> delegate, AuxThreadContext auxContext) {
        this.delegate = delegate;
        this.auxContext = auxContext;
    }

    @Override
    public V getRawResult() {
        return delegate.getRawResult();
    }

    @Override
    protected void setRawResult(V value) {
        // result is stored in the delegate (set by the delegate's own exec())
    }

    @Override
    protected boolean exec() {
        TraceEntry traceEntry = auxContext.start();
        try {
            // call via Runnable interface to avoid protected method access restriction;
            // AdaptedCallable and AdaptedRunnable (and AdaptedInterruptibleCallable/Runnable) all
            // implement RunnableFuture which extends Runnable, so this cast is safe
            ((Runnable) delegate).run();
        } catch (Throwable t) {
            traceEntry.endWithError(t);
            throw rethrow(t);
        }
        traceEntry.end();
        return true;
    }

    private static RuntimeException rethrow(Throwable t) {
        GlowrootForkJoinTask.<RuntimeException>throwsUnchecked(t);
        throw new AssertionError();
    }

    @SuppressWarnings("unchecked")
    private static <T extends Throwable> void throwsUnchecked(Throwable t) throws T {
        throw (T) t;
    }
}
