/*
 * Copyright 2016-2023 the original author or authors.
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

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.function.Supplier;

import com.datastax.oss.driver.api.core.DriverException;
import com.datastax.oss.driver.api.core.cql.AsyncResultSet;
import com.spotify.futures.CompletableFutures;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class MoreFutures {

    private static final Logger logger = LoggerFactory.getLogger(MoreFutures.class);

    private MoreFutures() {}

    public static void waitForAll(Collection<? extends CompletableFuture<?>> futures) {
        try {
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
        } catch (CompletionException ce) {
            throw unwrapDriverException(ce);
        }
    }

    public static CompletableFuture<?> rollupAsync(CompletableFuture<AsyncResultSet> input,
                                                   Executor asyncExecutor, DoRollup function) {
        return transformAsync(input.thenCompose(MoreFutures::skipEmptyLeadingPages), asyncExecutor,
                (results) -> {
                    if (!results.currentPage().iterator().hasNext()) {
                        return CompletableFuture.completedFuture(null);
                    }
                    return function.execute(results);
                });
    }

    public static CompletableFuture<?> rollupAsync(
            Collection<? extends CompletionStage<AsyncResultSet>> futures,
            Executor asyncExecutor, DoRollupList function) {
        return transformAsync(
                CompletableFutures.allAsList(new ArrayList<>(futures))
                        .thenCompose(MoreFutures::skipEmptyLeadingPagesIfNeeded),
                asyncExecutor,
                (list) -> {
                    for (AsyncResultSet results : list) {
                        if (results.currentPage().iterator().hasNext()) {
                            return function.execute(list);
                        }
                    }
                    return CompletableFuture.completedFuture(null);
                });
    }

    static CompletableFuture<AsyncResultSet> skipEmptyLeadingPages(
            AsyncResultSet results) {
        AsyncResultSet current = results;
        while (!current.currentPage().iterator().hasNext() && current.hasMorePages()) {
            CompletableFuture<AsyncResultSet> nextPage;
            try {
                nextPage = current.fetchNextPage().toCompletableFuture();
            } catch (Throwable t) {
                return CompletableFuture.failedFuture(t);
            }
            if (!nextPage.isDone()) {
                return nextPage.thenCompose(MoreFutures::skipEmptyLeadingPages);
            }
            if (nextPage.isCompletedExceptionally()) {
                return nextPage;
            }
            current = nextPage.join();
        }
        return CompletableFuture.completedFuture(current);
    }

    private static CompletableFuture<List<AsyncResultSet>> skipEmptyLeadingPagesIfNeeded(
            List<AsyncResultSet> list) {
        boolean anyHasMorePages = false;
        for (AsyncResultSet results : list) {
            if (results.currentPage().iterator().hasNext()) {
                return CompletableFuture.completedFuture(list);
            }
            if (results.hasMorePages()) {
                anyHasMorePages = true;
            }
        }
        if (!anyHasMorePages) {
            return CompletableFuture.completedFuture(list);
        }
        List<CompletableFuture<AsyncResultSet>> advanced = new ArrayList<>(list.size());
        for (AsyncResultSet results : list) {
            advanced.add(skipEmptyLeadingPages(results));
        }
        return CompletableFutures.allAsList(advanced);
    }

    public static <T> CompletableFuture<?> runWithBoundedConcurrency(Collection<T> items,
            int maxConcurrency, Function<T, CompletionStage<?>> task) {
        return runWithBoundedConcurrency(items, maxConcurrency, task, true);
    }

    public static <T> CompletableFuture<?> runAllWithBoundedConcurrency(Collection<T> items,
            int maxConcurrency, Function<T, CompletionStage<?>> task) {
        return runWithBoundedConcurrency(items, maxConcurrency, task, false);
    }

    private static <T> CompletableFuture<?> runWithBoundedConcurrency(Collection<T> items,
            int maxConcurrency, Function<T, CompletionStage<?>> task, boolean failFast) {
        if (maxConcurrency <= 0) {
            throw new IllegalArgumentException(
                    "maxConcurrency must be positive: " + maxConcurrency);
        }
        if (items.isEmpty()) {
            return CompletableFuture.completedFuture(null);
        }
        Queue<T> queue = new ConcurrentLinkedQueue<>(items);
        int workers = Math.min(maxConcurrency, items.size());
        AtomicReference<@Nullable Throwable> firstFailure = new AtomicReference<>();
        CompletableFuture<?>[] workerFutures = new CompletableFuture<?>[workers];
        for (int i = 0; i < workers; i++) {
            workerFutures[i] = drainQueue(queue, task, failFast, firstFailure);
        }
        return CompletableFuture.allOf(workerFutures).thenCompose(ignored -> {
            Throwable failure = firstFailure.get();
            if (failure != null) {
                return CompletableFuture.failedFuture(failure);
            }
            return CompletableFuture.completedFuture(null);
        });
    }

    private static <T> CompletableFuture<Void> drainQueue(Queue<T> queue,
            Function<T, CompletionStage<?>> task, boolean failFast,
            AtomicReference<@Nullable Throwable> firstFailure) {
        while (true) {
            if (failFast && firstFailure.get() != null) {
                return CompletableFuture.completedFuture(null);
            }
            T item = queue.poll();
            if (item == null) {
                return CompletableFuture.completedFuture(null);
            }
            CompletableFuture<?> future;
            try {
                future = task.apply(item).toCompletableFuture();
            } catch (Throwable t) {
                recordFailure(firstFailure, t);
                if (failFast) {
                    return CompletableFuture.completedFuture(null);
                }
                continue;
            }
            if (!future.isDone()) {
                return future.handle((ignored, t) -> {
                    if (t != null) {
                        recordFailure(firstFailure, t);
                    }
                    return null;
                }).thenCompose(ignored -> drainQueue(queue, task, failFast, firstFailure));
            }
            if (future.isCompletedExceptionally()) {
                recordFailure(firstFailure, extractFailure(future));
                if (failFast) {
                    return CompletableFuture.completedFuture(null);
                }
            }
        }
    }

    private static void recordFailure(AtomicReference<@Nullable Throwable> firstFailure,
            Throwable throwable) {
        firstFailure.compareAndSet(null, unwrapCompletionException(throwable));
    }

    private static Throwable extractFailure(CompletableFuture<?> future) {
        try {
            future.join();
        } catch (Throwable t) {
            return unwrapCompletionException(t);
        }
        return new IllegalStateException("Future completed exceptionally without an exception");
    }

    public static Throwable unwrapCompletionException(Throwable throwable) {
        Throwable cause = throwable;
        while (cause instanceof CompletionException && cause.getCause() != null) {
            cause = cause.getCause();
        }
        return cause;
    }

    private static <V, R> CompletableFuture<R> transformAsync(CompletableFuture<V> future,
                                                              Executor asyncExecutor, Function<V, CompletableFuture<R>> function) {
        return future.thenComposeAsync(input -> {
                return function.apply(input);
            },
            // calls to Session.readAsync() inside of the function could block due to the
            // per-thread concurrent limit, so this needs to be executed in its own thread, not
            // in the cassandra driver thread that completes the last future which will block
            // the cassandra driver thread pool
            asyncExecutor);
    }

    public static RuntimeException unwrapDriverException(CompletionException e) {
        Throwable cause = e.getCause();
        if (cause instanceof DriverException) {
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

    public static final class AsyncLimiter {

        private final Queue<Runnable> waiters = new ArrayDeque<>();
        private final ThreadLocal<Boolean> inReleaseLoop = new ThreadLocal<>();
        private int availablePermits;

        public AsyncLimiter(int maxConcurrency) {
            if (maxConcurrency <= 0) {
                throw new IllegalArgumentException(
                        "maxConcurrency must be positive: " + maxConcurrency);
            }
            this.availablePermits = maxConcurrency;
        }

        public <T> CompletableFuture<T> runAsync(Supplier<? extends CompletionStage<T>> task) {
            CompletableFuture<T> result = new CompletableFuture<>();
            boolean acquired = false;
            synchronized (this) {
                if (availablePermits > 0 && waiters.isEmpty()) {
                    availablePermits--;
                    acquired = true;
                } else {
                    waiters.add(() -> startTask(task, result));
                }
            }
            if (acquired) {
                startTask(task, result);
            }
            return result;
        }

        private <T> void startTask(Supplier<? extends CompletionStage<T>> task,
                CompletableFuture<T> result) {
            CompletionStage<T> stage;
            try {
                stage = task.get();
            } catch (Throwable t) {
                try {
                    result.completeExceptionally(t);
                } finally {
                    release();
                }
                return;
            }
            stage.whenComplete((value, t) -> {
                try {
                    if (t != null) {
                        result.completeExceptionally(unwrapCompletionException(t));
                    } else {
                        result.complete(value);
                    }
                } finally {
                    release();
                }
            });
        }

        private void release() {
            if (Boolean.TRUE.equals(inReleaseLoop.get())) {
                synchronized (this) {
                    availablePermits++;
                }
                return;
            }
            inReleaseLoop.set(Boolean.TRUE);
            try {
                boolean returnedPermit = false;
                while (true) {
                    Runnable next;
                    synchronized (this) {
                        if (!returnedPermit) {
                            availablePermits++;
                            returnedPermit = true;
                        }
                        if (availablePermits <= 0) {
                            return;
                        }
                        next = waiters.poll();
                        if (next == null) {
                            return;
                        }
                        availablePermits--;
                    }
                    next.run();
                }
            } finally {
                inReleaseLoop.remove();
            }
        }
    }
}
