/*
 * Copyright 2016-2017 the original author or authors.
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

import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;

import com.google.common.util.concurrent.FutureCallback;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.MoreExecutors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class MoreFutures {

    private static final Logger logger = LoggerFactory.getLogger(MoreFutures.class);

    private MoreFutures() {}

    // not using guava Futures.allAsList().get() because it logs every error
    public static void waitForAll(List<? extends Future<?>> futures) throws Exception {
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
        if (exception != null) {
            throw exception;
        }
    }

    public static <V> CompletableFuture<V> onFailure(ListenableFuture<V> future,
            Runnable onFailure) {
        CompletableFuture<V> chainedFuture = new CompletableFuture<>();
        Futures.addCallback(future, new FutureCallback<V>() {
            @Override
            public void onSuccess(V result) {
                chainedFuture.complete(result);
            }
            @Override
            public void onFailure(Throwable t) {
                logger.debug(t.getMessage(), t);
                onFailure.run();
                chainedFuture.completeExceptionally(t);
            }
        }, MoreExecutors.directExecutor());
        return chainedFuture;
    }

    public static <V> CompletableFuture<V> onFailure(CompletableFuture<V> future,
            Runnable onFailure) {
        return future.whenComplete((result, t) -> {
            if (t != null) {
                onFailure.run();
            }
        });
    }

    public static <V> CompletableFuture<V> toCompletableFuture(ListenableFuture<V> future) {
        CompletableFuture<V> chainedFuture = new CompletableFuture<>();
        Futures.addCallback(future, new FutureCallback<V>() {
            @Override
            public void onSuccess(V result) {
                chainedFuture.complete(result);
            }
            @Override
            public void onFailure(Throwable t) {
                logger.debug(t.getMessage(), t);
                chainedFuture.completeExceptionally(t);
            }
        }, MoreExecutors.directExecutor());
        return chainedFuture;
    }

    public static <V> CompletableFuture<V> submitAsync(Callable<V> callable,
            ExecutorService executor) {
        CompletableFuture<V> future = new CompletableFuture<>();
        executor.execute(() -> {
            try {
                future.complete(callable.call());
            } catch (Throwable t) {
                future.completeExceptionally(t);
            }
        });
        return future;
    }
}
