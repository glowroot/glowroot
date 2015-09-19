/*
 * Copyright 2011-2015 the original author or authors.
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
package org.glowroot.server.simplerepo;

import java.io.File;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.util.List;
import java.util.concurrent.ScheduledExecutorService;

import javax.annotation.Nullable;
import javax.management.InstanceAlreadyExistsException;
import javax.management.MBeanServer;
import javax.management.ObjectName;

import com.google.common.base.Ticker;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.glowroot.collector.spi.Collector;
import org.glowroot.common.util.Clock;
import org.glowroot.common.util.OnlyUsedByTests;
import org.glowroot.server.repo.AggregateRepository;
import org.glowroot.server.repo.ConfigRepository;
import org.glowroot.server.repo.GaugeValueRepository;
import org.glowroot.server.repo.RepoAdmin;
import org.glowroot.server.repo.TraceRepository;
import org.glowroot.server.repo.config.StorageConfig;
import org.glowroot.server.repo.helper.AlertingService;
import org.glowroot.server.simplerepo.PlatformMBeanServerLifecycle.InitListener;
import org.glowroot.server.simplerepo.util.CappedDatabase;
import org.glowroot.server.simplerepo.util.DataSource;
import org.glowroot.server.simplerepo.util.H2DatabaseStats;
import org.glowroot.server.util.MailService;

import static com.google.common.base.Preconditions.checkNotNull;
import static java.util.concurrent.TimeUnit.MINUTES;

public class SimpleRepoModule {

    private static final long SNAPSHOT_REAPER_PERIOD_MINUTES = 5;

    private static final Logger logger = LoggerFactory.getLogger(SimpleRepoModule.class);

    private final DataSource dataSource;
    private final ImmutableList<CappedDatabase> rollupCappedDatabases;
    private final CappedDatabase traceCappedDatabase;
    private final AggregateDao aggregateDao;
    private final TraceDao traceDao;
    private final GaugeValueDao gaugeValueDao;
    private final ConfigRepository configRepository;
    private final RepoAdmin repoAdmin;
    private final CollectorImpl collectorImpl;
    private final @Nullable ReaperRunnable reaperRunnable;

    @OnlyUsedByTests
    private volatile boolean unregisterMBeans;

    public SimpleRepoModule(File baseDir, Clock clock, Ticker ticker,
            ConfigRepository configRepository, @Nullable ScheduledExecutorService scheduledExecutor,
            PlatformMBeanServerLifecycle platformMBeanServerLifecycle, boolean internalH2MemDb,
            boolean viewerModeEnabled) throws Exception {
        File dataDir = new File(baseDir, "data");
        if (!dataDir.exists() && !dataDir.mkdir()) {
            throw new IOException("Could not create directory: " + dataDir.getAbsolutePath());
        }
        final DataSource dataSource;
        if (internalH2MemDb) {
            // mem db is only used for testing (by glowroot-test-container)
            dataSource = new DataSource();
        } else {
            dataSource = new DataSource(new File(dataDir, "data.h2.db"));
        }
        this.dataSource = dataSource;
        this.configRepository = configRepository;
        StorageConfig storageConfig = configRepository.getStorageConfig();
        final List<CappedDatabase> rollupCappedDatabases = Lists.newArrayList();
        for (int i = 0; i < storageConfig.rollupCappedDatabaseSizesMb().size(); i++) {
            File file = new File(dataDir, "rollup-" + i + "-detail.capped.db");
            int sizeKb = storageConfig.rollupCappedDatabaseSizesMb().get(i) * 1024;
            rollupCappedDatabases.add(new CappedDatabase(file, sizeKb, ticker));
        }
        this.rollupCappedDatabases = ImmutableList.copyOf(rollupCappedDatabases);
        traceCappedDatabase = new CappedDatabase(new File(dataDir, "trace-detail.capped.db"),
                storageConfig.traceCappedDatabaseSizeMb() * 1024, ticker);
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
                }
            }
        });

        aggregateDao =
                new AggregateDao(dataSource, this.rollupCappedDatabases, configRepository, clock);
        traceDao = new TraceDao(dataSource, traceCappedDatabase);
        gaugeValueDao = new GaugeValueDao(dataSource, configRepository,
                platformMBeanServerLifecycle, clock);

        repoAdmin = new RepoAdminImpl(dataSource, rollupCappedDatabases, traceCappedDatabase,
                configRepository);

        TriggeredAlertDao triggeredAlertDao = new TriggeredAlertDao(dataSource);
        AlertingService alertingService = new AlertingService(configRepository, triggeredAlertDao,
                aggregateDao, new MailService());
        collectorImpl = new CollectorImpl(aggregateDao, traceDao, gaugeValueDao, alertingService);
        PreInitializeStorageShutdownClasses.preInitializeClasses();
        if (viewerModeEnabled) {
            reaperRunnable = null;
        } else {
            // scheduledExecutor must be non-null when not in viewer mode
            checkNotNull(scheduledExecutor);
            reaperRunnable = new ReaperRunnable(configRepository, aggregateDao, traceDao,
                    gaugeValueDao, clock);
            reaperRunnable.scheduleWithFixedDelay(scheduledExecutor, 0,
                    SNAPSHOT_REAPER_PERIOD_MINUTES, MINUTES);
        }
    }

    public Collector getCollector() {
        return collectorImpl;
    }

    public GaugeValueRepository getGaugeValueRepository() {
        return gaugeValueDao;
    }

    public RepoAdmin getRepoAdmin() {
        return repoAdmin;
    }

    public AggregateRepository getAggregateRepository() {
        return aggregateDao;
    }

    public TraceRepository getTraceRepository() {
        return traceDao;
    }

    public ConfigRepository getConfigRepository() {
        return configRepository;
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
