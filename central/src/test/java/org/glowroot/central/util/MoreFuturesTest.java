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
import java.util.function.Function;

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
    public void rollupAsyncAdvancesPastEmptyLeadingPagesToInvokeCallback() throws Exception {
        AsyncResultSet page3 = nonEmptyResultSet();
        AsyncResultSet page2 = emptyResultSetWithNextPage(CompletableFuture.completedFuture(page3));
        CompletableFuture<AsyncResultSet> page2Future = new CompletableFuture<>();
        AsyncResultSet page1 = emptyResultSetWithNextPage(page2Future);

        AtomicBoolean ranOnAsyncExecutor = new AtomicBoolean(false);
        Executor trackingExecutor = command -> {
            ranOnAsyncExecutor.set(true);
            command.run();
        };
        AtomicReference<AsyncResultSet> received = new AtomicReference<>();

        CompletableFuture<?> resultFuture = MoreFutures.rollupAsync(
                CompletableFuture.completedFuture(page1),
                trackingExecutor,
                rows -> {
                    received.set(rows);
                    return CompletableFuture.completedFuture(null);
                });

        assertThat(resultFuture.isDone()).isFalse();
        assertThat(received.get()).isNull();

        page2Future.complete(page2);

        assertThat(resultFuture.get(2, TimeUnit.SECONDS)).isNull();
        assertThat(ranOnAsyncExecutor.get()).isTrue();
        assertThat(received.get()).isSameAs(page3);
    }

    @Test
    public void rollupAsyncSkipsCallbackWhenAllPagesAreEmpty() throws Exception {
        AsyncResultSet page2 = emptyResultSet();
        AsyncResultSet page1 = emptyResultSetWithNextPage(CompletableFuture.completedFuture(page2));
        AtomicBoolean invoked = new AtomicBoolean(false);

        Object result = MoreFutures.rollupAsync(
                        CompletableFuture.completedFuture(page1),
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
    public void rollupAsyncPropagatesFetchNextPageFailure() {
        RuntimeException failure = new RuntimeException("page fetch failed");
        AsyncResultSet page1 = emptyResultSetWithNextPage(CompletableFuture.failedFuture(failure));
        AtomicBoolean invoked = new AtomicBoolean(false);

        CompletableFuture<?> resultFuture = MoreFutures.rollupAsync(
                CompletableFuture.completedFuture(page1),
                DIRECT_EXECUTOR,
                rows -> {
                    invoked.set(true);
                    return CompletableFuture.completedFuture(null);
                });

        assertThatThrownBy(() -> resultFuture.get(2, TimeUnit.SECONDS))
                .isInstanceOf(ExecutionException.class)
                .hasCause(failure);
        assertThat(invoked.get()).isFalse();
    }

    @Test
    public void rollupAsyncHandlesManyConsecutiveEmptyPagesWithoutStackOverflow()
            throws Exception {
        AsyncResultSet lastPage = nonEmptyResultSet();
        AtomicInteger remainingEmptyPages = new AtomicInteger(5000);
        AsyncResultSet emptyPage = mock(AsyncResultSet.class);
        when(emptyPage.currentPage()).thenReturn(Collections.emptyList());
        when(emptyPage.hasMorePages()).thenReturn(true);
        when(emptyPage.fetchNextPage()).thenAnswer(invocation ->
                CompletableFuture.completedFuture(
                        remainingEmptyPages.decrementAndGet() > 0 ? emptyPage : lastPage));
        AtomicReference<AsyncResultSet> received = new AtomicReference<>();

        CompletableFuture<?> resultFuture = MoreFutures.rollupAsync(
                CompletableFuture.completedFuture(emptyPage),
                DIRECT_EXECUTOR,
                rows -> {
                    received.set(rows);
                    return CompletableFuture.completedFuture(null);
                });

        assertThat(resultFuture.get(2, TimeUnit.SECONDS)).isNull();
        assertThat(remainingEmptyPages.get()).isEqualTo(0);
        assertThat(received.get()).isSameAs(lastPage);
    }

    @Test
    public void rollupAsyncListAdvancesPastEmptyLeadingPagesToInvokeCallback() throws Exception {
        AsyncResultSet emptyNoMore = emptyResultSet();
        AsyncResultSet page2 = nonEmptyResultSet();
        CompletableFuture<AsyncResultSet> nextPageFuture = new CompletableFuture<>();
        AsyncResultSet emptyWithMore = emptyResultSetWithNextPage(nextPageFuture);

        AtomicBoolean ranOnAsyncExecutor = new AtomicBoolean(false);
        Executor trackingExecutor = command -> {
            ranOnAsyncExecutor.set(true);
            command.run();
        };
        AtomicReference<List<AsyncResultSet>> received = new AtomicReference<>();

        CompletableFuture<?> resultFuture = MoreFutures.rollupAsync(
                List.of(CompletableFuture.completedFuture(emptyNoMore),
                        CompletableFuture.completedFuture(emptyWithMore)),
                trackingExecutor,
                rows -> {
                    received.set(rows);
                    return CompletableFuture.completedFuture(null);
                });

        assertThat(resultFuture.isDone()).isFalse();
        assertThat(received.get()).isNull();

        nextPageFuture.complete(page2);

        assertThat(resultFuture.get(2, TimeUnit.SECONDS)).isNull();
        assertThat(ranOnAsyncExecutor.get()).isTrue();
        assertThat(received.get()).containsExactly(emptyNoMore, page2);
    }

    @Test
    public void rollupAsyncListSkipsCallbackWhenAllResultSetsHaveOnlyEmptyPages()
            throws Exception {
        AsyncResultSet rs1 = emptyResultSetWithNextPage(
                CompletableFuture.completedFuture(emptyResultSet()));
        AsyncResultSet rs2 = emptyResultSetWithNextPage(
                CompletableFuture.completedFuture(emptyResultSet()));
        AtomicBoolean invoked = new AtomicBoolean(false);

        Object result = MoreFutures.rollupAsync(
                        List.of(CompletableFuture.completedFuture(rs1),
                                CompletableFuture.completedFuture(rs2)),
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

    @Test
    public void runWithBoundedConcurrencyRejectsNonPositiveMaxConcurrency() {
        assertThatThrownBy(() -> MoreFutures.runWithBoundedConcurrency(
                List.of(1), 0, item -> CompletableFuture.completedFuture(null)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> MoreFutures.runWithBoundedConcurrency(
                Collections.emptyList(), 0, item -> CompletableFuture.completedFuture(null)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> MoreFutures.runAllWithBoundedConcurrency(
                List.of(1), -1, item -> CompletableFuture.completedFuture(null)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    public void runWithBoundedConcurrencyStopsPollingNewItemsAfterAsyncFailure()
            throws Exception {
        List<Integer> items = List.of(0, 1, 2, 3, 4, 5);
        List<CompletableFuture<Void>> pending = new ArrayList<>();
        List<Integer> started = new ArrayList<>();

        CompletableFuture<?> allDone = MoreFutures.runWithBoundedConcurrency(items, 2, item -> {
            started.add(item);
            CompletableFuture<Void> future = new CompletableFuture<>();
            pending.add(future);
            return future;
        });

        assertThat(started).containsExactly(0, 1);

        RuntimeException failure = new RuntimeException("boom");
        pending.get(0).completeExceptionally(failure);

        assertThat(allDone.isDone()).isFalse();

        pending.get(1).complete(null);

        assertThatThrownBy(() -> allDone.get(2, TimeUnit.SECONDS))
                .isInstanceOf(ExecutionException.class)
                .hasCause(failure);
        assertThat(started).containsExactly(0, 1);
    }

    @Test
    public void runWithBoundedConcurrencyPropagatesSynchronousThrow() {
        RuntimeException failure = new RuntimeException("sync boom");
        List<Integer> started = new ArrayList<>();

        CompletableFuture<?> result = MoreFutures.runWithBoundedConcurrency(
                List.of(1, 2, 3), 2, item -> {
                    started.add(item);
                    if (item == 1) {
                        throw failure;
                    }
                    return CompletableFuture.completedFuture(null);
                });

        assertThatThrownBy(() -> result.get(2, TimeUnit.SECONDS))
                .isInstanceOf(ExecutionException.class)
                .hasCause(failure);
        assertThat(started).containsExactly(1);
    }

    @Test
    public void runAllWithBoundedConcurrencyDrainsAllItemsAfterAllWorkersFailAsynchronously()
            throws Exception {
        List<Integer> items = List.of(0, 1, 2, 3, 4, 5);
        AtomicInteger active = new AtomicInteger();
        AtomicInteger maxActive = new AtomicInteger();
        List<Integer> started = new ArrayList<>();
        CompletableFuture<Void> f0 = new CompletableFuture<>();
        CompletableFuture<Void> f1 = new CompletableFuture<>();
        CompletableFuture<Void> f4 = new CompletableFuture<>();
        CompletableFuture<Void> f5 = new CompletableFuture<>();

        CompletableFuture<?> allDone = MoreFutures.runAllWithBoundedConcurrency(items, 2, item -> {
            int current = active.incrementAndGet();
            maxActive.accumulateAndGet(current, Math::max);
            started.add(item);
            switch (item) {
                case 0:
                    return f0.thenApply(Function.identity());
                case 1:
                    return f1;
                case 2:
                    active.decrementAndGet();
                    throw new RuntimeException("sync throw");
                case 3:
                    active.decrementAndGet();
                    return CompletableFuture.failedFuture(new RuntimeException("sync fail"));
                case 4:
                    return f4;
                case 5:
                    return f5;
                default:
                    throw new AssertionError("Unexpected item: " + item);
            }
        });

        assertThat(started).containsExactly(0, 1);
        assertThat(active.get()).isEqualTo(2);
        assertThat(allDone.isDone()).isFalse();

        RuntimeException failure0 = new RuntimeException("async fail 0");
        active.decrementAndGet();
        f0.completeExceptionally(failure0);

        RuntimeException failure1 = new RuntimeException("async fail 1");
        active.decrementAndGet();
        f1.completeExceptionally(failure1);

        assertThat(started).containsExactly(0, 1, 2, 3, 4, 5);
        assertThat(active.get()).isEqualTo(2);
        assertThat(allDone.isDone()).isFalse();

        active.decrementAndGet();
        f4.complete(null);
        assertThat(allDone.isDone()).isFalse();

        active.decrementAndGet();
        f5.complete(null);

        assertThatThrownBy(() -> allDone.get(2, TimeUnit.SECONDS))
                .isInstanceOf(ExecutionException.class)
                .hasCause(failure0);
        assertThat(maxActive.get()).isEqualTo(2);
        assertThat(active.get()).isEqualTo(0);
    }

    @Test
    public void runAllWithBoundedConcurrencyHandlesSynchronousFailuresWithoutStackOverflow()
            throws Exception {
        assertThat(MoreFutures.runAllWithBoundedConcurrency(Collections.emptyList(), 3,
                item -> CompletableFuture.completedFuture(null)).get(2, TimeUnit.SECONDS))
                .isNull();

        List<Integer> largeItems = new ArrayList<>(5000);
        for (int i = 0; i < 5000; i++) {
            largeItems.add(i);
        }
        AtomicInteger processed = new AtomicInteger();
        RuntimeException firstItemFailure = new RuntimeException("fail-0");

        CompletableFuture<?> result = MoreFutures.runAllWithBoundedConcurrency(
                largeItems, 4, item -> {
                    processed.incrementAndGet();
                    return CompletableFuture.failedFuture(
                            item == 0 ? firstItemFailure : new RuntimeException("fail-" + item));
                });

        assertThatThrownBy(() -> result.get(2, TimeUnit.SECONDS))
                .isInstanceOf(ExecutionException.class)
                .hasCause(firstItemFailure);
        assertThat(processed.get()).isEqualTo(5000);
    }

    private static AsyncResultSet emptyResultSet() {
        AsyncResultSet resultSet = mock(AsyncResultSet.class);
        when(resultSet.currentPage()).thenReturn(Collections.emptyList());
        return resultSet;
    }

    private static AsyncResultSet emptyResultSetWithNextPage(
            CompletionStage<AsyncResultSet> nextPage) {
        AsyncResultSet resultSet = mock(AsyncResultSet.class);
        when(resultSet.currentPage()).thenReturn(Collections.emptyList());
        when(resultSet.hasMorePages()).thenReturn(true);
        when(resultSet.fetchNextPage()).thenReturn(nextPage);
        return resultSet;
    }

    private static AsyncResultSet nonEmptyResultSet() {
        AsyncResultSet resultSet = mock(AsyncResultSet.class);
        Row row = mock(Row.class);
        when(resultSet.currentPage()).thenReturn(List.of(row));
        return resultSet;
    }
}
