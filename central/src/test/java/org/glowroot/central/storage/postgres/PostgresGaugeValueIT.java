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

import java.util.List;

import com.google.common.collect.ImmutableList;
import org.glowroot.central.repo.PostgresCentralAdminModule;
import org.glowroot.common2.repo.CassandraProfile;
import org.glowroot.common2.repo.GaugeValueRepository.Gauge;
import org.glowroot.wire.api.model.CollectorServiceOuterClass.GaugeValueMessage.GaugeValue;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers(disabledWithoutDocker = true)
@Tag("postgres-store")
public class PostgresGaugeValueIT {

    @Container
    @SuppressWarnings("resource")
    public final PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>("postgres:16-alpine");

    private PostgresCentralAdminModule admin;
    private PostgresGaugeValueDao gaugeDao;

    @BeforeEach
    public void before() throws Exception {
        admin = PostgresCentralAdminModule.create(postgres.getJdbcUrl(), postgres.getUsername(),
                postgres.getPassword(), 5, "");
        gaugeDao = admin.getGaugeValueDao();
        gaugeDao.truncateAll();
    }

    @AfterEach
    public void after() {
        if (admin != null) {
            admin.close();
        }
    }

    @Test
    public void storeReadAndDiscoverGauges() throws Exception {
        String gaugeName = "java.lang:type=Memory:HeapMemoryUsage.used";
        long t1 = 60_000;
        long t2 = 120_000;
        gaugeDao.store("one", ImmutableList.of(
                GaugeValue.newBuilder().setGaugeName(gaugeName).setCaptureTime(t1)
                        .setValue(100).setWeight(1).build(),
                GaugeValue.newBuilder().setGaugeName(gaugeName).setCaptureTime(t2)
                        .setValue(200).setWeight(1).build()))
                .toCompletableFuture().join();

        List<Gauge> gauges = gaugeDao.getGauges("one", 0, 200_000, CassandraProfile.web)
                .toCompletableFuture().join();
        assertThat(gauges).extracting(Gauge::name).contains(gaugeName);

        List<GaugeValue> values = gaugeDao
                .readGaugeValues("one", gaugeName, 0, 200_000, 0, CassandraProfile.web)
                .toCompletableFuture().join();
        assertThat(values).hasSize(2);
        assertThat(values.get(0).getValue()).isEqualTo(100);

        List<GaugeValue> rolled = gaugeDao
                .readGaugeValues("one", gaugeName, 0, 200_000, 1, CassandraProfile.web)
                .toCompletableFuture().join();
        assertThat(rolled).isNotEmpty();

        assertThat(gaugeDao.getOldestCaptureTime("one", gaugeName, 0, CassandraProfile.web)
                .toCompletableFuture().join()).isEqualTo(t1);
    }
}
