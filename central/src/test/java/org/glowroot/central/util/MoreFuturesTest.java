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

import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import com.datastax.oss.driver.api.core.cql.AsyncResultSet;
import com.datastax.oss.driver.api.core.cql.Row;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
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
