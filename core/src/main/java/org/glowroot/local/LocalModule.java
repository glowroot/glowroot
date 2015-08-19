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
package org.glowroot.local;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.Map;
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

import org.glowroot.agent.config.ConfigService;
import org.glowroot.agent.util.LazyPlatformMBeanServer;
import org.glowroot.agent.util.LazyPlatformMBeanServer.InitListener;
import org.glowroot.collector.spi.Collector;
import org.glowroot.common.config.StorageConfig;
import org.glowroot.common.repo.AggregateRepository;
import org.glowroot.common.repo.ConfigRepository;
import org.glowroot.common.repo.GaugeValueRepository;
import org.glowroot.common.repo.RepoAdmin;
import org.glowroot.common.repo.TraceRepository;
import org.glowroot.common.repo.helper.AlertingService;
import org.glowroot.common.util.Clock;
import org.glowroot.common.util.MailService;
import org.glowroot.local.util.CappedDatabase;
import org.glowroot.local.util.DataSource;
import org.glowroot.local.util.H2DatabaseStats;
import org.glowroot.markers.OnlyUsedByTests;
import org.glowroot.plugin.api.config.ConfigListener;

import static com.google.common.base.Preconditions.checkNotNull;
import static java.util.concurrent.TimeUnit.MINUTES;

public class LocalModule {

    private static final long SNAPSHOT_REAPER_PERIOD_MINUTES = 5;

    private static final Logger logger = LoggerFactory.getLogger(LocalModule.class);

    private final DataSource dataSource;
    private final ImmutableList<CappedDatabase> rollupCappedDatabases;
    private final CappedDatabase traceCappedDatabase;
    private final AggregateDao aggregateDao;
    private final TraceDao traceDao;
    private final GaugeValueDao gaugeValueDao;
    private final ConfigRepositoryImpl configRepository;
    private final RepoAdmin repoAdmin;
    private final CollectorImpl collectorImpl;
    private final @Nullable ReaperRunnable reaperRunnable;
    private final LazyPlatformMBeanServer lazyPlatformMBeanServer;

    @OnlyUsedByTests
    private volatile boolean unregisterMBeans;

    public LocalModule(File baseDir, Map<String, String> properties, Clock clock, Ticker ticker,
            final ConfigService configService, @Nullable ScheduledExecutorService scheduledExecutor,
            LazyPlatformMBeanServer lazyPlatformMBeanServer, boolean viewerModeEnabled)
                    throws Exception {
        File dataDir = new File(baseDir, "data");
        if (!dataDir.exists() && !dataDir.mkdir()) {
            throw new IOException("Could not create directory: " + dataDir.getAbsolutePath());
        }
        // mem db is only used for testing (by glowroot-test-container)
        String h2MemDb = properties.get("internal.h2.memdb");
        final DataSource dataSource;
        if (Boolean.parseBoolean(h2MemDb)) {
            dataSource = new DataSource();
        } else {
            dataSource = new DataSource(new File(dataDir, "data.h2.db"));
        }
        this.dataSource = dataSource;
        StorageConfig storageConfig = configService.getStorageConfig();
        List<CappedDatabase> rollupCappedDatabases = Lists.newArrayList();
        for (int i = 0; i < storageConfig.rollupCappedDatabaseSizesMb().size(); i++) {
            File file = new File(dataDir, "rollup-" + i + "-detail.capped.db");
            int sizeKb = storageConfig.rollupCappedDatabaseSizesMb().get(i) * 1024;
            rollupCappedDatabases.add(new CappedDatabase(file, sizeKb, ticker));
        }
        this.rollupCappedDatabases = ImmutableList.copyOf(rollupCappedDatabases);
        traceCappedDatabase = new CappedDatabase(new File(dataDir, "trace-detail.capped.db"),
                storageConfig.traceCappedDatabaseSizeMb() * 1024, ticker);
        this.lazyPlatformMBeanServer = lazyPlatformMBeanServer;
        lazyPlatformMBeanServer.addInitListener(new LazyInit());
        configRepository = new ConfigRepositoryImpl(baseDir, configService);
        aggregateDao =
                new AggregateDao(dataSource, this.rollupCappedDatabases, configRepository, clock);
        traceDao = new TraceDao(dataSource, traceCappedDatabase);
        gaugeValueDao =
                new GaugeValueDao(dataSource, configRepository, lazyPlatformMBeanServer, clock);

        repoAdmin = new RepoAdminImpl(dataSource, rollupCappedDatabases, traceCappedDatabase,
                configRepository);

        TriggeredAlertDao triggeredAlertDao = new TriggeredAlertDao(dataSource);
        AlertingService alertingService = new AlertingService(configRepository, triggeredAlertDao,
                aggregateDao, new MailService());
        collectorImpl = new CollectorImpl(aggregateDao, traceDao, gaugeValueDao, alertingService);
        // safe to set query timeout after all daos have initialized
        dataSource.setQueryTimeoutSeconds(
                configService.getAdvancedConfig().internalQueryTimeoutSeconds());
        configService.addConfigListener(new ConfigListener() {
            @Override
            public void onChange() {
                dataSource.setQueryTimeoutSeconds(
                        configService.getAdvancedConfig().internalQueryTimeoutSeconds());
            }
        });
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

    public ImmutableList<CappedDatabase> getRollupCappedDatabases() {
        return rollupCappedDatabases;
    }

    public CappedDatabase getTraceCappedDatabase() {
        return traceCappedDatabase;
    }

    private class LazyInit implements InitListener {

        @Override
        public void postInit(MBeanServer mbeanServer) throws Exception {
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
    }

    @OnlyUsedByTests
    public void close() throws Exception {
        if (unregisterMBeans) {
            for (int i = 0; i < rollupCappedDatabases.size(); i++) {
                lazyPlatformMBeanServer.unregisterMBean(
                        new ObjectName("org.glowroot:type=RollupCappedDatabase" + i));
            }
            lazyPlatformMBeanServer
                    .unregisterMBean(new ObjectName("org.glowroot:type=TraceCappedDatabase"));
            lazyPlatformMBeanServer.unregisterMBean(new ObjectName("org.glowroot:type=H2Database"));
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
