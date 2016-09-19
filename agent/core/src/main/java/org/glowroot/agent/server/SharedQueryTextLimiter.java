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
package org.glowroot.agent.server;

import java.util.List;

import com.google.common.base.Charsets;
import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import com.google.common.collect.Lists;
import com.google.common.hash.Hashing;

import org.glowroot.wire.api.model.AggregateOuterClass.Aggregate;
import org.glowroot.wire.api.model.TraceOuterClass.Trace;

import static com.google.common.base.Preconditions.checkState;
import static java.util.concurrent.TimeUnit.DAYS;

class SharedQueryTextLimiter {

    // if full text sha1 has not been sent in the past day, there's a possibility the full text may
    // have expired on the server and so full text must be sent
    private final Cache<String, Boolean> sentInThePastDay = CacheBuilder.newBuilder()
            .expireAfterWrite(1, DAYS)
            .maximumSize(10000)
            .build();

    List<Aggregate.SharedQueryText> reduceAggregatePayloadWherePossible(List<String> fullTexts) {
        List<Aggregate.SharedQueryText> sharedQueryTexts = Lists.newArrayList();
        for (String fullText : fullTexts) {
            if (fullText.length() > 120) {
                String fullTextSha1 =
                        Hashing.sha1().hashString(fullText, Charsets.UTF_8).toString();
                if (sentInThePastDay.getIfPresent(fullTextSha1) == null) {
                    // need to send full text
                    sharedQueryTexts.add(Aggregate.SharedQueryText.newBuilder()
                            .setFullText(fullText)
                            .build());
                } else {
                    // ok to just send truncated text
                    sharedQueryTexts.add(Aggregate.SharedQueryText.newBuilder()
                            .setTruncatedText(fullText.substring(0, 120))
                            .setFullTextSha1(fullTextSha1)
                            .build());
                }
            } else {
                sharedQueryTexts.add(Aggregate.SharedQueryText.newBuilder()
                        .setFullText(fullText)
                        .build());
            }
        }
        return sharedQueryTexts;
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
            if (fullText.length() > 240) {
                String fullTextSha1 =
                        Hashing.sha1().hashString(fullText, Charsets.UTF_8).toString();
                if (sentInThePastDay.getIfPresent(fullTextSha1) == null) {
                    // need to send full text
                    updatedSharedQueryTexts.add(sharedQueryText);
                } else {
                    // ok to just send truncated text
                    updatedSharedQueryTexts.add(Trace.SharedQueryText.newBuilder()
                            .setTruncatedText(fullText.substring(0, 120))
                            .setTruncatedEndText(
                                    fullText.substring(fullText.length() - 120, fullText.length()))
                            .setFullTextSha1(fullTextSha1)
                            .build());
                }
            } else {
                updatedSharedQueryTexts.add(sharedQueryText);
            }
        }
        return updatedSharedQueryTexts;
    }

    void onSuccessfullySentToServer(String fullTextSha1) {
        sentInThePastDay.put(fullTextSha1, true);
    }
}
