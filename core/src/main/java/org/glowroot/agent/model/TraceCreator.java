/*
 * Copyright 2012-2015 the original author or authors.
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
package org.glowroot.agent.model;

import java.io.IOException;
import java.util.Map;

import com.google.common.collect.ImmutableSetMultimap;
import org.immutables.value.Value;

import org.glowroot.agent.model.ThreadInfoComponent.ThreadInfoData;
import org.glowroot.collector.spi.Trace;
import org.glowroot.common.repo.ImmutableTraceHeader;
import org.glowroot.common.repo.TraceRepository.Existence;
import org.glowroot.common.repo.TraceRepository.TraceHeader;
import org.glowroot.common.util.Styles;

@Styles.Private
@Value.Include(Trace.class)
public class TraceCreator {

    private TraceCreator() {}

    public static Trace createPartialTrace(Transaction transaction, long captureTime,
            long captureTick) throws IOException {
        return createTrace(transaction, true, true, true, captureTime, captureTick);
    }

    public static Trace createCompletedTrace(Transaction transaction, boolean slow)
            throws IOException {
        return createTrace(transaction, slow, false, false, transaction.getCaptureTime(),
                transaction.getEndTick());
    }

    public static TraceHeader createActiveTraceHeader(Transaction transaction, long captureTime,
            long captureTick) throws IOException {
        return createTraceHeader(transaction, true, false, captureTime, captureTick);
    }

    public static TraceHeader createCompletedTraceHeader(Transaction transaction)
            throws IOException {
        return createTraceHeader(transaction, false, false, transaction.getCaptureTime(),
                transaction.getEndTick());
    }

    // timings for traces that are still active are normalized to the capture tick in order to
    // *attempt* to present a picture of the trace at that exact tick
    // (without using synchronization to block updates to the trace while it is being read)
    private static Trace createTrace(Transaction transaction, boolean slow, boolean active,
            boolean partial, long captureTime, long captureTick) throws IOException {
        TraceBuilder builder = new TraceBuilder();
        builder.id(transaction.getId());
        builder.partial(partial);
        builder.slow(slow);
        ErrorMessage errorMessage = transaction.getErrorMessage();
        builder.error(errorMessage != null);
        builder.startTime(transaction.getStartTime());
        builder.captureTime(captureTime);
        builder.durationNanos(captureTick - transaction.getStartTick());
        builder.transactionType(transaction.getTransactionType());
        builder.transactionName(transaction.getTransactionName());
        builder.headline(transaction.getHeadline());
        builder.user(transaction.getUser());
        ImmutableSetMultimap<String, String> customAttributes = transaction.getCustomAttributes();
        builder.customAttributes(customAttributes.asMap());
        builder.customDetail(cast(transaction.getCustomDetail()));
        if (errorMessage != null) {
            builder.errorMessage(errorMessage.message());
            builder.errorThrowable(errorMessage.throwable());
        }
        if (active) {
            builder.rootTimer(transaction.getRootTimer().getSnapshot());
        } else {
            builder.rootTimer(transaction.getRootTimer());
        }
        ThreadInfoData threadInfo = transaction.getThreadInfo();
        if (threadInfo == null) {
            builder.threadCpuNanos(-1);
            builder.threadBlockedNanos(-1);
            builder.threadWaitedNanos(-1);
            builder.threadAllocatedBytes(-1);
        } else {
            builder.threadCpuNanos(threadInfo.threadCpuNanos());
            builder.threadBlockedNanos(threadInfo.threadBlockedNanos());
            builder.threadWaitedNanos(threadInfo.threadWaitedNanos());
            builder.threadAllocatedBytes(threadInfo.threadAllocatedBytes());
        }
        builder.gcActivity(transaction.getGcActivity());
        builder.entries(transaction.getEntries());
        builder.entryLimitExceeded(transaction.isEntryLimitExceeded());
        builder.syntheticRootProfileNode(transaction.getSyntheticRootProfileNode());
        builder.profileLimitExceeded(transaction.isProfileLimitExceeded());
        return builder.build();
    }

    // timings for traces that are still active are normalized to the capture tick in order to
    // *attempt* to present a picture of the trace at that exact tick
    // (without using synchronization to block updates to the trace while it is being read)
    private static TraceHeader createTraceHeader(Transaction transaction, boolean active,
            boolean partial, long captureTime, long captureTick) throws IOException {
        ImmutableTraceHeader.Builder builder = ImmutableTraceHeader.builder();
        builder.id(transaction.getId());
        builder.active(active);
        builder.partial(partial);
        ErrorMessage errorMessage = transaction.getErrorMessage();
        builder.error(errorMessage != null);
        builder.startTime(transaction.getStartTime());
        builder.captureTime(captureTime);
        builder.durationNanos(captureTick - transaction.getStartTick());
        builder.transactionType(transaction.getTransactionType());
        builder.transactionName(transaction.getTransactionName());
        builder.headline(transaction.getHeadline());
        builder.user(transaction.getUser());
        ImmutableSetMultimap<String, String> customAttributes = transaction.getCustomAttributes();
        builder.putAllCustomAttributes(customAttributes.asMap());
        builder.customDetail(cast(transaction.getCustomDetail()));
        if (errorMessage != null) {
            builder.errorMessage(errorMessage.message());
            builder.errorThrowable(errorMessage.throwable());
        }
        if (active) {
            builder.rootTimer(transaction.getRootTimer().getSnapshot());
        } else {
            builder.rootTimer(transaction.getRootTimer());
        }
        ThreadInfoData threadInfo = transaction.getThreadInfo();
        if (threadInfo != null) {
            builder.threadCpuNanos(threadInfo.threadCpuNanos());
            builder.threadBlockedNanos(threadInfo.threadBlockedNanos());
            builder.threadWaitedNanos(threadInfo.threadWaitedNanos());
            builder.threadAllocatedBytes(threadInfo.threadAllocatedBytes());
        }
        builder.gcActivity(transaction.getGcActivity());
        int entryCount = transaction.getEntryCount();
        long profileSampleCount = transaction.getProfileSampleCount();
        builder.entryCount(entryCount);
        builder.entryLimitExceeded(transaction.isEntryLimitExceeded());
        if (entryCount == 0) {
            builder.entriesExistence(Existence.NO);
        } else {
            builder.entriesExistence(Existence.YES);
        }
        builder.profileSampleCount(profileSampleCount);
        builder.profileLimitExceeded(transaction.isProfileLimitExceeded());
        if (transaction.getProfile() == null) {
            builder.profileExistence(Existence.NO);
        } else {
            builder.profileExistence(Existence.YES);
        }
        return builder.build();
    }

    @SuppressWarnings("return.type.incompatible")
    private static Map<String, ? extends Object> cast(
            Map<String, ? extends /*@Nullable*/Object> detail) {
        return detail;
    }
}
