/*
 * Copyright 2026 the original author or authors.
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

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import com.datastax.oss.driver.api.core.cql.AsyncResultSet;
import com.datastax.oss.driver.api.core.cql.BoundStatement;
import com.datastax.oss.driver.api.core.cql.PreparedStatement;
import com.datastax.oss.driver.api.core.cql.Row;
import com.datastax.oss.driver.api.core.uuid.Uuids;
import com.google.common.collect.ImmutableList;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import org.glowroot.central.util.Session;
import org.glowroot.common.util.Clock;
import org.glowroot.common2.config.ImmutableCentralStorageConfig;
import org.glowroot.common2.repo.CassandraProfile;
import org.glowroot.common2.repo.ConfigRepository.RollupConfig;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class SyntheticResultDaoTest {

    @Test
    public void rollupContinuesNewerIntervalsWhenOlderIntervalFails() throws Exception {
        Session session = mock(Session.class);
        ConfigRepositoryImpl configRepository = mock(ConfigRepositoryImpl.class);
        when(configRepository.getRollupConfigs())
                .thenReturn(ImmutableList.copyOf(RollupConfig.buildRollupConfigs()));
        when(configRepository.getCentralStorageConfig()).thenReturn(
                CompletableFuture.completedFuture(ImmutableCentralStorageConfig.builder().build()));

        Map<PreparedStatement, String> sqlByPS = new HashMap<>();
        when(session.prepare(anyString())).thenAnswer(invocation -> {
            String sql = invocation.getArgument(0);
            PreparedStatement ps = mock(PreparedStatement.class);
            sqlByPS.put(ps, sql);
            when(ps.bind()).thenAnswer(bindInvocation -> mock(BoundStatement.class, bsInvocation -> {
                if ("getPreparedStatement".equals(bsInvocation.getMethod().getName())) {
                    return ps;
                }
                if (bsInvocation.getMethod().getReturnType()
                        .isAssignableFrom(BoundStatement.class)) {
                    return bsInvocation.getMock();
                }
                return Mockito.RETURNS_DEFAULTS.answer(bsInvocation);
            }));
            return ps;
        });

        UUID uuid1 = Uuids.timeBased();
        UUID uuid2 = Uuids.timeBased();
        Row needsRow1 = mockNeedsRollupRow(300_000L, uuid1, Set.of("synth1"));
        Row needsRow2 = mockNeedsRollupRow(600_000L, uuid2, Set.of("synth1"));
        AsyncResultSet needsRollup1Results = resultSet(List.of(needsRow1, needsRow2));
        AsyncResultSet emptyResults = resultSet(Collections.emptyList());

        when(session.readAsync(any(BoundStatement.class), eq(CassandraProfile.rollup)))
                .thenAnswer(invocation -> {
                    BoundStatement bs = invocation.getArgument(0);
                    String sql = sqlByPS.get(bs.getPreparedStatement());
                    if (sql != null && sql.contains("synthetic_needs_rollup_1")) {
                        return CompletableFuture.completedFuture(needsRollup1Results);
                    }
                    return CompletableFuture.completedFuture(emptyResults);
                });

        Row resultRow = mock(Row.class);
        when(resultRow.getDouble(0)).thenReturn(100.0);
        when(resultRow.getLong(1)).thenReturn(2L);
        when(resultRow.getByteBuffer(2)).thenReturn(null);
        AsyncResultSet secondIntervalResults = resultSet(List.of(resultRow));

        when(session.readAsyncWarnIfNoRows(any(BoundStatement.class), eq(CassandraProfile.rollup),
                anyString(), any(), any(), any(), any(), any()))
                .thenReturn(CompletableFuture.failedFuture(new RuntimeException("simulated timeout")))
                .thenReturn(CompletableFuture.completedFuture(secondIntervalResults));

        when(session.writeAsync(any(BoundStatement.class), eq(CassandraProfile.rollup)))
                .thenReturn(CompletableFuture.completedFuture(emptyResults));

        Clock clock = new Clock() {
            @Override
            public long currentTimeMillis() {
                return 1_000_000L;
            }
        };

        SyntheticResultDaoImpl dao = new SyntheticResultDaoImpl(session, configRepository,
                Runnable::run, 0, clock);

        dao.rollup("agent1").toCompletableFuture().get(2, TimeUnit.SECONDS);

        verify(session, times(2)).readAsyncWarnIfNoRows(any(BoundStatement.class),
                eq(CassandraProfile.rollup), anyString(), eq("agent1"), eq("synth1"),
                any(), any(), eq(1));

        ArgumentCaptor<BoundStatement> writeCaptor = ArgumentCaptor.forClass(BoundStatement.class);
        verify(session, times(3)).writeAsync(writeCaptor.capture(), eq(CassandraProfile.rollup));

        List<String> writtenSqls = new ArrayList<>();
        BoundStatement deleteNeedsRollup1BS = null;
        for (BoundStatement written : writeCaptor.getAllValues()) {
            String sql = sqlByPS.get(written.getPreparedStatement());
            writtenSqls.add(sql);
            if (sql != null && sql.startsWith("delete from synthetic_needs_rollup_1")) {
                deleteNeedsRollup1BS = written;
            }
        }
        assertThat(writtenSqls).anyMatch(sql -> sql.startsWith("insert into synthetic_result_rollup_1"));
        assertThat(writtenSqls).anyMatch(sql -> sql.startsWith("insert into synthetic_needs_rollup_2"));
        assertThat(deleteNeedsRollup1BS).isNotNull();
        verify(deleteNeedsRollup1BS).setInstant(1, Instant.ofEpochMilli(600_000L));
        verify(deleteNeedsRollup1BS).setUuid(2, uuid2);
    }

    private static Row mockNeedsRollupRow(long captureTime, UUID uniqueness, Set<String> keys) {
        Row row = mock(Row.class);
        when(row.getInstant(0)).thenReturn(Instant.ofEpochMilli(captureTime));
        when(row.getUuid(1)).thenReturn(uniqueness);
        when(row.getSet(2, String.class)).thenReturn(keys);
        return row;
    }

    private static AsyncResultSet resultSet(List<Row> rows) {
        AsyncResultSet resultSet = mock(AsyncResultSet.class);
        when(resultSet.currentPage()).thenReturn(rows);
        when(resultSet.hasMorePages()).thenReturn(false);
        return resultSet;
    }
}
