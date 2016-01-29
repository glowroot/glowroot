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
package org.glowroot.agent.ui.sandbox;

import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

class AsyncWrapperCall {

    // re-using single executor to prevent spawning tons of threads
    private static final ExecutorService executor = Executors.newCachedThreadPool();

    private final int maxTimeMillis;
    private final int maxTraceEntryMessageLength;

    AsyncWrapperCall(int maxTimeMillis, int maxTraceEntryMessageLength) {
        this.maxTimeMillis = maxTimeMillis;
        this.maxTraceEntryMessageLength = maxTraceEntryMessageLength;
    }

    void execute() throws InterruptedException, ExecutionException {
        Future<Void> future1 = executor.submit(new Callable<Void>() {
            @Override
            public Void call() throws Exception {
                new ExpensiveCall(maxTimeMillis, maxTraceEntryMessageLength).execute();
                return null;
            }
        });
        Future<Void> future2 = executor.submit(new Callable<Void>() {
            @Override
            public Void call() throws Exception {
                new ExpensiveCall(maxTimeMillis, maxTraceEntryMessageLength).execute();
                return null;
            }
        });
        Future<Void> future3 = executor.submit(new Callable<Void>() {
            @Override
            public Void call() throws Exception {
                new ExpensiveCall(maxTimeMillis, maxTraceEntryMessageLength).execute();
                return null;
            }
        });
        future1.get();
        future2.get();
        future3.get();
    }
}
