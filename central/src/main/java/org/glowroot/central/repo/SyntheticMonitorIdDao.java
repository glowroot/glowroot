/*
 * Copyright 2018-2023 the original author or authors.
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
package org.glowroot.central.repo;

import com.datastax.oss.driver.api.core.cql.AsyncResultSet;
import com.datastax.oss.driver.api.core.cql.BoundStatement;
import com.datastax.oss.driver.api.core.cql.PreparedStatement;
import com.datastax.oss.driver.api.core.cql.Row;
import com.google.common.base.Function;
import org.glowroot.central.util.RateLimiter;
import org.glowroot.central.util.Session;
import org.glowroot.common.util.CaptureTimes;
import org.glowroot.common.util.Clock;
import org.glowroot.common2.repo.CassandraProfile;
import org.immutables.value.Value;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

import static com.google.common.base.Preconditions.checkNotNull;
import static java.util.concurrent.TimeUnit.DAYS;

class SyntheticMonitorIdDao {

    private final Session session;
    private final ConfigRepositoryImpl configRepository;
    private final Clock clock;

    private final PreparedStatement insertPS;
    private final PreparedStatement readPS;

    private final RateLimiter<SyntheticMonitorKey> rateLimiter = new RateLimiter<>();

    SyntheticMonitorIdDao(Session session, ConfigRepositoryImpl configRepository, Clock clock)
            throws Exception {
        this.session = session;
        this.configRepository = configRepository;
        this.clock = clock;

        int maxRollupHours = configRepository.getCentralStorageConfig().toCompletableFuture().join().getMaxRollupHours();
        session.createTableWithTWCS("create table if not exists synthetic_monitor_id"
                + " (agent_rollup_id varchar, capture_time timestamp, synthetic_monitor_id varchar,"
                + " synthetic_monitor_display varchar, primary key (agent_rollup_id, capture_time,"
                + " synthetic_monitor_id))", maxRollupHours);

        insertPS = session.prepare("insert into synthetic_monitor_id (agent_rollup_id,"
                + " capture_time, synthetic_monitor_id, synthetic_monitor_display) values (?, ?, ?,"
                + " ?) using ttl ?");
        readPS = session.prepare("select synthetic_monitor_id, synthetic_monitor_display from"
                + " synthetic_monitor_id where agent_rollup_id = ? and capture_time >= ? and"
                + " capture_time <= ?");
    }

    Map<String, String> getSyntheticMonitorIds(String agentRollupId, long from, long to)
            throws Exception {
        long rolledUpFrom = CaptureTimes.getRollup(from, DAYS.toMillis(1));
        long rolledUpTo = CaptureTimes.getRollup(to, DAYS.toMillis(1));
        BoundStatement boundStatement = readPS.bind()
                .setString(0, agentRollupId)
                .setInstant(1, Instant.ofEpochMilli(rolledUpFrom))
                .setInstant(2, Instant.ofEpochMilli(rolledUpTo));
        Map<String, String> syntheticMonitorIds = new HashMap<>();
        Function<AsyncResultSet, CompletableFuture<Map<String, String>>> compute = new Function<>() {

            @Override
            public CompletableFuture<Map<String, String>> apply(AsyncResultSet results) {
                for (Row row : results.currentPage()) {
                    syntheticMonitorIds.put(checkNotNull(row.getString(0)), checkNotNull(row.getString(1)));
                }
                if (results.hasMorePages()) {
                    return results.fetchNextPage().thenCompose(this::apply).toCompletableFuture();
                }
                return CompletableFuture.completedFuture(syntheticMonitorIds);
            }
        };
        return session.readAsync(boundStatement, CassandraProfile.web).thenCompose(compute).toCompletableFuture().get();
    }

    CompletionStage<?> insert(String agentRollupId, long captureTime, String syntheticMonitorId,
                              String syntheticMonitorDisplay) {
        long rollupCaptureTime = CaptureTimes.getRollup(captureTime, DAYS.toMillis(1));
        return configRepository.getCentralStorageConfig().thenCompose(centralStorageConfig -> {
            int i = 0;
            BoundStatement boundStatement = insertPS.bind()
                    .setString(i++, agentRollupId)
                    .setInstant(i++, Instant.ofEpochMilli(rollupCaptureTime))
                    .setString(i++, syntheticMonitorId)
                    .setString(i++, syntheticMonitorDisplay);
            int maxRollupTTL = centralStorageConfig.getMaxRollupTTL();
            boundStatement = boundStatement.setInt(i++, Common.getAdjustedTTL(maxRollupTTL, rollupCaptureTime, clock));
            return session.writeAsync(boundStatement, CassandraProfile.collector);
        });
    }

    @Value.Immutable
    interface SyntheticMonitorKey {
        String agentRollupId();

        long captureTime();

        String syntheticMonitorId();

        String syntheticMonitorDisplay();
    }
}
