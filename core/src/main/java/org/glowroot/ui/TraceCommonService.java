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
package org.glowroot.ui;

import javax.annotation.Nullable;

import com.google.common.io.CharSource;

import org.glowroot.common.live.ImmutableTraceExport;
import org.glowroot.common.live.LiveTraceRepository;
import org.glowroot.common.live.LiveTraceRepository.TraceExport;
import org.glowroot.common.repo.TraceRepository;
import org.glowroot.common.repo.TraceRepository.TraceHeader;
import org.glowroot.common.util.ChunkSource;

class TraceCommonService {

    private final TraceRepository traceRepository;
    private final LiveTraceRepository liveTraceRepository;

    TraceCommonService(TraceRepository traceRepository, LiveTraceRepository liveTraceRepository) {
        this.traceRepository = traceRepository;
        this.liveTraceRepository = liveTraceRepository;
    }

    @Nullable
    TraceHeader getTraceHeader(String traceId) throws Exception {
        // check active/pending traces first, and lastly stored traces to make sure that the trace
        // is not missed if it is in transition between these states
        TraceHeader traceHeader = liveTraceRepository.getTraceHeader(traceId);
        if (traceHeader != null) {
            return traceHeader;
        }
        return traceRepository.readTraceHeader(traceId);
    }

    // overwritten entries will return {"overwritten":true}
    // expired (not found) trace will return {"expired":true}
    @Nullable
    ChunkSource getEntries(String traceId) throws Exception {
        // check active/pending traces first, and lastly stored traces to make sure that the trace
        // is not missed if it is in transition between these states
        ChunkSource traceEntries = liveTraceRepository.getTraceEntries(traceId);
        if (traceEntries != null) {
            return traceEntries;
        }
        return toNullableChunkSource(traceRepository.readEntries(traceId));
    }

    // overwritten profile will return {"overwritten":true}
    // expired (not found) trace will return {"expired":true}
    @Nullable
    ChunkSource getProfile(String traceId) throws Exception {
        // check active/pending traces first, and lastly stored traces to make sure that the trace
        // is not missed if it is in transition between these states
        ChunkSource traceProfile = liveTraceRepository.getTraceProfile(traceId);
        if (traceProfile != null) {
            return traceProfile;
        }
        return toNullableChunkSource(traceRepository.readProfile(traceId));
    }

    @Nullable
    TraceExport getExport(String traceId) throws Exception {
        // check active/pending traces first, and lastly stored traces to make sure that the trace
        // is not missed if it is in transition between these states
        TraceExport traceExport = liveTraceRepository.getTraceExport(traceId);
        if (traceExport != null) {
            return traceExport;
        }

        TraceHeader trace = traceRepository.readTraceHeader(traceId);
        if (trace == null) {
            return null;
        }
        return ImmutableTraceExport.builder()
                .traceHeader(trace)
                .entries(toNullableChunkSource(traceRepository.readEntries(traceId)))
                .profile(toNullableChunkSource(traceRepository.readProfile(traceId)))
                .build();
    }

    private @Nullable ChunkSource toNullableChunkSource(@Nullable CharSource charSource) {
        if (charSource == null) {
            return null;
        }
        return ChunkSource.from(charSource);
    }
}
