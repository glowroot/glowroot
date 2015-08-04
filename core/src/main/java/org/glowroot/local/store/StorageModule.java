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
package org.glowroot.local.store;

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

import org.glowroot.collector.AggregateRepository;
import org.glowroot.collector.TraceRepository;
import org.glowroot.common.Clock;
import org.glowroot.config.ConfigModule;
import org.glowroot.config.ConfigService;
import org.glowroot.config.StorageConfig;
import org.glowroot.jvm.LazyPlatformMBeanServer;
import org.glowroot.jvm.LazyPlatformMBeanServer.InitListener;
import org.glowroot.markers.OnlyUsedByTests;
import org.glowroot.plugin.api.config.ConfigListener;
import org.glowroot.weaving.PreInitializeStorageShutdownClasses;

import static java.util.concurrent.TimeUnit.MINUTES;

public class StorageModule {

    private static final long SNAPSHOT_REAPER_PERIOD_MINUTES = 5;

    private static final Logger logger = LoggerFactory.getLogger(StorageModule.class);

    private final DataSource dataSource;
    private final ImmutableList<CappedDatabase> rollupCappedDatabases;
    private final CappedDatabase traceCappedDatabase;
    private final AggregateDao aggregateDao;
    private final AggregateRepositoryImpl aggregateRepositoryImpl;
    private final TraceDao traceDao;
    private final GaugePointDao gaugePointDao;
    private final @Nullable ReaperRunnable reaperRunnable;
    private final LazyPlatformMBeanServer lazyPlatformMBeanServer;

    @OnlyUsedByTests
    private volatile boolean unregisterMBeans;

    public StorageModule(File baseDir, Map<String, String> properties, Clock clock, Ticker ticker,
            ConfigModule configModule, ScheduledExecutorService scheduledExecutor,
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
        final ConfigService configService = configModule.getConfigService();
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
        aggregateDao = new AggregateDao(dataSource, this.rollupCappedDatabases,
                configModule.getConfigService(), clock);
        TriggeredAlertDao triggeredAlertDao = new TriggeredAlertDao(dataSource);
        AlertingService alertingService = new AlertingService(configService, triggeredAlertDao,
                aggregateDao, new MailService());
        aggregateRepositoryImpl = new AggregateRepositoryImpl(aggregateDao, alertingService);
        traceDao = new TraceDao(dataSource, traceCappedDatabase);
        gaugePointDao = new GaugePointDao(dataSource, configService, clock);
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
            reaperRunnable =
                    new ReaperRunnable(configService, aggregateDao, traceDao, gaugePointDao, clock);
            reaperRunnable.scheduleWithFixedDelay(scheduledExecutor, 0,
                    SNAPSHOT_REAPER_PERIOD_MINUTES, MINUTES);
        }
    }
    public AggregateRepository getAggregateRepository() {
        return aggregateRepositoryImpl;
    }

    public TraceRepository getTraceRepository() {
        return traceDao;
    }

    public GaugePointDao getGaugePointDao() {
        return gaugePointDao;
    }

    public DataSource getDataSource() {
        return dataSource;
    }

    public AggregateDao getAggregateDao() {
        return aggregateDao;
    }

    public TraceDao getTraceDao() {
        return traceDao;
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
            lazyPlatformMBeanServer.unregisterMBean(
                    new ObjectName("org.glowroot:type=TraceCappedDatabase"));
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
