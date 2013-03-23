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

import io.informant.api.PluginServices;
import io.informant.config.ConfigModule;
import io.informant.local.store.DataSourceModule;
import io.informant.local.store.StorageModule;
import io.informant.local.ui.LocalUiModule;
import io.informant.markers.OnlyUsedByTests;
import io.informant.markers.ThreadSafe;
import io.informant.snapshot.SnapshotModule;
import io.informant.trace.TraceModule;

import java.lang.instrument.ClassFileTransformer;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import checkers.igj.quals.ReadOnly;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.util.concurrent.ThreadFactoryBuilder;

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
    private final DataSourceModule dataSourceModule;
    private final StorageModule storageModule;
    private final SnapshotModule snapshotModule;
    private final TraceModule traceModule;
    private final LocalUiModule uiModule;

    InformantModule(@ReadOnly Map<String, String> properties) throws Exception {
        ThreadFactory threadFactory = new ThreadFactoryBuilder().setDaemon(true)
                .setNameFormat("Informant-Background").build();
        scheduledExecutor = Executors.newScheduledThreadPool(2, threadFactory);
        configModule = new ConfigModule(properties);
        dataSourceModule = new DataSourceModule(configModule, properties);
        storageModule = new StorageModule(configModule, dataSourceModule, scheduledExecutor);
        snapshotModule = new SnapshotModule(configModule, storageModule.getSnapshotSink(),
                scheduledExecutor);
        traceModule = new TraceModule(configModule, snapshotModule.getSnapshotTraceSink(),
                scheduledExecutor);
        uiModule = new LocalUiModule(configModule, dataSourceModule, storageModule, snapshotModule,
                traceModule, properties);
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
    public DataSourceModule getDataSourceModule() {
        return dataSourceModule;
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
        storageModule.close();
        dataSourceModule.close();
        scheduledExecutor.shutdownNow();
    }
}
