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
    void deleteAllData() throws SQLException {
        // clear in-memory aggregates first
        if (liveAggregateRepository != null) {
            liveAggregateRepository.clearAll();
        }
        // TODO optimize by just deleting and re-creating h2 db
        traceRepository.deleteAll();
        gaugeValueRepository.deleteAll();
        aggregateRepository.deleteAll();
        repoAdmin.defrag();
    }

    @POST("/backend/admin/reweave")
    String reweave() throws Exception {
        int count = liveWeavingService.reweave();
        return "{\"classes\":" + count + "}";
    }

    @POST("/backend/admin/defrag-data")
    void defragData() throws SQLException {
        repoAdmin.defrag();
    }

    @OnlyUsedByTests
    @POST("/backend/admin/reset-all-config")
    void resetAllConfig() throws IOException {
        configRepository.resetAllConfig();
    }

    @OnlyUsedByTests
    @GET("/backend/admin/num-active-transactions")
    String getNumActiveTransactions() {
        return Integer.toString(liveTraceRepository.getTransactionCount());
    }

    @OnlyUsedByTests
    @GET("/backend/admin/num-pending-complete-transactions")
    String getNumPendingCompleteTransactions() {
        return Integer.toString(liveTraceRepository.getPendingTransactionCount());
    }

    @OnlyUsedByTests
    @GET("/backend/admin/num-traces")
    String getNumTraces() throws Exception {
        return Long.toString(traceRepository.count());
    }
}
