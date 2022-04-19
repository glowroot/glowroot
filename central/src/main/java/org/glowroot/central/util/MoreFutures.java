/*
 * Copyright 2016-2019 the original author or authors.
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
package org.glowroot.central.util;

import java.util.Collection;
import java.util.List;
import java.util.concurrent.*;
import java.util.function.Function;

import com.datastax.oss.driver.api.core.DriverException;
import com.datastax.oss.driver.api.core.cql.AsyncResultSet;
import com.google.common.util.concurrent.FutureCallback;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.MoreExecutors;
import com.google.common.util.concurrent.SettableFuture;
import com.spotify.futures.CompletableFutures;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class MoreFutures {

    private static final Logger logger = LoggerFactory.getLogger(MoreFutures.class);

    private MoreFutures() {}

    public static void waitForAll(Collection<? extends Future<?>> futures) throws Exception {
        Exception exception = null;
        for (Future<?> future : futures) {
            if (exception != null) {
                future.cancel(true);
            } else {
                try {
                    future.get();
                } catch (InterruptedException | ExecutionException e) {
                    logger.debug(e.getMessage(), e);
                    exception = e;
                }
            }
        }
        if (exception == null) {
            return;
        }
        if (exception instanceof ExecutionException) {
            throw unwrapDriverException((ExecutionException) exception);
        }
        throw exception;
    }

    public static <V> ListenableFuture<V> onFailure(ListenableFuture<V> future,
            Runnable onFailure) {
        SettableFuture<V> outerFuture = SettableFuture.create();
        Futures.addCallback(future, new FutureCallback<V>() {
            @Override
            public void onSuccess(V result) {
                outerFuture.set(result);
            }
            @Override
            public void onFailure(Throwable t) {
                logger.debug(t.getMessage(), t);
                onFailure.run();
                outerFuture.setException(t);
            }
        }, MoreExecutors.directExecutor());
        return outerFuture;
    }

    public static CompletableFuture<?> rollupAsync(CompletableFuture<AsyncResultSet> input,
                                                   Executor asyncExecutor, DoRollup function) {
        return transformAsync(input, asyncExecutor,
                (results) -> {
                        if (!results.currentPage().iterator().hasNext()) {
                            return CompletableFuture.completedFuture(null);
                        }
                        return function.execute(results);
                    }
                );
    }

    public static CompletableFuture<?> rollupAsync(List<CompletableFuture<AsyncResultSet>> futures,
                                                   Executor asyncExecutor, DoRollupList function) {
        return transformAsync(CompletableFutures.allAsList(futures), asyncExecutor, function::execute);
    }

    private static <V, R> CompletableFuture<R> transformAsync(CompletableFuture<V> future,
                                                              Executor asyncExecutor, Function<V, CompletableFuture<R>> function) {
        boolean inRollupThread = Session.isInRollupThread();
        return future.thenComposeAsync(input -> {
                boolean priorInRollupThread = Session.isInRollupThread();
                Session.setInRollupThread(inRollupThread);
                try {
                    return function.apply(input);
                } finally {
                    Session.setInRollupThread(priorInRollupThread);
                }
            },
            // calls to Session.readAsync() inside of the function could block due to the
            // per-thread concurrent limit, so this needs to be executed in its own thread, not
            // in the cassandra driver thread that completes the last future which will block
            // the cassandra driver thread pool
            asyncExecutor);
    }

    public static Exception unwrapDriverException(ExecutionException e) {
        Throwable cause = e.getCause();
        if (cause instanceof DriverException) {
            // see com.datastax.driver.core.DriverThrowables.propagateCause()
            return ((DriverException) cause).copy();
        } else {
            return e;
        }
    }

    public interface DoRollup {
        CompletableFuture<?> execute(AsyncResultSet results);
    }

    public interface DoRollupList {
        CompletableFuture<?> execute(List<AsyncResultSet> results);
    }
}
