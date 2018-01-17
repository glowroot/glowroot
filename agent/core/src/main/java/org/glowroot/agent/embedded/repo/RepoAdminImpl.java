/*
 * Copyright 2015-2018 the original author or authors.
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
import java.util.List;
import java.util.concurrent.Callable;

import com.google.common.base.Stopwatch;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;
import org.checkerframework.checker.tainting.qual.Untainted;

import org.glowroot.agent.embedded.util.CappedDatabase;
import org.glowroot.agent.embedded.util.DataSource;
import org.glowroot.agent.embedded.util.DataSource.JdbcQuery;
import org.glowroot.common.repo.ImmutableTraceCount;
import org.glowroot.common.repo.ImmutableTraceCounts;
import org.glowroot.common.repo.ImmutableTraceOverallCount;
import org.glowroot.common.repo.RepoAdmin;
import org.glowroot.wire.api.model.CollectorServiceOuterClass.Environment;

import static com.google.common.base.Preconditions.checkNotNull;
import static java.util.concurrent.TimeUnit.MILLISECONDS;

class RepoAdminImpl implements RepoAdmin {

    private final DataSource dataSource;
    private final List<CappedDatabase> rollupCappedDatabases;
    private final CappedDatabase traceCappedDatabase;
    private final ConfigRepositoryImpl configRepository;
    private final EnvironmentDao environmentDao;
    private final GaugeIdDao gaugeIdDao;
    private final GaugeNameDao gaugeNameDao;
    private final GaugeValueDao gaugeValueDao;
    private final TransactionTypeDao transactionTypeDao;
    private final FullQueryTextDao fullQueryTextDao;
    private final TraceAttributeNameDao traceAttributeNameDao;

    RepoAdminImpl(DataSource dataSource, List<CappedDatabase> rollupCappedDatabases,
            CappedDatabase traceCappedDatabase, ConfigRepositoryImpl configRepository,
            EnvironmentDao environmentDao, GaugeIdDao gaugeIdDao, GaugeNameDao gaugeNameDao,
            GaugeValueDao gaugeValueDao, TransactionTypeDao transactionTypeDao,
            FullQueryTextDao fullQueryTextDao, TraceAttributeNameDao traceAttributeNameDao) {
        this.dataSource = dataSource;
        this.rollupCappedDatabases = rollupCappedDatabases;
        this.traceCappedDatabase = traceCappedDatabase;
        this.configRepository = configRepository;
        this.environmentDao = environmentDao;
        this.gaugeIdDao = gaugeIdDao;
        this.gaugeNameDao = gaugeNameDao;
        this.gaugeValueDao = gaugeValueDao;
        this.transactionTypeDao = transactionTypeDao;
        this.fullQueryTextDao = fullQueryTextDao;
        this.traceAttributeNameDao = traceAttributeNameDao;
    }

    @Override
    public void deleteAllData() throws Exception {
        Environment environment = environmentDao.read("");
        dataSource.deleteAll();
        environmentDao.reinitAfterDeletingDatabase();
        gaugeIdDao.invalidateCache();
        gaugeNameDao.invalidateCache();
        gaugeValueDao.reinitAfterDeletingDatabase();
        transactionTypeDao.invalidateCache();
        fullQueryTextDao.invalidateCache();
        traceAttributeNameDao.invalidateCache();
        if (environment != null) {
            environmentDao.store(environment);
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
    public long getH2DataFileSize() {
        return dataSource.getH2DataFileSize();
    }

    @Override
    public List<H2Table> analyzeH2DiskSpace() throws Exception {
        return dataSource.analyzeH2DiskSpace();
    }

    @Override
    public TraceCounts analyzeTraceCounts() throws Exception {
        return dataSource.suppressQueryTimeout(new Callable<TraceCounts>() {
            @Override
            public TraceCounts call() throws Exception {
                ImmutableTraceCounts.Builder builder = ImmutableTraceCounts.builder();
                Stopwatch stopwatch = Stopwatch.createStarted();
                builder.addAllOverallCounts(dataSource.query(new TraceOverallCountQuery()));
                // sleep a bit to allow some other threads to use the data source
                Thread.sleep(stopwatch.elapsed(MILLISECONDS) / 10);
                builder.addAllCounts(dataSource.query(new TraceCountQuery()));
                return builder.build();
            }
        });
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

    private class TraceOverallCountQuery implements JdbcQuery<List<TraceOverallCount>> {

        @Override
        public @Untainted String getSql() {
            return "select transaction_type, count(*), count(case when error then 1 end) from trace"
                    + " group by transaction_type order by count(*) desc";
        }

        @Override
        public void bind(PreparedStatement preparedStatement) {}

        @Override
        public List<TraceOverallCount> processResultSet(ResultSet resultSet) throws Exception {
            List<TraceOverallCount> traceCounts = Lists.newArrayList();
            while (resultSet.next()) {
                int i = 1;
                traceCounts.add(ImmutableTraceOverallCount.builder()
                        .transactionType(checkNotNull(resultSet.getString(i++)))
                        .count(resultSet.getLong(i++))
                        .errorCount(resultSet.getLong(i++))
                        .build());
            }
            return traceCounts;
        }

        @Override
        public List<TraceOverallCount> valueIfDataSourceClosed() {
            return ImmutableList.of();
        }
    }

    private class TraceCountQuery implements JdbcQuery<List<TraceCount>> {

        @Override
        public @Untainted String getSql() {
            return "select transaction_type, transaction_name, count(*), count(case when error then"
                    + " 1 end) from trace group by transaction_type, transaction_name order by"
                    + " count(*) desc limit 50";
        }

        @Override
        public void bind(PreparedStatement preparedStatement) {}

        @Override
        public List<TraceCount> processResultSet(ResultSet resultSet) throws Exception {
            List<TraceCount> traceCounts = Lists.newArrayList();
            while (resultSet.next()) {
                int i = 1;
                traceCounts.add(ImmutableTraceCount.builder()
                        .transactionType(checkNotNull(resultSet.getString(i++)))
                        .transactionName(checkNotNull(resultSet.getString(i++)))
                        .count(resultSet.getLong(i++))
                        .errorCount(resultSet.getLong(i++))
                        .build());
            }
            return traceCounts;
        }

        @Override
        public List<TraceCount> valueIfDataSourceClosed() {
            return ImmutableList.of();
        }
    }
}
