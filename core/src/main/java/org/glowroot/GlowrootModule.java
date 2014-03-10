/*
 * Copyright 2013-2014 the original author or authors.
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
package org.glowroot;

import java.io.File;
import java.io.IOException;
import java.lang.instrument.Instrumentation;
import java.net.URISyntaxException;
import java.sql.SQLException;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;

import checkers.igj.quals.ReadOnly;
import checkers.nullness.quals.Nullable;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Ticker;
import com.google.common.util.concurrent.ThreadFactoryBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.glowroot.api.PluginServices;
import org.glowroot.collector.CollectorModule;
import org.glowroot.common.Clock;
import org.glowroot.config.ConfigModule;
import org.glowroot.jvm.JvmModule;
import org.glowroot.local.store.StorageModule;
import org.glowroot.local.ui.LocalUiModule;
import org.glowroot.markers.OnlyUsedByTests;
import org.glowroot.markers.ThreadSafe;
import org.glowroot.trace.TraceModule;

/**
 * @author Trask Stalnaker
 * @since 0.5
 */
@VisibleForTesting
@ThreadSafe
public class GlowrootModule {

    private static final Logger logger = LoggerFactory.getLogger(GlowrootModule.class);

    private final ScheduledExecutorService scheduledExecutor;
    private final ConfigModule configModule;
    private final StorageModule storageModule;
    private final CollectorModule collectorModule;
    private final TraceModule traceModule;
    private final LocalUiModule uiModule;
    private final File dataDir;

    GlowrootModule(File dataDir, @ReadOnly Map<String, String> properties,
            @Nullable Instrumentation instrumentation, String version,
            boolean snapshotReaperDisabled, boolean aggregatorDisabled)
            throws StartupFailedException {
        Ticker ticker = Ticker.systemTicker();
        Clock clock = Clock.systemClock();

        ThreadFactory threadFactory = new ThreadFactoryBuilder().setDaemon(true)
                .setNameFormat("Glowroot-Background-%d").build();
        scheduledExecutor = Executors.newScheduledThreadPool(2, threadFactory);
        JvmModule jvmModule = new JvmModule();
        try {
            configModule = new ConfigModule(instrumentation, dataDir);
        } catch (IOException e) {
            throw new StartupFailedException(e);
        } catch (URISyntaxException e) {
            throw new StartupFailedException(e);
        }
        try {
            storageModule = new StorageModule(dataDir, properties, ticker, clock, configModule,
                    scheduledExecutor, snapshotReaperDisabled);
        } catch (SQLException e) {
            throw new StartupFailedException(e);
        } catch (IOException e) {
            throw new StartupFailedException(e);
        }
        collectorModule = new CollectorModule(clock, ticker, configModule,
                storageModule.getSnapshotRepository(), storageModule.getAggregateRepository(),
                scheduledExecutor, aggregatorDisabled);
        traceModule = new TraceModule(ticker, clock, configModule,
                collectorModule.getTraceCollector(),
                jvmModule.getThreadAllocatedBytes().getService(), instrumentation,
                scheduledExecutor);
        uiModule = new LocalUiModule(ticker, clock, dataDir, jvmModule, configModule,
                storageModule, collectorModule, traceModule, instrumentation, properties, version);
        this.dataDir = dataDir;
    }

    PluginServices getPluginServices(@Nullable String pluginId) {
        return traceModule.getPluginServices(pluginId);
    }

    @OnlyUsedByTests
    public ConfigModule getConfigModule() {
        return configModule;
    }

    @OnlyUsedByTests
    public StorageModule getStorageModule() {
        return storageModule;
    }

    @OnlyUsedByTests
    public CollectorModule getCollectorModule() {
        return collectorModule;
    }

    @OnlyUsedByTests
    public TraceModule getTraceModule() {
        return traceModule;
    }

    @OnlyUsedByTests
    public LocalUiModule getUiModule() {
        return uiModule;
    }

    @OnlyUsedByTests
    public File getDataDir() {
        return dataDir;
    }

    @OnlyUsedByTests
    public void close() {
        logger.debug("close()");
        uiModule.close();
        traceModule.close();
        storageModule.close();
        scheduledExecutor.shutdownNow();
    }

    @VisibleForTesting
    @SuppressWarnings("serial")
    public static class StartupFailedException extends Exception {
        public StartupFailedException(Throwable cause) {
            super(cause);
        }
    }
}
