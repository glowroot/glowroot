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
package org.glowroot.central.storage.postgres;

import java.util.UUID;

import org.glowroot.central.repo.PostgresCentralAdminModule;
import org.glowroot.common2.repo.CassandraProfile;
import org.glowroot.common2.repo.ImmutableTraceQuery;
import org.glowroot.common2.repo.TraceRepository.HeaderPlus;
import org.glowroot.common2.repo.TraceRepository.TraceQuery;
import org.glowroot.wire.api.model.TraceOuterClass.Trace;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers(disabledWithoutDocker = true)
@Tag("postgres-store")
public class PostgresTraceIT {

    @Container
    @SuppressWarnings("resource")
    public final PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>("postgres:16-alpine");

    private PostgresCentralAdminModule admin;
    private PostgresTraceDao traceDao;

    @BeforeEach
    public void before() throws Exception {
        admin = PostgresCentralAdminModule.create(postgres.getJdbcUrl(), postgres.getUsername(),
                postgres.getPassword(), 5, "");
        traceDao = admin.getTraceDao();
        traceDao.truncateAll();
    }

    @AfterEach
    public void after() {
        if (admin != null) {
            admin.close();
        }
    }

    @Test
    public void storeAndReadHeader() throws Exception {
        Trace.Header header = Trace.Header.newBuilder()
                .setPartial(false)
                .setSlow(true)
                .setStartTime(1)
                .setCaptureTime(11)
                .setDurationNanos(MILLISECONDS.toNanos(10))
                .setTransactionType("unit test")
                .setTransactionName("test transaction name")
                .setHeadline("test headline")
                .setUser("j")
                .setMainThreadRootTimer(Trace.Timer.newBuilder()
                        .setName("the top")
                        .setTotalNanos(123)
                        .setCount(1))
                .build();
        String traceId = UUID.randomUUID().toString();
        Trace trace = Trace.newBuilder().setId(traceId).setHeader(header).build();
        traceDao.store("one", trace).toCompletableFuture().join();

        HeaderPlus headerPlus =
                traceDao.readHeaderPlus("one", traceId).toCompletableFuture().join();
        assertThat(headerPlus).isNotNull();
        assertThat(headerPlus.header().getTransactionType()).isEqualTo("unit test");

        // no entries stored — expect null
        assertThat(traceDao.readEntries("one", traceId, CassandraProfile.web)
                .toCompletableFuture().join()).isNull();

        TraceQuery query = ImmutableTraceQuery.builder()
                .transactionType("unit test")
                .from(0)
                .to(100)
                .build();
        assertThat(traceDao.readSlowCount("one", query).toCompletableFuture().join())
                .isEqualTo(1L);

        // parent rollup id should also see the point
        Trace nested = Trace.newBuilder().setId(UUID.randomUUID().toString())
                .setHeader(header).build();
        traceDao.store("parent::child::", nested).toCompletableFuture().join();
        assertThat(traceDao.readSlowCount("parent::", query).toCompletableFuture().join())
                .isEqualTo(1L);
        assertThat(traceDao.readSlowCount("parent::child::", query).toCompletableFuture().join())
                .isEqualTo(1L);
    }
}
