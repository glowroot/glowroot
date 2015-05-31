/*
 * Copyright 2013-2015 the original author or authors.
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
package org.glowroot.collector;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;

import javax.annotation.Nullable;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.glowroot.collector.QueryComponent.AggregateQuery;
import org.glowroot.common.ObjectMappers;
import org.glowroot.common.ScratchBuffer;
import org.glowroot.transaction.model.Profile;
import org.glowroot.transaction.model.ProfileNode;
import org.glowroot.transaction.model.QueryData;
import org.glowroot.transaction.model.ThreadInfoData;
import org.glowroot.transaction.model.TimerImpl;
import org.glowroot.transaction.model.Transaction;

import static java.util.concurrent.TimeUnit.NANOSECONDS;

// must be used under an appropriate lock
class AggregateBuilder {

    private static final ObjectMapper mapper = ObjectMappers.create();

    private final String transactionType;
    private final @Nullable String transactionName;
    private long totalMicros;
    private long errorCount;
    private long transactionCount;
    private @Nullable Long totalCpuTime;
    private @Nullable Long totalBlockedTime;
    private @Nullable Long totalWaitedTime;
    private @Nullable Long totalAllocatedBytes;
    private long traceCount;
    // histogram uses microseconds to reduce (or at least simplify) bucket allocations
    private final LazyHistogram lazyHistogram = new LazyHistogram();
    private final AggregateTimer syntheticRootTimer = AggregateTimer.createSyntheticRootTimer();
    private final QueryComponent queryComponent;
    private final AggregateProfileBuilder aggregateProfile = new AggregateProfileBuilder();

    AggregateBuilder(String transactionType, @Nullable String transactionName,
            int maxAggregateQueriesPerQueryType) {
        this.transactionType = transactionType;
        this.transactionName = transactionName;
        queryComponent = new QueryComponent(maxAggregateQueriesPerQueryType, true);
    }

    void add(Transaction transaction) {
        long durationMicros = NANOSECONDS.toMicros(transaction.getDuration());
        totalMicros += durationMicros;
        if (transaction.getErrorMessage() != null) {
            errorCount++;
        }
        if (transaction.willBeStored()) {
            traceCount++;
        }
        transactionCount++;
        ThreadInfoData threadInfo = transaction.getThreadInfo();
        if (threadInfo != null) {
            totalCpuTime = nullAwareAdd(totalCpuTime, threadInfo.threadCpuTime());
            totalBlockedTime = nullAwareAdd(totalBlockedTime, threadInfo.threadBlockedTime());
            totalWaitedTime = nullAwareAdd(totalWaitedTime, threadInfo.threadWaitedTime());
            totalAllocatedBytes = nullAwareAdd(totalAllocatedBytes,
                    threadInfo.threadAllocatedBytes());
        }
        lazyHistogram.add(durationMicros);
    }

    void addToTimers(TimerImpl rootTimer) {
        syntheticRootTimer.mergeAsChildTimer(rootTimer);
    }

    void addToQueries(Map<String, Map<String, QueryData>> queries) {
        for (Entry<String, Map<String, QueryData>> entry : queries.entrySet()) {
            queryComponent.mergeQueries(entry.getKey(), entry.getValue());
        }
    }

    void addToProfile(Profile profile) {
        aggregateProfile.addProfile(profile);
    }

    Aggregate build(long captureTime, ScratchBuffer scratchBuffer) throws IOException {
        ByteBuffer buffer = scratchBuffer.getBuffer(lazyHistogram.getNeededByteBufferCapacity());
        buffer.clear();
        byte[] histogram = lazyHistogram.encodeUsingTempByteBuffer(buffer);
        return Aggregate.builder()
                .transactionType(transactionType)
                .transactionName(transactionName)
                .captureTime(captureTime)
                .totalMicros(totalMicros)
                .errorCount(errorCount)
                .transactionCount(transactionCount)
                .totalCpuMicros(nullAwareNanosToMicros(totalCpuTime))
                .totalBlockedMicros(nullAwareNanosToMicros(totalBlockedTime))
                .totalWaitedMicros(nullAwareNanosToMicros(totalWaitedTime))
                .totalAllocatedKBytes(nullAwareBytesToKBytes(totalAllocatedBytes))
                .traceCount(traceCount)
                .histogram(histogram)
                .timers(mapper.writeValueAsString(syntheticRootTimer))
                .queries(getQueriesJson())
                .profile(getProfileJson())
                .build();
    }

    TransactionSummary getLiveTransactionSummary() {
        return TransactionSummary.builder()
                .transactionName(transactionName)
                .totalMicros(totalMicros)
                .transactionCount(transactionCount)
                .build();
    }

    ErrorSummary getLiveErrorSummary() {
        return ErrorSummary.builder()
                .transactionName(transactionName)
                .errorCount(errorCount)
                .transactionCount(transactionCount)
                .build();
    }

    @Nullable
    ErrorPoint buildErrorPoint(long captureTime) {
        if (errorCount == 0) {
            return null;
        }
        return ErrorPoint.builder()
                .captureTime(captureTime)
                .errorCount(errorCount)
                .transactionCount(transactionCount)
                .build();
    }

    @Nullable
    String getQueriesJson() throws IOException {
        Map<String, List<AggregateQuery>> queries = queryComponent.getOrderedAndTruncatedQueries();
        if (queries.isEmpty()) {
            return null;
        }
        return mapper.writeValueAsString(queries);
    }

    @Nullable
    String getProfileJson() throws IOException {
        ProfileNode syntheticRootNode = aggregateProfile.getSyntheticRootNode();
        if (syntheticRootNode.getChildNodes().isEmpty()) {
            return null;
        }
        return mapper.writeValueAsString(syntheticRootNode);
    }

    private static @Nullable Long nullAwareNanosToMicros(@Nullable Long nanoseconds) {
        if (nanoseconds == null) {
            return null;
        }
        return NANOSECONDS.toMicros(nanoseconds);
    }

    private static @Nullable Long nullAwareBytesToKBytes(@Nullable Long bytes) {
        if (bytes == null) {
            return null;
        }
        return bytes / 1024;
    }

    private static @Nullable Long nullAwareAdd(@Nullable Long x, @Nullable Long y) {
        if (x == null) {
            return y;
        }
        if (y == null) {
            return x;
        }
        return x + y;
    }
}
