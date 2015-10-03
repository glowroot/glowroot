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

import java.io.IOException;
import java.sql.SQLException;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.immutables.value.Value;

import org.glowroot.common.live.LiveAggregateRepository;
import org.glowroot.common.live.LiveTraceRepository;
import org.glowroot.common.live.LiveWeavingService;
import org.glowroot.common.util.ObjectMappers;
import org.glowroot.common.util.OnlyUsedByTests;
import org.glowroot.storage.repo.AggregateRepository;
import org.glowroot.storage.repo.ConfigRepository;
import org.glowroot.storage.repo.GaugeValueRepository;
import org.glowroot.storage.repo.RepoAdmin;
import org.glowroot.storage.repo.TraceRepository;

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
        String serverGroup =
                mapper.readValue(content, ImmutableRequestWithServerGroup.class).serverGroup();
        // clear in-memory aggregates first
        if (liveAggregateRepository != null) {
            liveAggregateRepository.clearAll();
        }
        // TODO optimize by just deleting and re-creating h2 db
        traceRepository.deleteAll(serverGroup);
        gaugeValueRepository.deleteAll(serverGroup);
        aggregateRepository.deleteAll(serverGroup);
        repoAdmin.defrag();
    }

    @POST("/backend/admin/reweave")
    String reweave(String content) throws Exception {
        String server = mapper.readValue(content, ImmutableRequestWithServer.class).server();
        int count = liveWeavingService.reweave(server);
        return "{\"classes\":" + count + "}";
    }

    @POST("/backend/admin/defrag-data")
    void defragData() throws SQLException {
        repoAdmin.defrag();
    }

    @OnlyUsedByTests
    @POST("/backend/admin/reset-all-config")
    void resetAllConfig(String content) throws IOException {
        String server = mapper.readValue(content, ImmutableRequestWithServer.class).server();
        configRepository.resetAllConfig(server);
    }

    @OnlyUsedByTests
    @GET("/backend/admin/num-active-transactions")
    String getNumActiveTransactions(String queryString) throws Exception {
        String server = getServer(queryString);
        return Integer.toString(liveTraceRepository.getTransactionCount(server));
    }

    @OnlyUsedByTests
    @GET("/backend/admin/num-pending-complete-transactions")
    String getNumPendingCompleteTransactions(String queryString) throws Exception {
        String server = getServer(queryString);
        return Integer.toString(liveTraceRepository.getPendingTransactionCount(server));
    }

    @OnlyUsedByTests
    @GET("/backend/admin/num-traces")
    String getNumTraces(String queryString) throws Exception {
        String server = getServer(queryString);
        return Long.toString(traceRepository.count(server));
    }

    private static String getServer(String queryString) throws Exception {
        return QueryStrings.decode(queryString, RequestWithServer.class).server();
    }

    @Value.Immutable
    interface RequestWithServerGroup {
        String serverGroup();
    }

    @Value.Immutable
    interface RequestWithServer {
        String server();
    }
}
