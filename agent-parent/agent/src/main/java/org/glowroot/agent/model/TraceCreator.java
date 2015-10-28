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
import java.util.Collection;
import java.util.List;
import java.util.Map.Entry;

import org.glowroot.common.util.NotAvailableAware;
import org.glowroot.common.util.Styles;
import org.glowroot.wire.api.model.ProfileTreeOuterClass.ProfileTree;
import org.glowroot.wire.api.model.TraceOuterClass.Trace;

@Styles.Private
public class TraceCreator {

    private TraceCreator() {}

    public static Trace createPartialTrace(Transaction transaction, long captureTime,
            long captureTick) throws IOException {
        return createFullTrace(transaction, true, true, captureTime, captureTick);
    }

    public static Trace createCompletedTrace(Transaction transaction, boolean slow)
            throws IOException {
        return createFullTrace(transaction, slow, false, transaction.getCaptureTime(),
                transaction.getEndTick());
    }

    public static Trace.Header createPartialTraceHeader(Transaction transaction, long captureTime,
            long captureTick) throws IOException {
        // only slow transactions reach this point, so setting slow=true (second arg below)
        return createTraceHeader(transaction, true, true, captureTime, captureTick);
    }

    public static Trace.Header createCompletedTraceHeader(Transaction transaction)
            throws IOException {
        // only slow transactions reach this point, so setting slow=true (second arg below)
        return createTraceHeader(transaction, true, false, transaction.getCaptureTime(),
                transaction.getEndTick());
    }

    // timings for traces that are still active are normalized to the capture tick in order to
    // *attempt* to present a picture of the trace at that exact tick
    // (without using synchronization to block updates to the trace while it is being read)
    private static Trace createFullTrace(Transaction transaction, boolean slow, boolean partial,
            long captureTime, long captureTick) throws IOException {
        Trace.Header header =
                createTraceHeader(transaction, slow, partial, captureTime, captureTick);
        List<Trace.Entry> entries = transaction.getEntriesProtobuf();
        ProfileTree profileTree = transaction.getProfileTreeProtobuf();
        Trace.Builder builder = Trace.newBuilder()
                .setId(transaction.getId())
                .setHeader(header)
                .addAllEntry(entries);
        if (profileTree != null) {
            builder.setProfileTree(profileTree);
        }
        return builder.build();
    }

    private static Trace.Header createTraceHeader(Transaction transaction, boolean slow,
            boolean partial, long captureTime, long captureTick) throws IOException {
        Trace.Header.Builder builder = Trace.Header.newBuilder();
        builder.setPartial(partial);
        builder.setSlow(slow);
        ErrorMessage errorMessage = transaction.getErrorMessage();
        builder.setStartTime(transaction.getStartTime());
        builder.setCaptureTime(captureTime);
        builder.setDurationNanos(captureTick - transaction.getStartTick());
        builder.setTransactionType(transaction.getTransactionType());
        builder.setTransactionName(transaction.getTransactionName());
        builder.setHeadline(transaction.getHeadline());
        builder.setUser(transaction.getUser());
        for (Entry<String, Collection<String>> entry : transaction.getAttributes().asMap()
                .entrySet()) {
            builder.addAttributeBuilder()
                    .setName(entry.getKey())
                    .addAllValue(entry.getValue());
        }
        builder.addAllDetailEntry(DetailMapWriter.toProtobufDetail(transaction.getDetail()));
        if (errorMessage != null) {
            Trace.Error.Builder errorBuilder = builder.getErrorBuilder();
            errorBuilder.setMessage(errorMessage.message());
            Trace.Throwable throwable = errorMessage.throwable();
            if (throwable != null) {
                errorBuilder.setException(throwable);
            }
            errorBuilder.build();
        }
        builder.setRootTimer(transaction.getRootTimer().toProtobuf());
        ThreadInfoData threadInfo = transaction.getThreadInfo();
        if (threadInfo != null) {
            if (threadInfo.threadCpuNanos() != NotAvailableAware.NA) {
                builder.setThreadCpuNanos(getOptionalInt(threadInfo.threadCpuNanos()));
            }
            if (threadInfo.threadBlockedNanos() != NotAvailableAware.NA) {
                builder.setThreadBlockedNanos(getOptionalInt(threadInfo.threadBlockedNanos()));
            }
            if (threadInfo.threadWaitedNanos() != NotAvailableAware.NA) {
                builder.setThreadWaitedNanos(getOptionalInt(threadInfo.threadWaitedNanos()));
            }
            if (threadInfo.threadAllocatedBytes() != NotAvailableAware.NA) {
                builder.setThreadAllocatedBytes(getOptionalInt(threadInfo.threadAllocatedBytes()));
            }
        }
        builder.addAllGcActivity(transaction.getGcActivity());
        builder.setEntryCount(transaction.getEntryCount());
        builder.setEntryLimitExceeded(transaction.isEntryLimitExceeded());
        builder.setProfileSampleCount(transaction.getProfileSampleCount());
        builder.setProfileSampleLimitExceeded(transaction.isProfileSampleLimitExceeded());
        return builder.build();
    }

    private static Trace.OptionalInt getOptionalInt(long value) {
        return Trace.OptionalInt.newBuilder().setValue(value).build();
    }
}
