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
package org.glowroot.central.util;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import com.datastax.oss.driver.api.core.cql.AsyncResultSet;
import com.datastax.oss.driver.api.core.cql.Row;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class MoreFuturesTest {

    private static final Executor DIRECT_EXECUTOR = Runnable::run;

    @Test
    public void rollupAsyncSkipsCallbackWhenSingleResultSetIsEmpty() throws Exception {
        AsyncResultSet emptyResultSet = emptyResultSet();
        AtomicBoolean invoked = new AtomicBoolean(false);

        Object result = MoreFutures.rollupAsync(
                        CompletableFuture.completedFuture(emptyResultSet),
                        DIRECT_EXECUTOR,
                        rows -> {
                            invoked.set(true);
                            return CompletableFuture.completedFuture("executed");
                        })
                .get(2, TimeUnit.SECONDS);

        assertThat(invoked.get()).isFalse();
        assertThat(result).isNull();
    }

    @Test
    public void rollupAsyncInvokesCallbackWhenSingleResultSetHasRows() throws Exception {
        AsyncResultSet nonEmptyResultSet = nonEmptyResultSet();
        CompletableFuture<Void> callbackFuture = new CompletableFuture<>();
        AtomicReference<AsyncResultSet> received = new AtomicReference<>();

        CompletableFuture<?> resultFuture = MoreFutures.rollupAsync(
                CompletableFuture.completedFuture(nonEmptyResultSet),
                DIRECT_EXECUTOR,
                rows -> {
                    received.set(rows);
                    return callbackFuture;
                });

        assertThat(received.get()).isSameAs(nonEmptyResultSet);
        assertThat(resultFuture.isDone()).isFalse();
        callbackFuture.complete(null);
        assertThat(resultFuture.get(2, TimeUnit.SECONDS)).isNull();
    }

    @Test
    public void rollupAsyncListSkipsCallbackWhenAllResultSetsAreEmpty() throws Exception {
        List<CompletionStage<AsyncResultSet>> futures = List.of(
                CompletableFuture.completedFuture(emptyResultSet()),
                CompletableFuture.completedFuture(emptyResultSet()));
        AtomicBoolean invoked = new AtomicBoolean(false);

        Object result = MoreFutures.rollupAsync(
                        futures,
                        DIRECT_EXECUTOR,
                        rows -> {
                            invoked.set(true);
                            return CompletableFuture.completedFuture("executed");
                        })
                .get(2, TimeUnit.SECONDS);

        assertThat(invoked.get()).isFalse();
        assertThat(result).isNull();
    }

    @Test
    public void rollupAsyncListInvokesCallbackWithAllResultsWhenAnyResultSetHasRows()
            throws Exception {
        AsyncResultSet empty = emptyResultSet();
        AsyncResultSet nonEmpty = nonEmptyResultSet();
        List<CompletionStage<AsyncResultSet>> futures = List.of(
                CompletableFuture.completedFuture(empty),
                CompletableFuture.completedFuture(nonEmpty));
        CompletableFuture<Void> callbackFuture = new CompletableFuture<>();
        AtomicReference<List<AsyncResultSet>> received = new AtomicReference<>();

        CompletableFuture<?> resultFuture = MoreFutures.rollupAsync(
                futures,
                DIRECT_EXECUTOR,
                rows -> {
                    received.set(rows);
                    return callbackFuture;
                });

        assertThat(received.get()).containsExactly(empty, nonEmpty);
        assertThat(resultFuture.isDone()).isFalse();
        callbackFuture.complete(null);
        assertThat(resultFuture.get(2, TimeUnit.SECONDS)).isNull();
    }

    @Test
    public void runWithBoundedConcurrencyRespectsLimitAndProcessesAllItems() throws Exception {
        List<Integer> items = List.of(0, 1, 2, 3, 4, 5, 6, 7, 8, 9);
        List<CompletableFuture<Void>> pending = new ArrayList<>();
        List<Integer> started = new ArrayList<>();
        AtomicInteger active = new AtomicInteger();
        AtomicInteger maxActive = new AtomicInteger();

        CompletableFuture<?> allDone = MoreFutures.runWithBoundedConcurrency(items, 3, item -> {
            int current = active.incrementAndGet();
            maxActive.accumulateAndGet(current, Math::max);
            started.add(item);
            CompletableFuture<Void> future = new CompletableFuture<>();
            pending.add(future);
            return future;
        });

        assertThat(started).hasSize(3);
        assertThat(active.get()).isEqualTo(3);
        assertThat(allDone.isDone()).isFalse();

        for (int i = 0; i < items.size(); i++) {
            active.decrementAndGet();
            pending.get(i).complete(null);
        }

        allDone.get(2, TimeUnit.SECONDS);
        assertThat(started).containsExactlyElementsOf(items);
        assertThat(maxActive.get()).isEqualTo(3);
        assertThat(active.get()).isEqualTo(0);
    }

    @Test
    public void runWithBoundedConcurrencyHandlesSynchronousCompletionAndEmptyInput()
            throws Exception {
        assertThat(MoreFutures.runWithBoundedConcurrency(Collections.emptyList(), 3,
                item -> CompletableFuture.completedFuture(null)).get(2, TimeUnit.SECONDS))
                .isNull();

        List<Integer> largeItems = new ArrayList<>(5000);
        for (int i = 0; i < 5000; i++) {
            largeItems.add(i);
        }
        AtomicInteger processed = new AtomicInteger();

        MoreFutures.runWithBoundedConcurrency(largeItems, 4, item -> {
            processed.incrementAndGet();
            return CompletableFuture.completedFuture(null);
        }).get(2, TimeUnit.SECONDS);

        assertThat(processed.get()).isEqualTo(5000);
    }

    @Test
    public void runWithBoundedConcurrencyPropagatesException() {
        RuntimeException failure = new RuntimeException("boom");
        CompletableFuture<?> result = MoreFutures.runWithBoundedConcurrency(
                List.of(1, 2, 3), 2, item -> {
                    if (item == 2) {
                        return CompletableFuture.failedFuture(failure);
                    }
                    return CompletableFuture.completedFuture(null);
                });

        assertThatThrownBy(() -> result.get(2, TimeUnit.SECONDS))
                .isInstanceOf(ExecutionException.class)
                .hasCause(failure);
    }

    private static AsyncResultSet emptyResultSet() {
        AsyncResultSet resultSet = mock(AsyncResultSet.class);
        when(resultSet.currentPage()).thenReturn(Collections.emptyList());
        return resultSet;
    }

    private static AsyncResultSet nonEmptyResultSet() {
        AsyncResultSet resultSet = mock(AsyncResultSet.class);
        Row row = mock(Row.class);
        when(resultSet.currentPage()).thenReturn(List.of(row));
        return resultSet;
    }
}
