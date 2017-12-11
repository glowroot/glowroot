/*
 * Copyright 2015-2017 the original author or authors.
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
package org.glowroot.agent.embedded.repo;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import org.checkerframework.checker.tainting.qual.Untainted;

import org.glowroot.agent.embedded.init.ConfigRepositoryImpl;
import org.glowroot.agent.embedded.util.CappedDatabase;
import org.glowroot.agent.embedded.util.DataSource;
import org.glowroot.agent.embedded.util.DataSource.JdbcQuery;
import org.glowroot.common.repo.ImmutableTraceTable;
import org.glowroot.common.repo.RepoAdmin;
import org.glowroot.common.util.Clock;
import org.glowroot.wire.api.model.CollectorServiceOuterClass.Environment;

import static java.util.concurrent.TimeUnit.DAYS;
import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static org.glowroot.agent.util.Checkers.castUntainted;

class RepoAdminImpl implements RepoAdmin {

    private final DataSource dataSource;
    private final List<CappedDatabase> rollupCappedDatabases;
    private final CappedDatabase traceCappedDatabase;
    private final ConfigRepositoryImpl configRepository;
    private final EnvironmentDao agentDao;
    private final GaugeIdDao gaugeIdDao;
    private final GaugeNameDao gaugeNameDao;
    private final GaugeValueDao gaugeValueDao;
    private final TransactionTypeDao transactionTypeDao;
    private final FullQueryTextDao fullQueryTextDao;
    private final TraceAttributeNameDao traceAttributeNameDao;
    private final Clock clock;

    RepoAdminImpl(DataSource dataSource, List<CappedDatabase> rollupCappedDatabases,
            CappedDatabase traceCappedDatabase, ConfigRepositoryImpl configRepository,
            EnvironmentDao agentDao, GaugeIdDao gaugeIdDao, GaugeNameDao gaugeNameDao,
            GaugeValueDao gaugeValueDao, TransactionTypeDao transactionTypeDao,
            FullQueryTextDao fullQueryTextDao, TraceAttributeNameDao traceAttributeNameDao,
            Clock clock) {
        this.dataSource = dataSource;
        this.rollupCappedDatabases = rollupCappedDatabases;
        this.traceCappedDatabase = traceCappedDatabase;
        this.configRepository = configRepository;
        this.agentDao = agentDao;
        this.gaugeIdDao = gaugeIdDao;
        this.gaugeNameDao = gaugeNameDao;
        this.gaugeValueDao = gaugeValueDao;
        this.transactionTypeDao = transactionTypeDao;
        this.fullQueryTextDao = fullQueryTextDao;
        this.traceAttributeNameDao = traceAttributeNameDao;
        this.clock = clock;
    }

    @Override
    public void deleteAllData() throws Exception {
        Environment environment = agentDao.read("");
        dataSource.deleteAll();
        agentDao.reinitAfterDeletingDatabase();
        gaugeIdDao.invalidateCache();
        gaugeNameDao.invalidateCache();
        gaugeValueDao.reinitAfterDeletingDatabase();
        transactionTypeDao.invalidateCache();
        fullQueryTextDao.invalidateCache();
        traceAttributeNameDao.invalidateCache();
        if (environment != null) {
            agentDao.store(environment);
        }
    }

    @Override
    public void defragH2Data() throws Exception {
        dataSource.defrag();
    }

    @Override
    public void compactH2Data() throws Exception {
        dataSource.compact();
    }

    @Override
    public List<H2Table> analyzeH2DiskSpace() throws Exception {
        return dataSource.analyzeH2DiskSpace();
    }

    @Override
    public TraceTable analyzeTraceData() throws Exception {
        long captureTime = clock.currentTimeMillis();
        long count = dataSource.queryForLong("select count(*) from trace where capture_time < ?",
                captureTime);
        long errorCount = dataSource.queryForLong(
                "select count(*) from trace where error = ? and capture_time < ?", true,
                captureTime);
        int slowThresholdMillis1 =
                configRepository.getTransactionConfig("").getSlowThresholdMillis().getValue();
        int slowThresholdMillis2 = slowThresholdMillis1 == 0 ? 500 : slowThresholdMillis1 * 2;
        int slowThresholdMillis3 = slowThresholdMillis1 == 0 ? 1000 : slowThresholdMillis1 * 3;
        int slowThresholdMillis4 = slowThresholdMillis1 == 0 ? 1500 : slowThresholdMillis1 * 4;
        long slowCount1 = getSlowCount(slowThresholdMillis1, captureTime);
        long slowCount2 = getSlowCount(slowThresholdMillis2, captureTime);
        long slowCount3 = getSlowCount(slowThresholdMillis3, captureTime);
        long slowCount4 = getSlowCount(slowThresholdMillis4, captureTime);
        List<Long> ageDistribution = dataSource.query(new AgeDistributionQuery());
        return ImmutableTraceTable.builder()
                .count(count)
                .errorCount(errorCount)
                .slowThresholdMillis1(slowThresholdMillis1)
                .slowCount1(slowCount1)
                .slowThresholdMillis2(slowThresholdMillis2)
                .slowCount2(slowCount2)
                .slowThresholdMillis3(slowThresholdMillis3)
                .slowCount3(slowCount3)
                .slowThresholdMillis4(slowThresholdMillis4)
                .slowCount4(slowCount4)
                .ageDistribution(ageDistribution)
                .build();
    }

    private long getSlowCount(int slowThresholdMillis, long captureTime) throws SQLException {
        return dataSource.queryForLong("select count(*) from trace where slow = ? and error = ?"
                + " and duration_nanos >= ? and capture_time < ?", true, false,
                MILLISECONDS.toNanos(slowThresholdMillis), captureTime);
    }

    @Override
    public void resizeIfNeeded() throws Exception {
        // resize() doesn't do anything if the new and old value are the same
        for (int i = 0; i < rollupCappedDatabases.size(); i++) {
            rollupCappedDatabases.get(i).resize(
                    configRepository.getEmbeddedStorageConfig().rollupCappedDatabaseSizesMb().get(i)
                            * 1024);
        }
        traceCappedDatabase.resize(
                configRepository.getEmbeddedStorageConfig().traceCappedDatabaseSizeMb() * 1024);
    }

    private class AgeDistributionQuery implements JdbcQuery<List<Long>> {

        @Override
        public @Untainted String getSql() {
            return "select floor((? - capture_time) / " + castUntainted(DAYS.toMillis(1))
                    + ") age, count(*) from trace where capture_time < ? group by age";
        }

        @Override
        public void bind(PreparedStatement preparedStatement) throws SQLException {
            long now = clock.currentTimeMillis();
            preparedStatement.setLong(1, now);
            preparedStatement.setLong(2, now);
        }

        @Override
        public List<Long> processResultSet(ResultSet resultSet) throws Exception {
            Map<Integer, Long> countsPerAge = Maps.newHashMap();
            while (resultSet.next()) {
                countsPerAge.put(resultSet.getInt(1), resultSet.getLong(2));
            }
            List<Long> ageDistribution = Lists.newArrayList();
            for (Map.Entry<Integer, Long> entry : countsPerAge.entrySet()) {
                int age = entry.getKey();
                while (age >= ageDistribution.size()) {
                    ageDistribution.add(0L);
                }
                ageDistribution.set(age, entry.getValue());
            }
            return ageDistribution;
        }

        @Override
        public List<Long> valueIfDataSourceClosed() {
            return ImmutableList.of();
        }
    }
}
