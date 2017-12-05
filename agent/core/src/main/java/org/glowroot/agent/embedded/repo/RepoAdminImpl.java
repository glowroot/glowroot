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

import java.util.List;

import org.glowroot.agent.embedded.init.ConfigRepositoryImpl;
import org.glowroot.agent.embedded.util.CappedDatabase;
import org.glowroot.agent.embedded.util.DataSource;
import org.glowroot.common.repo.RepoAdmin;
import org.glowroot.wire.api.model.CollectorServiceOuterClass.Environment;

class RepoAdminImpl implements RepoAdmin {

    private final DataSource dataSource;
    private final List<CappedDatabase> rollupCappedDatabases;
    private final CappedDatabase traceCappedDatabase;
    private final ConfigRepositoryImpl configRepository;
    private final EnvironmentDao agentDao;
    private final GaugeIdDao gaugeIdDao;
    private final GaugeValueDao gaugeValueDao;
    private final TransactionTypeDao transactionTypeDao;
    private final FullQueryTextDao fullQueryTextDao;
    private final TraceAttributeNameDao traceAttributeNameDao;

    RepoAdminImpl(DataSource dataSource, List<CappedDatabase> rollupCappedDatabases,
            CappedDatabase traceCappedDatabase, ConfigRepositoryImpl configRepository,
            EnvironmentDao agentDao, GaugeIdDao gaugeIdDao, GaugeValueDao gaugeValueDao,
            TransactionTypeDao transactionTypeDao, FullQueryTextDao fullQueryTextDao,
            TraceAttributeNameDao traceAttributeNameDao) {
        this.dataSource = dataSource;
        this.rollupCappedDatabases = rollupCappedDatabases;
        this.traceCappedDatabase = traceCappedDatabase;
        this.configRepository = configRepository;
        this.agentDao = agentDao;
        this.gaugeIdDao = gaugeIdDao;
        this.gaugeValueDao = gaugeValueDao;
        this.transactionTypeDao = transactionTypeDao;
        this.fullQueryTextDao = fullQueryTextDao;
        this.traceAttributeNameDao = traceAttributeNameDao;
    }

    @Override
    public void deleteAllData() throws Exception {
        Environment environment = agentDao.read("");
        dataSource.deleteAll();
        agentDao.reinitAfterDeletingDatabase();
        gaugeIdDao.invalidateCache();
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
}
