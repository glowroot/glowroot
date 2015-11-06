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

import com.fasterxml.jackson.databind.ObjectMapper;
import org.immutables.value.Value;

import org.glowroot.common.live.LiveAggregateRepository;
import org.glowroot.common.live.LiveWeavingService;
import org.glowroot.common.util.ObjectMappers;
import org.glowroot.storage.repo.AggregateRepository;
import org.glowroot.storage.repo.GaugeValueRepository;
import org.glowroot.storage.repo.RepoAdmin;
import org.glowroot.storage.repo.TraceRepository;

import static com.google.common.base.Preconditions.checkNotNull;

@JsonService
class AdminJsonService {

    private static final String SERVER_ID = "";

    private static final ObjectMapper mapper = ObjectMappers.create();

    private final AggregateRepository aggregateRepository;
    private final TraceRepository traceRepository;
    private final GaugeValueRepository gaugeValueRepository;
    private final LiveAggregateRepository liveAggregateRepository;
    private final @Nullable LiveWeavingService liveWeavingService;
    private final RepoAdmin repoAdmin;

    AdminJsonService(AggregateRepository aggregateRepository, TraceRepository traceRepository,
            GaugeValueRepository gaugeValueRepository,
            LiveAggregateRepository liveAggregateRepository,
            @Nullable LiveWeavingService liveWeavingService, RepoAdmin repoAdmin) {
        this.aggregateRepository = aggregateRepository;
        this.traceRepository = traceRepository;
        this.gaugeValueRepository = gaugeValueRepository;
        this.liveAggregateRepository = liveAggregateRepository;
        this.liveWeavingService = liveWeavingService;
        this.repoAdmin = repoAdmin;
    }

    @POST("/backend/admin/delete-all-data")
    void deleteAllData(String content) throws Exception {
        String serverRollup =
                mapper.readValue(content, ImmutableRequestWithServerRollup.class).serverRollup();
        // clear in-memory aggregates first
        liveAggregateRepository.clearAll();
        // TODO optimize by just deleting and re-creating h2 db
        traceRepository.deleteAll(serverRollup);
        gaugeValueRepository.deleteAll(serverRollup);
        aggregateRepository.deleteAll(serverRollup);
        repoAdmin.defrag();
    }

    @POST("/backend/admin/reweave")
    String reweave() throws Exception {
        checkNotNull(liveWeavingService);
        int count = liveWeavingService.reweave(SERVER_ID);
        return "{\"classes\":" + count + "}";
    }

    @POST("/backend/admin/defrag-data")
    void defragData() throws Exception {
        repoAdmin.defrag();
    }

    @Value.Immutable
    interface RequestWithServerRollup {
        String serverRollup();
    }

    @Value.Immutable
    interface RequestWithServerId {
        String serverId();
    }
}
