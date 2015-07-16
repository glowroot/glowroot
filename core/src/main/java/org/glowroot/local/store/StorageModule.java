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
import java.util.Map;
import java.util.concurrent.ScheduledExecutorService;

import javax.annotation.Nullable;
import javax.management.InstanceAlreadyExistsException;
import javax.management.MBeanServer;
import javax.management.ObjectName;

import com.google.common.base.Ticker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.glowroot.api.PluginServices.ConfigListener;
import org.glowroot.collector.AggregateRepository;
import org.glowroot.collector.TraceRepository;
import org.glowroot.common.Clock;
import org.glowroot.config.ConfigModule;
import org.glowroot.config.ConfigService;
import org.glowroot.jvm.LazyPlatformMBeanServer;
import org.glowroot.jvm.LazyPlatformMBeanServer.MBeanServerCallback;
import org.glowroot.markers.OnlyUsedByTests;
import org.glowroot.weaving.PreInitializeStorageShutdownClasses;

import static com.google.common.base.Preconditions.checkNotNull;
import static java.util.concurrent.TimeUnit.MINUTES;

public class StorageModule {

    private static final long SNAPSHOT_REAPER_PERIOD_MINUTES = 5;

    private static final long FIXED_GAUGE_ROLLUP1_SECONDS =
            Long.getLong("glowroot.internal.gaugeRollup1", 60);
    private static final long FIXED_GAUGE_ROLLUP2_SECONDS =
            Long.getLong("glowroot.internal.gaugeRollup2", 15 * 60);
    private static final long FIXED_AGGREGATE_ROLLUP1_SECONDS =
            Long.getLong("glowroot.internal.aggregateRollup1", 5 * 60);
    private static final long FIXED_AGGREGATE_ROLLUP2_SECONDS =
            Long.getLong("glowroot.internal.aggregateRollup2", 30 * 60);

    private static final Logger logger = LoggerFactory.getLogger(StorageModule.class);

    private final DataSource dataSource;
    private final CappedDatabase cappedDatabase;
    private final AggregateDao aggregateDao;
    private final AggregateRepositoryImpl aggregateRepositoryImpl;
    private final TraceDao traceDao;
    private final GaugePointDao gaugePointDao;
    private final @Nullable ReaperRunnable reaperRunnable;
    private final LazyPlatformMBeanServer lazyPlatformMBeanServer;

    @OnlyUsedByTests
    private volatile boolean unregisterMBeans;

    public StorageModule(File dataDir, Map<String, String> properties, Clock clock, Ticker ticker,
            ConfigModule configModule, ScheduledExecutorService scheduledExecutor,
            LazyPlatformMBeanServer lazyPlatformMBeanServer, boolean viewerModeEnabled)
                    throws Exception {
        // mem db is only used for testing (by glowroot-test-container)
        String h2MemDb = properties.get("internal.h2.memdb");
        final DataSource dataSource;
        if (Boolean.parseBoolean(h2MemDb)) {
            dataSource = new DataSource();
        } else {
            dataSource = new DataSource(new File(dataDir, "glowroot.h2.db"));
        }
        this.dataSource = dataSource;
        final ConfigService configService = configModule.getConfigService();
        int cappedDatabaseSizeMb = configService.getStorageConfig().cappedDatabaseSizeMb();
        cappedDatabase = new CappedDatabase(new File(dataDir, "glowroot.capped.db"),
                cappedDatabaseSizeMb * 1024, ticker);
        this.lazyPlatformMBeanServer = lazyPlatformMBeanServer;
        lazyPlatformMBeanServer.possiblyDelayedCall(new MBeanServerCallback() {
            @Override
            public void call(MBeanServer mbeanServer) throws Exception {
                try {
                    checkNotNull(cappedDatabase);
                    mbeanServer.registerMBean(new CappedDatabaseStats(cappedDatabase),
                            new ObjectName("org.glowroot:type=CappedDatabaseStats"));
                    mbeanServer.registerMBean(new H2DatabaseStats(dataSource),
                            new ObjectName("org.glowroot:type=H2DatabaseStats"));
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
        aggregateDao = new AggregateDao(dataSource, cappedDatabase,
                configModule.getConfigService(), FIXED_AGGREGATE_ROLLUP1_SECONDS,
                FIXED_AGGREGATE_ROLLUP2_SECONDS);
        TriggeredAlertDao triggeredAlertDao = new TriggeredAlertDao(dataSource);
        AlertingService alertingService = new AlertingService(configService, triggeredAlertDao,
                aggregateDao, new MailService());
        aggregateRepositoryImpl = new AggregateRepositoryImpl(aggregateDao, alertingService);
        traceDao = new TraceDao(dataSource, cappedDatabase);
        gaugePointDao = new GaugePointDao(dataSource, clock, FIXED_GAUGE_ROLLUP1_SECONDS,
                FIXED_GAUGE_ROLLUP2_SECONDS);
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

    public CappedDatabase getCappedDatabase() {
        return cappedDatabase;
    }

    public long getFixedAggregateRollup1Seconds() {
        return FIXED_AGGREGATE_ROLLUP1_SECONDS;
    }

    public long getFixedAggregateRollup2Seconds() {
        return FIXED_AGGREGATE_ROLLUP2_SECONDS;
    }

    public long getFixedGaugeRollup1Seconds() {
        return FIXED_GAUGE_ROLLUP1_SECONDS;
    }

    public long getFixedGaugeRollup2Seconds() {
        return FIXED_GAUGE_ROLLUP2_SECONDS;
    }

    @OnlyUsedByTests
    public void close() throws Exception {
        if (unregisterMBeans) {
            lazyPlatformMBeanServer.unregisterMBean(
                    new ObjectName("org.glowroot:type=CappedDatabaseStats"));
            lazyPlatformMBeanServer.unregisterMBean(
                    new ObjectName("org.glowroot:type=H2DatabaseStats"));
        }
        if (reaperRunnable != null) {
            reaperRunnable.cancel();
        }
        cappedDatabase.close();
        dataSource.close();
    }
}
