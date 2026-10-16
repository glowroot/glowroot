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

import java.util.ArrayList;
import java.util.List;

import com.google.common.collect.ImmutableList;
import org.glowroot.central.repo.PostgresCentralAdminModule;
import org.glowroot.common.live.ImmutableAggregateQuery;
import org.glowroot.common.live.ImmutableSummaryQuery;
import org.glowroot.common.live.LiveAggregateRepository.AggregateQuery;
import org.glowroot.common.live.LiveAggregateRepository.OverviewAggregate;
import org.glowroot.common.live.LiveAggregateRepository.SummaryQuery;
import org.glowroot.common.model.OverallSummaryCollector;
import org.glowroot.common.model.OverallSummaryCollector.OverallSummary;
import org.glowroot.common.model.TransactionNameSummaryCollector;
import org.glowroot.common.model.TransactionNameSummaryCollector.SummarySortOrder;
import org.glowroot.common.model.TransactionNameSummaryCollector.TransactionNameSummary;
import org.glowroot.common2.repo.CassandraProfile;
import org.glowroot.wire.api.model.AgentConfigOuterClass.AgentConfig;
import org.glowroot.wire.api.model.AggregateOuterClass.Aggregate;
import org.glowroot.wire.api.model.AggregateOuterClass.OldAggregatesByType;
import org.glowroot.wire.api.model.AggregateOuterClass.OldTransactionAggregate;
import org.glowroot.wire.api.model.Proto.OptionalInt64;
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
public class PostgresAggregateIT {

    @Container
    @SuppressWarnings("resource")
    public final PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>("postgres:16-alpine");

    private PostgresCentralAdminModule admin;
    private PostgresAggregateDao aggregateDao;

    @BeforeEach
    public void before() throws Exception {
        admin = PostgresCentralAdminModule.create(postgres.getJdbcUrl(), postgres.getUsername(),
                postgres.getPassword(), 5, "");
        aggregateDao = admin.getAggregateDao();
        admin.getAgentConfigDao().store("one", AgentConfig.getDefaultInstance(), true)
                .toCompletableFuture().join();
        aggregateDao.truncateAll();
    }

    @AfterEach
    public void after() {
        if (admin != null) {
            admin.close();
        }
    }

    @Test
    public void storeAndRollupSummaries() throws Exception {
        List<Aggregate.SharedQueryText> sharedQueryText = ImmutableList
                .of(Aggregate.SharedQueryText.newBuilder().setFullText("select 1").build());
        aggregateDao.store("one", 60000, createData(), sharedQueryText).toCompletableFuture()
                .join();
        aggregateDao.store("one", 120000, createData(), sharedQueryText).toCompletableFuture()
                .join();
        aggregateDao.store("one", 360000, createData(), sharedQueryText).toCompletableFuture()
                .join();

        SummaryQuery summaryQuery = ImmutableSummaryQuery.builder()
                .transactionType("tt1")
                .from(0)
                .to(300000)
                .rollupLevel(0)
                .build();
        AggregateQuery aggregateQuery = ImmutableAggregateQuery.builder()
                .transactionType("tt1")
                .from(0)
                .to(300000)
                .rollupLevel(0)
                .build();

        OverallSummaryCollector overallSummaryCollector = new OverallSummaryCollector();
        aggregateDao.mergeOverallSummaryInto("one", summaryQuery, overallSummaryCollector,
                CassandraProfile.web).toCompletableFuture().join();
        OverallSummary overallSummary = overallSummaryCollector.getOverallSummary();
        assertThat(overallSummary.totalDurationNanos()).isEqualTo(3579 * 2);
        assertThat(overallSummary.transactionCount()).isEqualTo(6);

        TransactionNameSummaryCollector tnCollector = new TransactionNameSummaryCollector();
        aggregateDao.mergeTransactionNameSummariesInto("one", summaryQuery,
                SummarySortOrder.TOTAL_TIME, 10, tnCollector, CassandraProfile.web)
                .toCompletableFuture().join();
        List<TransactionNameSummary> names =
                tnCollector.getResult(SummarySortOrder.TOTAL_TIME, 10).records();
        assertThat(names).hasSize(2);
        assertThat(names.get(0).transactionName()).isEqualTo("tn2");

        List<OverviewAggregate> overview = aggregateDao
                .readOverviewAggregates("one", aggregateQuery, CassandraProfile.web)
                .toCompletableFuture().join();
        assertThat(overview).hasSize(2);
        assertThat(overview.get(0).transactionCount()).isEqualTo(3);

        aggregateDao.rollup("one").toCompletableFuture().join();

        SummaryQuery rolled = ImmutableSummaryQuery.builder()
                .copyFrom(summaryQuery)
                .rollupLevel(1)
                .build();
        OverallSummaryCollector rolledCollector = new OverallSummaryCollector();
        aggregateDao.mergeOverallSummaryInto("one", rolled, rolledCollector, CassandraProfile.web)
                .toCompletableFuture().join();
        OverallSummary rolledSummary = rolledCollector.getOverallSummary();
        assertThat(rolledSummary.transactionCount()).isEqualTo(6);
        assertThat(rolledSummary.totalDurationNanos()).isEqualTo(3579 * 2);
    }

    @Test
    public void v09RoundTrip() throws Exception {
        admin.getV09AgentRollupDao().store("old-a", "old-a/");
        assertThat(admin.getV09AgentRollupDao().getV09AgentRollupId("old-a")).isEqualTo("old-a/");
        assertThat(admin.getV09AgentRollupDao().getV09AgentRollupId("missing")).isNull();
    }

    private static List<OldAggregatesByType> createData() {
        List<OldAggregatesByType> aggregatesByType = new ArrayList<>();
        aggregatesByType.add(OldAggregatesByType.newBuilder()
                .setTransactionType("tt1")
                .setOverallAggregate(createOverallAggregate())
                .addTransactionAggregate(createTransactionAggregate1())
                .addTransactionAggregate(createTransactionAggregate2())
                .build());
        return aggregatesByType;
    }

    private static Aggregate createOverallAggregate() {
        return Aggregate.newBuilder()
                .setTotalDurationNanos(3579)
                .setTransactionCount(3)
                .setErrorCount(1)
                .addMainThreadRootTimer(Aggregate.Timer.newBuilder()
                        .setName("abc")
                        .setTotalNanos(333)
                        .setCount(3))
                .setAuxThreadRootTimer(Aggregate.Timer.newBuilder()
                        .setName("auxiliary thread")
                        .setTotalNanos(666)
                        .setCount(3))
                .addAsyncTimer(Aggregate.Timer.newBuilder()
                        .setName("mnm")
                        .setTotalNanos(999)
                        .setCount(3))
                .addQuery(Aggregate.Query.newBuilder()
                        .setType("sqlo")
                        .setSharedQueryTextIndex(0)
                        .setTotalDurationNanos(7)
                        .setTotalRows(OptionalInt64.newBuilder().setValue(5))
                        .setExecutionCount(2))
                .build();
    }

    private static OldTransactionAggregate createTransactionAggregate1() {
        return OldTransactionAggregate.newBuilder()
                .setTransactionName("tn1")
                .setAggregate(Aggregate.newBuilder()
                        .setTotalDurationNanos(1234)
                        .setTransactionCount(1)
                        .addMainThreadRootTimer(Aggregate.Timer.newBuilder()
                                .setName("abc")
                                .setTotalNanos(111)
                                .setCount(1))
                        .build())
                .build();
    }

    private static OldTransactionAggregate createTransactionAggregate2() {
        return OldTransactionAggregate.newBuilder()
                .setTransactionName("tn2")
                .setAggregate(Aggregate.newBuilder()
                        .setTotalDurationNanos(2345)
                        .setTransactionCount(2)
                        .setErrorCount(1)
                        .addMainThreadRootTimer(Aggregate.Timer.newBuilder()
                                .setName("abc")
                                .setTotalNanos(222)
                                .setCount(2))
                        .build())
                .build();
    }
}
