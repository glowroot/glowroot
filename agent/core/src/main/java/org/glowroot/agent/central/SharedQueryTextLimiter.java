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
package org.glowroot.agent.central;

import java.util.List;

import com.google.common.base.Charsets;
import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import com.google.common.collect.Lists;
import com.google.common.hash.Hashing;

import org.glowroot.common.config.StorageConfig;
import org.glowroot.wire.api.model.AggregateOuterClass.Aggregate;
import org.glowroot.wire.api.model.TraceOuterClass.Trace;

import static com.google.common.base.Preconditions.checkState;
import static java.util.concurrent.TimeUnit.DAYS;

class SharedQueryTextLimiter {

    // if full text sha1 has not been sent in the past day, there's a possibility the full text may
    // have expired in the central collector and so full text must be sent
    private final Cache<String, Boolean> sentInThePastDay = CacheBuilder.newBuilder()
            .expireAfterWrite(1, DAYS)
            .maximumSize(10000)
            .build();

    Aggregate.SharedQueryText buildAggregateSharedQueryText(String fullText) {
        if (fullText.length() > StorageConfig.AGGREGATE_QUERY_TEXT_TRUNCATE) {
            String fullTextSha1 = Hashing.sha1().hashString(fullText, Charsets.UTF_8).toString();
            if (sentInThePastDay.getIfPresent(fullTextSha1) == null) {
                // need to send full text
                return Aggregate.SharedQueryText.newBuilder()
                        .setFullText(fullText)
                        .build();
            } else {
                // ok to just send truncated text
                return Aggregate.SharedQueryText.newBuilder()
                        .setTruncatedText(
                                fullText.substring(0, StorageConfig.AGGREGATE_QUERY_TEXT_TRUNCATE))
                        .setFullTextSha1(fullTextSha1)
                        .build();
            }
        } else {
            return Aggregate.SharedQueryText.newBuilder()
                    .setFullText(fullText)
                    .build();
        }
    }

    Trace.SharedQueryText buildTraceSharedQueryText(String fullText) {
        if (fullText.length() > 2 * StorageConfig.TRACE_QUERY_TEXT_TRUNCATE) {
            String fullTextSha1 = Hashing.sha1().hashString(fullText, Charsets.UTF_8).toString();
            if (sentInThePastDay.getIfPresent(fullTextSha1) == null) {
                // need to send full text
                return Trace.SharedQueryText.newBuilder()
                        .setFullText(fullText)
                        .build();
            } else {
                // ok to just send truncated text
                return Trace.SharedQueryText.newBuilder()
                        .setTruncatedText(
                                fullText.substring(0, StorageConfig.TRACE_QUERY_TEXT_TRUNCATE))
                        .setTruncatedEndText(fullText.substring(
                                fullText.length() - StorageConfig.TRACE_QUERY_TEXT_TRUNCATE,
                                fullText.length()))
                        .setFullTextSha1(fullTextSha1)
                        .build();
            }
        } else {
            return Trace.SharedQueryText.newBuilder()
                    .setFullText(fullText)
                    .build();
        }
    }

    List<Trace.SharedQueryText> reduceTracePayloadWherePossible(
            List<Trace.SharedQueryText> sharedQueryTexts) {
        List<Trace.SharedQueryText> updatedSharedQueryTexts = Lists.newArrayList();
        for (Trace.SharedQueryText sharedQueryText : sharedQueryTexts) {
            // local collection always passes in full text
            checkState(sharedQueryText.getTruncatedText().isEmpty());
            checkState(sharedQueryText.getTruncatedEndText().isEmpty());
            checkState(sharedQueryText.getFullTextSha1().isEmpty());
            String fullText = sharedQueryText.getFullText();
            if (fullText.length() > 2 * StorageConfig.TRACE_QUERY_TEXT_TRUNCATE) {
                String fullTextSha1 =
                        Hashing.sha1().hashString(fullText, Charsets.UTF_8).toString();
                if (sentInThePastDay.getIfPresent(fullTextSha1) == null) {
                    // need to send full text
                    updatedSharedQueryTexts.add(sharedQueryText);
                } else {
                    // ok to just send truncated text
                    updatedSharedQueryTexts.add(Trace.SharedQueryText.newBuilder()
                            .setTruncatedText(
                                    fullText.substring(0, StorageConfig.TRACE_QUERY_TEXT_TRUNCATE))
                            .setTruncatedEndText(fullText.substring(
                                    fullText.length() - StorageConfig.TRACE_QUERY_TEXT_TRUNCATE,
                                    fullText.length()))
                            .setFullTextSha1(fullTextSha1)
                            .build());
                }
            } else {
                updatedSharedQueryTexts.add(sharedQueryText);
            }
        }
        return updatedSharedQueryTexts;
    }

    void onSuccessfullySentToCentralCollector(String fullTextSha1) {
        sentInThePastDay.put(fullTextSha1, true);
    }
}
