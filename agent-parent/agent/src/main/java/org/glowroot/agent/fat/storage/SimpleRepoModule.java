/*
 * Copyright 2011-2016 the original author or authors.
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
package org.glowroot.agent.fat.storage;

import java.io.File;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.util.List;
import java.util.concurrent.ScheduledExecutorService;

import javax.annotation.Nullable;
import javax.management.InstanceAlreadyExistsException;
import javax.management.MBeanServer;
import javax.management.NotCompliantMBeanException;
import javax.management.ObjectName;

import com.google.common.base.Ticker;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.glowroot.agent.fat.storage.PlatformMBeanServerLifecycle.InitListener;
import org.glowroot.agent.fat.storage.util.CappedDatabase;
import org.glowroot.agent.fat.storage.util.DataSource;
import org.glowroot.agent.fat.storage.util.H2DatabaseStats;
import org.glowroot.common.util.Clock;
import org.glowroot.common.util.OnlyUsedByTests;
import org.glowroot.storage.config.StorageConfig;
import org.glowroot.storage.repo.AggregateRepository;
import org.glowroot.storage.repo.ConfigRepository;
import org.glowroot.storage.repo.GaugeValueRepository;
import org.glowroot.storage.repo.RepoAdmin;
import org.glowroot.storage.repo.TraceRepository;
import org.glowroot.storage.repo.TransactionTypeRepository;
import org.glowroot.storage.repo.helper.AlertingService;
import org.glowroot.storage.repo.helper.RollupLevelService;
import org.glowroot.storage.util.MailService;

import static com.google.common.base.Preconditions.checkNotNull;
import static java.util.concurrent.TimeUnit.MINUTES;

public class SimpleRepoModule {

    private static final long SNAPSHOT_REAPER_PERIOD_MINUTES = 5;

    private static final Logger logger = LoggerFactory.getLogger(SimpleRepoModule.class);

    private final DataSource dataSource;
    private final ImmutableList<CappedDatabase> rollupCappedDatabases;
    private final CappedDatabase traceCappedDatabase;
    private final AgentDao agentDao;
    private final TransactionTypeDao transactionTypeDao;
    private final AggregateDao aggregateDao;
    private final TraceDao traceDao;
    private final GaugeValueDao gaugeValueDao;
    private final ConfigRepository configRepository;
    private final RepoAdmin repoAdmin;
    private final RollupLevelService rollupLevelService;
    private final AlertingService alertingService;
    private final @Nullable ReaperRunnable reaperRunnable;

    @OnlyUsedByTests
    private volatile boolean unregisterMBeans;

    public SimpleRepoModule(DataSource dataSource, File dataDir, Clock clock, Ticker ticker,
            ConfigRepository configRepository, @Nullable ScheduledExecutorService scheduledExecutor,
            boolean reaperDisabled) throws Exception {
        if (!dataDir.exists() && !dataDir.mkdir()) {
            throw new IOException("Could not create directory: " + dataDir.getAbsolutePath());
        }
        this.dataSource = dataSource;
        this.configRepository = configRepository;
        StorageConfig storageConfig = configRepository.getStorageConfig();
        List<CappedDatabase> rollupCappedDatabases = Lists.newArrayList();
        for (int i = 0; i < storageConfig.rollupCappedDatabaseSizesMb().size(); i++) {
            File file = new File(dataDir, "rollup-" + i + "-detail.capped.db");
            int sizeKb = storageConfig.rollupCappedDatabaseSizesMb().get(i) * 1024;
            rollupCappedDatabases.add(new CappedDatabase(file, sizeKb, ticker));
        }
        this.rollupCappedDatabases = ImmutableList.copyOf(rollupCappedDatabases);
        traceCappedDatabase = new CappedDatabase(new File(dataDir, "trace-detail.capped.db"),
                storageConfig.traceCappedDatabaseSizeMb() * 1024, ticker);

        agentDao = new AgentDao(dataSource);
        transactionTypeDao = new TransactionTypeDao(dataSource);
        rollupLevelService = new RollupLevelService(configRepository, clock);
        aggregateDao = new AggregateDao(dataSource, this.rollupCappedDatabases, configRepository,
                transactionTypeDao);
        traceDao = new TraceDao(dataSource, traceCappedDatabase, transactionTypeDao);
        GaugeDao gaugeMetaDao = new GaugeDao(dataSource);
        gaugeValueDao = new GaugeValueDao(dataSource, gaugeMetaDao, configRepository, clock);

        repoAdmin = new RepoAdminImpl(dataSource, rollupCappedDatabases, traceCappedDatabase,
                configRepository);

        TriggeredAlertDao triggeredAlertDao = new TriggeredAlertDao(dataSource);
        alertingService = new AlertingService(configRepository, agentDao, triggeredAlertDao,
                aggregateDao, gaugeValueDao, rollupLevelService, new MailService());
        if (reaperDisabled) {
            reaperRunnable = null;
        } else {
            // scheduledExecutor must be non-null when enabling reaper
            checkNotNull(scheduledExecutor);
            reaperRunnable = new ReaperRunnable(configRepository, aggregateDao, traceDao,
                    gaugeValueDao, gaugeMetaDao, transactionTypeDao, clock);
            reaperRunnable.scheduleWithFixedDelay(scheduledExecutor, 0,
                    SNAPSHOT_REAPER_PERIOD_MINUTES, MINUTES);
        }
    }

    public void registerMBeans(PlatformMBeanServerLifecycle platformMBeanServerLifecycle) {
        platformMBeanServerLifecycle.addInitListener(new InitListener() {
            @Override
            public void doWithPlatformMBeanServer(MBeanServer mbeanServer) throws Exception {
                checkNotNull(traceCappedDatabase);
                try {
                    for (int i = 0; i < rollupCappedDatabases.size(); i++) {
                        mbeanServer.registerMBean(
                                new RollupCappedDatabaseStats(rollupCappedDatabases.get(i)),
                                new ObjectName("org.glowroot:type=RollupCappedDatabase" + i));
                    }
                    mbeanServer.registerMBean(new TraceCappedDatabaseStats(traceCappedDatabase),
                            new ObjectName("org.glowroot:type=TraceCappedDatabase"));
                    mbeanServer.registerMBean(new H2DatabaseStats(dataSource),
                            new ObjectName("org.glowroot:type=H2Database"));
                    unregisterMBeans = true;
                } catch (InstanceAlreadyExistsException e) {
                    // this happens during unit tests when a non-shared local container is used
                    // (so that then there are two local containers in the same jvm)
                    //
                    // log exception at debug level
                    logger.debug(e.getMessage(), e);
                } catch (NotCompliantMBeanException e) {
                    if (e.getStackTrace()[0].getClassName()
                            .equals("org.jboss.mx.metadata.MBeanCapability")) {
                        // this happens in jboss 4.2.3 because it doesn't know about Java 6 "MXBean"
                        // naming convention
                        // it's not really that important if these diagnostic mbeans aren't
                        // registered
                        logger.debug(e.getMessage(), e);
                    } else {
                        throw e;
                    }
                }
            }
        });
    }

    public AgentDao getAgentDao() {
        return agentDao;
    }

    public TransactionTypeRepository getTransactionTypeRepository() {
        return transactionTypeDao;
    }

    public AggregateRepository getAggregateRepository() {
        return aggregateDao;
    }

    public TraceRepository getTraceRepository() {
        return traceDao;
    }

    public GaugeValueRepository getGaugeValueRepository() {
        return gaugeValueDao;
    }

    public ConfigRepository getConfigRepository() {
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

    @OnlyUsedByTests
    public void close() throws Exception {
        MBeanServer mbeanServer = ManagementFactory.getPlatformMBeanServer();
        if (unregisterMBeans) {
            for (int i = 0; i < rollupCappedDatabases.size(); i++) {
                mbeanServer.unregisterMBean(
                        new ObjectName("org.glowroot:type=RollupCappedDatabase" + i));
            }
            mbeanServer.unregisterMBean(new ObjectName("org.glowroot:type=TraceCappedDatabase"));
            mbeanServer.unregisterMBean(new ObjectName("org.glowroot:type=H2Database"));
        }
        if (reaperRunnable != null) {
            reaperRunnable.cancel();
        }
        for (CappedDatabase cappedDatabase : rollupCappedDatabases) {
            cappedDatabase.close();
        }
        traceCappedDatabase.close();
        dataSource.close();
    }
}
