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
package org.glowroot.server.ui;

import java.io.IOException;
import java.sql.SQLException;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.immutables.value.Value;

import org.glowroot.common.util.ObjectMappers;
import org.glowroot.common.util.OnlyUsedByTests;
import org.glowroot.live.LiveAggregateRepository;
import org.glowroot.live.LiveTraceRepository;
import org.glowroot.live.LiveWeavingService;
import org.glowroot.server.repo.AggregateRepository;
import org.glowroot.server.repo.ConfigRepository;
import org.glowroot.server.repo.GaugeValueRepository;
import org.glowroot.server.repo.RepoAdmin;
import org.glowroot.server.repo.TraceRepository;

@JsonService
class AdminJsonService {

    private static final ObjectMapper mapper = ObjectMappers.create();

    private final AggregateRepository aggregateRepository;
    private final TraceRepository traceRepository;
    private final GaugeValueRepository gaugeValueRepository;
    private final LiveAggregateRepository liveAggregateRepository;
    private final ConfigRepository configRepository;
    private final LiveWeavingService liveWeavingService;
    private final LiveTraceRepository liveTraceRepository;
    private final RepoAdmin repoAdmin;

    AdminJsonService(AggregateRepository aggregateRepository, TraceRepository traceRepository,
            GaugeValueRepository gaugeValueRepository,
            LiveAggregateRepository liveAggregateRepository, ConfigRepository configRepository,
            LiveWeavingService liveWeavingService, LiveTraceRepository liveTraceRepository,
            RepoAdmin repoAdmin) {
        this.aggregateRepository = aggregateRepository;
        this.traceRepository = traceRepository;
        this.gaugeValueRepository = gaugeValueRepository;
        this.liveAggregateRepository = liveAggregateRepository;
        this.configRepository = configRepository;
        this.liveWeavingService = liveWeavingService;
        this.liveTraceRepository = liveTraceRepository;
        this.repoAdmin = repoAdmin;
    }

    @POST("/backend/admin/delete-all-data")
    void deleteAllData(String content) throws Exception {
        long serverId = mapper.readValue(content, ImmutableRequestWithServerId.class).serverId();
        // clear in-memory aggregates first
        if (liveAggregateRepository != null) {
            liveAggregateRepository.clearAll();
        }
        // TODO optimize by just deleting and re-creating h2 db
        traceRepository.deleteAll(serverId);
        gaugeValueRepository.deleteAll(serverId);
        aggregateRepository.deleteAll(serverId);
        repoAdmin.defrag();
    }

    @POST("/backend/admin/reweave")
    String reweave(String content) throws Exception {
        long serverId = mapper.readValue(content, ImmutableRequestWithServerId.class).serverId();
        int count = liveWeavingService.reweave(serverId);
        return "{\"classes\":" + count + "}";
    }

    @POST("/backend/admin/defrag-data")
    void defragData() throws SQLException {
        repoAdmin.defrag();
    }

    @OnlyUsedByTests
    @POST("/backend/admin/reset-all-config")
    void resetAllConfig(String content) throws IOException {
        long serverId = mapper.readValue(content, ImmutableRequestWithServerId.class).serverId();
        configRepository.resetAllConfig(serverId);
    }

    @OnlyUsedByTests
    @GET("/backend/admin/num-active-transactions")
    String getNumActiveTransactions(String queryString) throws Exception {
        long serverId = getServerId(queryString);
        return Integer.toString(liveTraceRepository.getTransactionCount(serverId));
    }

    @OnlyUsedByTests
    @GET("/backend/admin/num-pending-complete-transactions")
    String getNumPendingCompleteTransactions(String queryString) throws Exception {
        long serverId = getServerId(queryString);
        return Integer.toString(liveTraceRepository.getPendingTransactionCount(serverId));
    }

    @OnlyUsedByTests
    @GET("/backend/admin/num-traces")
    String getNumTraces(String queryString) throws Exception {
        long serverId = getServerId(queryString);
        return Long.toString(traceRepository.count(serverId));
    }

    private static long getServerId(String queryString) throws Exception {
        return QueryStrings.decode(queryString, RequestWithServerId.class).serverId();
    }

    @Value.Immutable
    interface RequestWithServerId {
        long serverId();
    }
}
