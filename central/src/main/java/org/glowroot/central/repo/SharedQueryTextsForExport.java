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

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.Function;

import org.glowroot.wire.api.model.TraceOuterClass.Trace;

/**
 * Resolves full query texts for trace export while preserving
 * {@code sharedQueryTextIndex} order used by entries and query stats.
 */
final class SharedQueryTextsForExport {

    private SharedQueryTextsForExport() {}

    /**
     * @param getFullText maps fullTextSha1 → full text (null if expired / missing)
     */
    static CompletionStage<List<Trace.SharedQueryText>> resolve(
            List<Trace.SharedQueryText> sharedQueryTexts,
            Function<String, CompletionStage<String>> getFullText) {
        List<Trace.SharedQueryText> resolved =
                new ArrayList<>(Collections.nCopies(sharedQueryTexts.size(), null));
        List<CompletableFuture<?>> futures = new ArrayList<>();
        for (int i = 0; i < sharedQueryTexts.size(); i++) {
            Trace.SharedQueryText sharedQueryText = sharedQueryTexts.get(i);
            String fullTextSha1 = sharedQueryText.getFullTextSha1();
            if (fullTextSha1.isEmpty()) {
                resolved.set(i, sharedQueryText);
            } else {
                final int index = i;
                futures.add(getFullText.apply(fullTextSha1).thenAccept(fullText -> {
                    if (fullText == null) {
                        resolved.set(index, Trace.SharedQueryText.newBuilder()
                                .setFullText(sharedQueryText.getTruncatedText()
                                        + " ... [full query text has expired] ... "
                                        + sharedQueryText.getTruncatedEndText())
                                .build());
                    } else {
                        resolved.set(index, Trace.SharedQueryText.newBuilder()
                                .setFullText(fullText)
                                .build());
                    }
                }).toCompletableFuture());
            }
        }
        return CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                .thenApply(ignored -> resolved);
    }
}
