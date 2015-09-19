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
package org.glowroot.server.repo;

import java.io.IOException;
import java.util.List;
import java.util.zip.DataFormatException;

import com.google.common.collect.Lists;
import org.checkerframework.checker.nullness.qual.MonotonicNonNull;

import org.glowroot.collector.spi.model.AggregateOuterClass.Aggregate;
import org.glowroot.collector.spi.model.ProfileTreeOuterClass.ProfileTree;
import org.glowroot.common.model.LazyHistogram;
import org.glowroot.common.model.LazyHistogram.ScratchBuffer;
import org.glowroot.common.model.MutableProfileTree;
import org.glowroot.common.model.QueryCollector;
import org.glowroot.common.util.NotAvailableAware;
import org.glowroot.common.util.Styles;
import org.glowroot.live.ImmutableOverviewAggregate;
import org.glowroot.live.ImmutablePercentileAggregate;
import org.glowroot.live.LiveAggregateRepository.OverviewAggregate;
import org.glowroot.live.LiveAggregateRepository.PercentileAggregate;

@Styles.Private
public class MutableAggregate {

    private final long captureTime;
    private double totalNanos;
    private long transactionCount;
    private long errorCount;
    private double totalCpuNanos = NotAvailableAware.NA;
    private double totalBlockedNanos = NotAvailableAware.NA;
    private double totalWaitedNanos = NotAvailableAware.NA;
    private double totalAllocatedBytes = NotAvailableAware.NA;
    private final LazyHistogram lazyHistogram = new LazyHistogram();
    private final List<MutableTimer> rootTimers = Lists.newArrayList();
    private QueryCollector queries;
    // lazy instantiated to reduce memory footprint
    private @MonotonicNonNull MutableProfileTree profileTree;

    public MutableAggregate(long captureTime, int maxAggregateQueriesPerQueryType) {
        this.captureTime = captureTime;
        queries = new QueryCollector(maxAggregateQueriesPerQueryType, 0);
    }

    public void addTotalNanos(double totalNanos) {
        this.totalNanos += totalNanos;
    }

    public void addTransactionCount(long transactionCount) {
        this.transactionCount += transactionCount;
    }

    public void addErrorCount(long errorCount) {
        this.errorCount += errorCount;
    }

    public void addTotalCpuNanos(double totalCpuNanos) {
        this.totalCpuNanos = NotAvailableAware.add(this.totalCpuNanos, totalCpuNanos);
    }

    public void addTotalBlockedNanos(double totalBlockedNanos) {
        this.totalBlockedNanos = NotAvailableAware.add(this.totalBlockedNanos, totalBlockedNanos);
    }

    public void addTotalWaitedNanos(double totalWaitedNanos) {
        this.totalWaitedNanos = NotAvailableAware.add(this.totalWaitedNanos, totalWaitedNanos);
    }

    public void addTotalAllocatedBytes(double totalAllocatedBytes) {
        this.totalAllocatedBytes =
                NotAvailableAware.add(this.totalAllocatedBytes, totalAllocatedBytes);
    }

    public void mergeHistogram(Aggregate.Histogram toBeMergedHistogram) throws DataFormatException {
        lazyHistogram.merge(toBeMergedHistogram);
    }

    public void mergeRootTimers(List<Aggregate.Timer> toBeMergedRootTimers) {
        for (Aggregate.Timer toBeMergedRootTimer : toBeMergedRootTimers) {
            mergeRootTimer(toBeMergedRootTimer);
        }
    }

    private void mergeRootTimer(Aggregate.Timer toBeMergedRootTimer) {
        for (MutableTimer rootTimer : rootTimers) {
            if (toBeMergedRootTimer.getName().equals(rootTimer.getName())) {
                rootTimer.merge(toBeMergedRootTimer);
                return;
            }
        }
        MutableTimer rootTimer = MutableTimer.createRootTimer(toBeMergedRootTimer.getName(),
                toBeMergedRootTimer.getExtended());
        rootTimer.merge(toBeMergedRootTimer);
        rootTimers.add(rootTimer);
    }

    public Aggregate toAggregate(ScratchBuffer scratchBuffer) throws IOException {
        Aggregate.Builder builder = Aggregate.newBuilder()
                .setCaptureTime(captureTime)
                .setTotalNanos(totalNanos)
                .setTransactionCount(transactionCount)
                .setErrorCount(errorCount)
                .setTotalCpuNanos(totalCpuNanos)
                .setTotalBlockedNanos(totalBlockedNanos)
                .setTotalWaitedNanos(totalWaitedNanos)
                .setTotalAllocatedBytes(totalAllocatedBytes)
                .setTotalNanosHistogram(lazyHistogram.toProtobuf(scratchBuffer))
                .addAllRootTimer(getRootTimersProtobuf())
                .addAllQueriesByType(queries.toProtobuf(true));
        if (profileTree != null) {
            builder.setProfileTree(profileTree.toProtobuf());
        }
        return builder.build();
    }

    public OverviewAggregate toOverviewAggregate() throws IOException {
        return ImmutableOverviewAggregate.builder()
                .captureTime(captureTime)
                .totalNanos(totalNanos)
                .transactionCount(transactionCount)
                .totalCpuNanos(totalCpuNanos)
                .totalBlockedNanos(totalBlockedNanos)
                .totalWaitedNanos(totalWaitedNanos)
                .totalAllocatedBytes(totalAllocatedBytes)
                .rootTimers(getRootTimersProtobuf())
                .build();
    }

    public PercentileAggregate toPercentileAggregate() throws IOException {
        return ImmutablePercentileAggregate.builder()
                .captureTime(captureTime)
                .totalNanos(totalNanos)
                .transactionCount(transactionCount)
                .histogram(lazyHistogram.toProtobuf(new ScratchBuffer()))
                .build();
    }

    public void mergeQueries(List<Aggregate.QueriesByType> toBeMergedQueries) throws IOException {
        queries.mergeQueries(toBeMergedQueries);
    }

    public void mergeProfile(ProfileTree toBeMergedProfileTree) throws IOException {
        if (profileTree == null) {
            profileTree = new MutableProfileTree();
        }
        profileTree.merge(toBeMergedProfileTree);
    }

    private List<Aggregate.Timer> getRootTimersProtobuf() {
        List<Aggregate.Timer> rootTimers = Lists.newArrayListWithCapacity(this.rootTimers.size());
        for (MutableTimer rootTimer : this.rootTimers) {
            rootTimers.add(rootTimer.toProtobuf());
        }
        return rootTimers;
    }
}
