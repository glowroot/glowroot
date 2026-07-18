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
package org.glowroot.central.repo;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;

import com.google.common.collect.ImmutableList;
import org.junit.jupiter.api.Test;

import org.glowroot.wire.api.model.TraceOuterClass.Trace;

import static org.assertj.core.api.Assertions.assertThat;

public class SharedQueryTextsForExportTest {

    @Test
    public void preservesIndexOrderWhenAsyncResolutionCompletesOutOfOrder() throws Exception {
        Trace.SharedQueryText inline0 = Trace.SharedQueryText.newBuilder()
                .setFullText("select a")
                .build();
        Trace.SharedQueryText sha1 = Trace.SharedQueryText.newBuilder()
                .setTruncatedText("select b start")
                .setTruncatedEndText("end")
                .setFullTextSha1("sha-b")
                .build();
        Trace.SharedQueryText inline2 = Trace.SharedQueryText.newBuilder()
                .setFullText("select c")
                .build();

        // Slow resolution for index 1 so it finishes after index 2 would have been reachable —
        // the old ArrayList.add()-on-complete path scrambled order here.
        CompletionStage<List<Trace.SharedQueryText>> stage = SharedQueryTextsForExport.resolve(
                ImmutableList.of(inline0, sha1, inline2),
                fullTextSha1 -> CompletableFuture.supplyAsync(() -> {
                    try {
                        Thread.sleep(50);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    return "select b full";
                }));

        List<Trace.SharedQueryText> resolved = stage.toCompletableFuture().get(2, TimeUnit.SECONDS);

        assertThat(resolved).hasSize(3);
        assertThat(resolved.get(0).getFullText()).isEqualTo("select a");
        assertThat(resolved.get(1).getFullText()).isEqualTo("select b full");
        assertThat(resolved.get(2).getFullText()).isEqualTo("select c");
    }

    @Test
    public void expiredFullTextKeepsSlotAndUsesTruncatedFallback() throws Exception {
        Trace.SharedQueryText sha1 = Trace.SharedQueryText.newBuilder()
                .setTruncatedText("head")
                .setTruncatedEndText("tail")
                .setFullTextSha1("missing")
                .build();
        Trace.SharedQueryText inline = Trace.SharedQueryText.newBuilder()
                .setFullText("inline")
                .build();

        List<Trace.SharedQueryText> resolved = SharedQueryTextsForExport
                .resolve(ImmutableList.of(sha1, inline),
                        fullTextSha1 -> CompletableFuture.completedFuture(null))
                .toCompletableFuture()
                .get(2, TimeUnit.SECONDS);

        assertThat(resolved.get(0).getFullText())
                .isEqualTo("head ... [full query text has expired] ... tail");
        assertThat(resolved.get(1).getFullText()).isEqualTo("inline");
    }
}
