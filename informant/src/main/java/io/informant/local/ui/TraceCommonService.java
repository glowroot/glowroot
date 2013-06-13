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

import java.io.IOException;

import checkers.igj.quals.ReadOnly;
import checkers.nullness.quals.Nullable;
import com.google.common.base.Ticker;
import com.google.common.io.CharSource;

import io.informant.collector.Snapshot;
import io.informant.collector.SnapshotCreator;
import io.informant.collector.SnapshotWriter;
import io.informant.common.Clock;
import io.informant.local.store.SnapshotDao;
import io.informant.markers.Singleton;
import io.informant.trace.TraceRegistry;
import io.informant.trace.model.Trace;

/**
 * @author Trask Stalnaker
 * @since 0.5
 */
@Singleton
class TraceCommonService {

    private final SnapshotDao snapshotDao;
    private final TraceRegistry traceRegistry;
    private final Clock clock;
    private final Ticker ticker;

    TraceCommonService(SnapshotDao snapshotDao, TraceRegistry traceRegistry, Clock clock,
            Ticker ticker) {
        this.snapshotDao = snapshotDao;
        this.traceRegistry = traceRegistry;
        this.clock = clock;
        this.ticker = ticker;
    }

    @ReadOnly
    @Nullable
    CharSource createCharSourceForSnapshotOrActiveTrace(String id, boolean summary)
            throws IOException {
        // check active traces first to make sure that the trace is not missed if it should complete
        // after checking stored traces but before checking active traces
        for (Trace active : traceRegistry.getTraces()) {
            if (active.getId().equals(id)) {
                Snapshot snapshot = SnapshotCreator.createActiveSnapshot(active,
                        clock.currentTimeMillis(), ticker.read(), summary);
                return SnapshotWriter.toCharSource(snapshot, true);
            }
        }
        Snapshot snapshot;
        if (summary) {
            snapshot = snapshotDao.readSnapshotWithoutDetail(id);
        } else {
            snapshot = snapshotDao.readSnapshot(id);
        }
        if (snapshot == null) {
            return null;
        } else {
            return SnapshotWriter.toCharSource(snapshot, false);
        }
    }
}
