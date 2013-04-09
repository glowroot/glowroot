/**
 * Copyright 2013 the original author or authors.
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
package io.informant;

import java.io.File;
import java.lang.instrument.ClassFileTransformer;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;

import checkers.igj.quals.ReadOnly;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Ticker;
import com.google.common.util.concurrent.ThreadFactoryBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.informant.api.PluginServices;
import io.informant.common.Clock;
import io.informant.config.ConfigModule;
import io.informant.local.store.StorageModule;
import io.informant.local.ui.LocalUiModule;
import io.informant.markers.OnlyUsedByTests;
import io.informant.markers.ThreadSafe;
import io.informant.snapshot.SnapshotModule;
import io.informant.trace.TraceModule;

/**
 * @author Trask Stalnaker
 * @since 0.5
 */
@VisibleForTesting
@ThreadSafe
public class InformantModule {

    private static final Logger logger = LoggerFactory.getLogger(InformantModule.class);

    private final ScheduledExecutorService scheduledExecutor;
    private final ConfigModule configModule;
    private final StorageModule storageModule;
    private final SnapshotModule snapshotModule;
    private final TraceModule traceModule;
    private final LocalUiModule uiModule;

    InformantModule(@ReadOnly Map<String, String> properties) throws Exception {
        Ticker ticker = Ticker.systemTicker();
        Clock clock = Clock.systemClock();
        File dataDir = DataDir.getDataDir(properties);

        ThreadFactory threadFactory = new ThreadFactoryBuilder().setDaemon(true)
                .setNameFormat("Informant-Background").build();
        scheduledExecutor = Executors.newScheduledThreadPool(2, threadFactory);
        configModule = new ConfigModule(dataDir);
        storageModule = new StorageModule(dataDir, properties, ticker, clock, configModule,
                scheduledExecutor);
        snapshotModule = new SnapshotModule(ticker, configModule, storageModule.getSnapshotSink(),
                scheduledExecutor);
        traceModule = new TraceModule(ticker, clock, configModule,
                snapshotModule.getSnapshotTraceSink(), scheduledExecutor);
        uiModule = new LocalUiModule(ticker, clock, dataDir, configModule, storageModule,
                snapshotModule, traceModule, properties);
    }

    ClassFileTransformer createWeavingClassFileTransformer() {
        return traceModule.createWeavingClassFileTransformer();
    }

    PluginServices getPluginServices(String pluginId) {
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
    public SnapshotModule getSnapshotModule() {
        return snapshotModule;
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
    public void close() {
        logger.debug("shutdown()");
        uiModule.close();
        traceModule.close();
        storageModule.close();
        scheduledExecutor.shutdownNow();
    }
}
