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

import java.util.Collection;
import java.util.List;
import java.util.concurrent.*;
import java.util.function.Function;

import com.datastax.oss.driver.api.core.DriverException;
import com.datastax.oss.driver.api.core.cql.AsyncResultSet;
import com.spotify.futures.CompletableFutures;
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
        return transformAsync(input, asyncExecutor,
                (results) -> {
                        if (!results.currentPage().iterator().hasNext()) {
                            return CompletableFuture.completedFuture(null);
                        }
                        return function.execute(results);
                    }
                );
    }

    public static CompletableFuture<?> rollupAsync(List<CompletableFuture<AsyncResultSet>> futures) {
        return CompletableFutures.allAsList(futures);
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
}
