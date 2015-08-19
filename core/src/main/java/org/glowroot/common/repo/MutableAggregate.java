/*
 * Copyright 2015 the original author or authors.
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
package org.glowroot.common.repo;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.Map;
import java.util.zip.DataFormatException;

import org.immutables.value.Value;

import org.glowroot.agent.model.ThreadInfoComponent.ThreadInfoData;
import org.glowroot.collector.spi.Aggregate;
import org.glowroot.common.repo.AggregateRepository.OverviewAggregate;
import org.glowroot.common.repo.AggregateRepository.PercentileAggregate;
import org.glowroot.common.util.Styles;

@Styles.Private
@Value.Include(Aggregate.class)
public class MutableAggregate {

    private long captureTime;
    private long totalMicros;
    private long errorCount;
    private long transactionCount;
    private long totalCpuMicros = ThreadInfoData.NOT_AVAILABLE;
    private long totalBlockedMicros = ThreadInfoData.NOT_AVAILABLE;
    private long totalWaitedMicros = ThreadInfoData.NOT_AVAILABLE;
    private long totalAllocatedKBytes = ThreadInfoData.NOT_AVAILABLE;
    private final LazyHistogram lazyHistogram = new LazyHistogram();
    private final MutableTimerNode syntheticRootTimerNode =
            MutableTimerNode.createSyntheticRootNode();
    private final QueryCollector mergedQueries;
    private final MutableProfileNode syntheticRootProfileNode =
            MutableProfileNode.createSyntheticRootNode();

    public MutableAggregate(long captureTime, int maxAggregateQueriesPerQueryType) {
        this.captureTime = captureTime;
        mergedQueries = new QueryCollector(maxAggregateQueriesPerQueryType, 0);
    }

    public void setCaptureTime(long captureTime) {
        this.captureTime = captureTime;
    }

    public void addTotalMicros(long totalMicros) {
        this.totalMicros += totalMicros;
    }

    public void addErrorCount(long errorCount) {
        this.errorCount += errorCount;
    }

    public void addTransactionCount(long transactionCount) {
        this.transactionCount += transactionCount;
    }

    public void addTotalCpuMicros(long totalCpuMicros) {
        this.totalCpuMicros = notAvailableAwareAdd(this.totalCpuMicros, totalCpuMicros);
    }

    public void addTotalBlockedMicros(long totalBlockedMicros) {
        this.totalBlockedMicros = notAvailableAwareAdd(this.totalBlockedMicros, totalBlockedMicros);
    }

    public void addTotalWaitedMicros(long totalWaitedMicros) {
        this.totalWaitedMicros = notAvailableAwareAdd(this.totalWaitedMicros, totalWaitedMicros);
    }

    public void addTotalAllocatedKBytes(long totalAllocatedKBytes) {
        this.totalAllocatedKBytes =
                notAvailableAwareAdd(this.totalAllocatedKBytes, totalAllocatedKBytes);
    }

    public void addHistogram(byte[] histogram) throws DataFormatException {
        lazyHistogram.decodeFromByteBuffer(ByteBuffer.wrap(histogram));
    }

    public void addHistogram(LazyHistogram histogram) throws DataFormatException {
        lazyHistogram.merge(histogram);
    }

    public void addTimers(MutableTimerNode syntheticRootTimer) throws IOException {
        this.syntheticRootTimerNode.mergeMatchedTimer(syntheticRootTimer);
    }

    public Aggregate toAggregate() throws IOException {
        AggregateBuilder builder = new AggregateBuilder()
                .captureTime(captureTime)
                .totalMicros(totalMicros)
                .errorCount(errorCount)
                .transactionCount(transactionCount)
                .totalCpuMicros(totalCpuMicros)
                .totalBlockedMicros(totalBlockedMicros)
                .totalWaitedMicros(totalWaitedMicros)
                .totalAllocatedKBytes(totalAllocatedKBytes)
                .histogram(lazyHistogram)
                .syntheticRootTimerNode(syntheticRootTimerNode)
                .queries(mergedQueries.getOrderedAndTruncatedQueries());
        if (syntheticRootProfileNode.sampleCount() > 0) {
            builder.syntheticRootProfileNode(syntheticRootProfileNode);
        }
        return builder.build();
    }

    public OverviewAggregate toOverviewAggregate() throws IOException {
        return ImmutableOverviewAggregate.builder()
                .captureTime(captureTime)
                .totalMicros(totalMicros)
                .transactionCount(transactionCount)
                .totalCpuMicros(totalCpuMicros)
                .totalBlockedMicros(totalBlockedMicros)
                .totalWaitedMicros(totalWaitedMicros)
                .totalAllocatedKBytes(totalAllocatedKBytes)
                .syntheticRootTimer(syntheticRootTimerNode)
                .build();
    }

    public PercentileAggregate toPercentileAggregate() throws IOException {
        return ImmutablePercentileAggregate.builder()
                .captureTime(captureTime)
                .totalMicros(totalMicros)
                .transactionCount(transactionCount)
                .histogram(lazyHistogram)
                .build();
    }

    public void addQueries(Map<String, List<MutableQuery>> queries) throws IOException {
        mergedQueries.mergeQueries(queries);
    }

    public void addProfile(MutableProfileNode syntheticRootProfileNode) throws IOException {
        this.syntheticRootProfileNode.mergeMatchedNode(syntheticRootProfileNode);
    }

    private static long notAvailableAwareAdd(long x, long y) {
        if (x == ThreadInfoData.NOT_AVAILABLE) {
            return y;
        }
        if (y == ThreadInfoData.NOT_AVAILABLE) {
            return x;
        }
        return x + y;
    }
}
