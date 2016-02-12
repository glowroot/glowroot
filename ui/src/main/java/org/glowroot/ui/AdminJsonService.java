/*
 * Copyright 2012-2016 the original author or authors.
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

import org.glowroot.common.live.LiveWeavingService;
import org.glowroot.common.util.ObjectMappers;
import org.glowroot.storage.repo.AggregateRepository;
import org.glowroot.storage.repo.GaugeValueRepository;
import org.glowroot.storage.repo.RepoAdmin;
import org.glowroot.storage.repo.TraceRepository;
import org.glowroot.storage.repo.TransactionTypeRepository;

import static com.google.common.base.Preconditions.checkNotNull;

@JsonService
class AdminJsonService {

    private static final ObjectMapper mapper = ObjectMappers.create();

    private final AggregateRepository aggregateRepository;
    private final TraceRepository traceRepository;
    private final TransactionTypeRepository transactionTypeRepository;
    private final GaugeValueRepository gaugeValueRepository;
    private final @Nullable LiveWeavingService liveWeavingService;
    private final RepoAdmin repoAdmin;

    AdminJsonService(AggregateRepository aggregateRepository, TraceRepository traceRepository,
            TransactionTypeRepository transactionTypeRepository,
            GaugeValueRepository gaugeValueRepository,
            @Nullable LiveWeavingService liveWeavingService, RepoAdmin repoAdmin) {
        this.aggregateRepository = aggregateRepository;
        this.traceRepository = traceRepository;
        this.transactionTypeRepository = transactionTypeRepository;
        this.gaugeValueRepository = gaugeValueRepository;
        this.liveWeavingService = liveWeavingService;
        this.repoAdmin = repoAdmin;
    }

    @POST("/backend/admin/delete-all-data")
    void deleteAllData(String content) throws Exception {
        String agentRollup =
                mapper.readValue(content, ImmutableRequestWithAgentRollup.class).agentRollup();
        // TODO optimize by just deleting and re-creating h2 db
        traceRepository.deleteAll(agentRollup);
        aggregateRepository.deleteAll(agentRollup);
        transactionTypeRepository.deleteAll(agentRollup);
        gaugeValueRepository.deleteAll(agentRollup);
        repoAdmin.defrag();
    }

    @POST("/backend/admin/reweave")
    String reweave(String content) throws Exception {
        checkNotNull(liveWeavingService);
        String agentId = mapper.readValue(content, ImmutableRequestWithAgentId.class).agentId();
        int count = liveWeavingService.reweave(agentId);
        return "{\"classes\":" + count + "}";
    }

    @POST("/backend/admin/defrag-data")
    void defragData() throws Exception {
        repoAdmin.defrag();
    }

    @Value.Immutable
    interface RequestWithAgentRollup {
        String agentRollup();
    }

    @Value.Immutable
    interface RequestWithAgentId {
        String agentId();
    }
}
