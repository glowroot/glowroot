/**
 * Copyright 2012-2013 the original author or authors.
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
package io.informant.local.ui;

import io.informant.core.trace.Trace;
import io.informant.core.trace.TraceRegistry;
import io.informant.core.util.ByteStream;
import io.informant.local.trace.TraceSnapshot;
import io.informant.local.trace.TraceSnapshotDao;
import io.informant.local.trace.TraceSnapshotWriter;
import io.informant.local.trace.TraceWriter;

import java.io.IOException;

import checkers.nullness.quals.Nullable;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Ticker;
import com.google.inject.Inject;
import com.google.inject.Singleton;

/**
 * @author Trask Stalnaker
 * @since 0.5
 */
@VisibleForTesting
@Singleton
public class TraceCommonService {

    private final TraceSnapshotDao traceSnapshotDao;
    private final TraceRegistry traceRegistry;
    private final Ticker ticker;

    @Inject
    TraceCommonService(TraceSnapshotDao traceSnapshotDao,
            TraceRegistry traceRegistry, Ticker ticker) {
        this.traceSnapshotDao = traceSnapshotDao;
        this.traceRegistry = traceRegistry;
        this.ticker = ticker;
    }

    @Nullable
    ByteStream getSnapshotOrActiveJson(String id, boolean summary) throws IOException {
        // check active traces first to make sure that the trace is not missed if it should complete
        // after checking stored traces but before checking active traces
        for (Trace active : traceRegistry.getTraces()) {
            if (active.getId().equals(id)) {
                TraceSnapshot snapshot = TraceWriter.toTraceSnapshot(active, ticker.read(),
                        summary);
                return TraceSnapshotWriter.toByteStream(snapshot, true);
            }
        }
        TraceSnapshot snapshot;
        if (summary) {
            snapshot = traceSnapshotDao.readSnapshotWithoutDetail(id);
        } else {
            snapshot = traceSnapshotDao.readSnapshot(id);
        }
        if (snapshot == null) {
            return null;
        } else {
            return TraceSnapshotWriter.toByteStream(snapshot, false);
        }
    }
}
