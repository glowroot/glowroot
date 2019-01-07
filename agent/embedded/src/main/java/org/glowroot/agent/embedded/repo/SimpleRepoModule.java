/*
 * Copyright 2011-2019 the original author or authors.
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

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ScheduledExecutorService;

import com.google.common.base.Ticker;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.glowroot.agent.embedded.util.CappedDatabase;
import org.glowroot.agent.embedded.util.DataSource;
import org.glowroot.agent.embedded.util.H2DatabaseStats;
import org.glowroot.common.util.Clock;
import org.glowroot.common.util.OnlyUsedByTests;
import org.glowroot.common2.config.EmbeddedStorageConfig;
import org.glowroot.common2.repo.RepoAdmin;
import org.glowroot.common2.repo.TraceAttributeNameRepository;
import org.glowroot.common2.repo.TransactionTypeRepository;
import org.glowroot.common2.repo.util.AlertingService;
import org.glowroot.common2.repo.util.AlertingService.IncidentKey;
import org.glowroot.common2.repo.util.HttpClient;
import org.glowroot.common2.repo.util.LockSet.LockSetImpl;
import org.glowroot.common2.repo.util.MailService;
import org.glowroot.common2.repo.util.RollupLevelService;

import static java.util.concurrent.TimeUnit.MINUTES;

public class SimpleRepoModule {

    // log startup messages using logger name "org.glowroot"
    private static final Logger startupLogger = LoggerFactory.getLogger("org.glowroot");

    private static final long SNAPSHOT_REAPER_PERIOD_MINUTES = 5;

    private final DataSource dataSource;
    private final ImmutableList<CappedDatabase> rollupCappedDatabases;
    private final CappedDatabase traceCappedDatabase;
    private final AlertingDisabledDao alertingDisabledDao;
    private final EnvironmentDao environmentDao;
    private final TransactionTypeDao transactionTypeDao;
    private final AggregateDao aggregateDao;
    private final TraceAttributeNameDao traceAttributeNameDao;
    private final TraceDao traceDao;
    private final GaugeValueDao gaugeValueDao;
    private final IncidentDao incidentDao;
    private final ConfigRepositoryImpl configRepository;
    private final RepoAdmin repoAdmin;
    private final RollupLevelService rollupLevelService;
    private final AlertingService alertingService;
    private final HttpClient httpClient;
    private final @Nullable ReaperRunnable reaperRunnable;

    public SimpleRepoModule(DataSource dataSource, File dataDir, Clock clock, Ticker ticker,
            ConfigRepositoryImpl configRepository,
            @Nullable ScheduledExecutorService backgroundExecutor) throws Exception {
        if (!dataDir.exists() && !dataDir.mkdir()) {
            throw new IOException("Could not create directory: " + dataDir.getAbsolutePath());
        }
        this.dataSource = dataSource;
        this.configRepository = configRepository;
        EmbeddedStorageConfig storageConfig = configRepository.getEmbeddedStorageConfig();
        List<CappedDatabase> rollupCappedDatabases = Lists.newArrayList();
        for (int i = 0; i < storageConfig.rollupCappedDatabaseSizesMb().size(); i++) {
            File file = new File(dataDir, "rollup-" + i + "-detail.capped.db");
            int sizeKb = storageConfig.rollupCappedDatabaseSizesMb().get(i) * 1024;
            rollupCappedDatabases.add(new CappedDatabase(file, sizeKb, backgroundExecutor, ticker));
        }
        this.rollupCappedDatabases = ImmutableList.copyOf(rollupCappedDatabases);
        traceCappedDatabase = new CappedDatabase(new File(dataDir, "trace-detail.capped.db"),
                storageConfig.traceCappedDatabaseSizeMb() * 1024, backgroundExecutor, ticker);

        SchemaUpgrade schemaUpgrade = new SchemaUpgrade(dataSource);
        Integer initialSchemaVersion = schemaUpgrade.getInitialSchemaVersion();
        if (initialSchemaVersion == null) {
            startupLogger.info("creating glowroot schema...");
        } else {
            schemaUpgrade.upgrade();
        }

        alertingDisabledDao = new AlertingDisabledDao(dataSource);
        environmentDao = new EnvironmentDao(dataSource);
        transactionTypeDao = new TransactionTypeDao(dataSource);
        rollupLevelService = new RollupLevelService(configRepository, clock);
        FullQueryTextDao fullQueryTextDao = new FullQueryTextDao(dataSource);
        aggregateDao = new AggregateDao(dataSource, this.rollupCappedDatabases, configRepository,
                transactionTypeDao, fullQueryTextDao);
        traceAttributeNameDao = new TraceAttributeNameDao(dataSource);
        traceDao = new TraceDao(dataSource, traceCappedDatabase, transactionTypeDao,
                fullQueryTextDao, traceAttributeNameDao);
        GaugeIdDao gaugeIdDao = new GaugeIdDao(dataSource);
        GaugeNameDao gaugeNameDao = new GaugeNameDao(dataSource);
        gaugeValueDao = new GaugeValueDao(dataSource, gaugeIdDao, gaugeNameDao, clock);
        incidentDao = new IncidentDao(dataSource);

        if (initialSchemaVersion == null) {
            schemaUpgrade.updateSchemaVersionToCurent();
            startupLogger.info("glowroot schema created");
        }

        repoAdmin = new RepoAdminImpl(dataSource, rollupCappedDatabases, traceCappedDatabase,
                configRepository, alertingDisabledDao, environmentDao, gaugeIdDao, gaugeNameDao,
                gaugeValueDao, transactionTypeDao, fullQueryTextDao, traceAttributeNameDao, clock);

        httpClient = new HttpClient(configRepository);

        alertingService = new AlertingService(configRepository, incidentDao, aggregateDao,
                gaugeValueDao, rollupLevelService, new MailService(), httpClient, newLockSet(),
                newLockSet(), clock);
        if (backgroundExecutor == null) {
            reaperRunnable = null;
        } else {
            reaperRunnable = new ReaperRunnable(configRepository, aggregateDao, traceDao,
                    gaugeIdDao, gaugeNameDao, gaugeValueDao, transactionTypeDao, fullQueryTextDao,
                    incidentDao, clock);
            reaperRunnable.scheduleWithFixedDelay(backgroundExecutor,
                    SNAPSHOT_REAPER_PERIOD_MINUTES, MINUTES);
        }
    }

    public void registerMBeans(PlatformMBeanServerLifecycle platformMBeanServerLifecycle) {
        for (int i = 0; i < rollupCappedDatabases.size(); i++) {
            platformMBeanServerLifecycle.lazyRegisterMBean(
                    new RollupCappedDatabaseStats(rollupCappedDatabases.get(i)),
                    "org.glowroot:type=RollupCappedDatabase" + i);
        }
        platformMBeanServerLifecycle.lazyRegisterMBean(
                new TraceCappedDatabaseStats(traceCappedDatabase),
                "org.glowroot:type=TraceCappedDatabase");
        platformMBeanServerLifecycle.lazyRegisterMBean(new H2DatabaseStats(dataSource),
                "org.glowroot:type=H2Database");
    }

    public AlertingDisabledDao getAlertingDisabledDao() {
        return alertingDisabledDao;
    }

    public EnvironmentDao getEnvironmentDao() {
        return environmentDao;
    }

    public TransactionTypeRepository getTransactionTypeRepository() {
        return transactionTypeDao;
    }

    public AggregateDao getAggregateDao() {
        return aggregateDao;
    }

    public TraceAttributeNameRepository getTraceAttributeNameRepository() {
        return traceAttributeNameDao;
    }

    public TraceDao getTraceDao() {
        return traceDao;
    }

    public GaugeValueDao getGaugeValueDao() {
        return gaugeValueDao;
    }

    public IncidentDao getIncidentDao() {
        return incidentDao;
    }

    public ConfigRepositoryImpl getConfigRepository() {
        return configRepository;
    }

    public RepoAdmin getRepoAdmin() {
        return repoAdmin;
    }

    public RollupLevelService getRollupLevelService() {
        return rollupLevelService;
    }

    public AlertingService getAlertingService() {
        return alertingService;
    }

    public HttpClient getHttpClient() {
        return httpClient;
    }

    @OnlyUsedByTests
    public void close() throws Exception {
        if (reaperRunnable != null) {
            reaperRunnable.cancel();
        }
        alertingService.close();
        for (CappedDatabase cappedDatabase : rollupCappedDatabases) {
            cappedDatabase.close();
        }
        traceCappedDatabase.close();
        dataSource.close();
    }

    private static LockSetImpl<IncidentKey> newLockSet() {
        return new LockSetImpl<IncidentKey>(Maps.<IncidentKey, UUID>newConcurrentMap());
    }
}
