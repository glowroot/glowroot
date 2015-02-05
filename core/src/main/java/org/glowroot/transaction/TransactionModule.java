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
package org.glowroot.transaction;

import java.io.File;
import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.util.concurrent.ScheduledExecutorService;

import javax.annotation.Nullable;

import org.glowroot.api.PluginServices;
import org.glowroot.common.Clock;
import org.glowroot.common.Ticker;
import org.glowroot.config.ConfigModule;
import org.glowroot.config.ConfigService;
import org.glowroot.jvm.ThreadAllocatedBytes;
import org.glowroot.markers.OnlyUsedByTests;
import org.glowroot.transaction.PluginServicesRegistry.PluginServicesFactory;
import org.glowroot.weaving.AnalyzedWorld;
import org.glowroot.weaving.ExtraBootResourceFinder;
import org.glowroot.weaving.PreInitializeWeavingClasses;
import org.glowroot.weaving.WeavingClassFileTransformer;
import org.glowroot.weaving.WeavingTimerService;

import static java.util.concurrent.TimeUnit.MILLISECONDS;

public class TransactionModule {

    private final AnalyzedWorld analyzedWorld;
    private final TransactionRegistry transactionRegistry;
    private final AdviceCache adviceCache;
    private final WeavingTimerService weavingTimerService;
    private final @Nullable ThreadAllocatedBytes threadAllocatedBytes;

    private final ImmediateTraceStoreWatcher immedateTraceStoreWatcher;

    private final boolean metricWrapperMethods;
    private final boolean jvmRetransformClassesSupported;

    private final PluginServicesFactory pluginServicesFactory;

    public TransactionModule(final Clock clock, final Ticker ticker,
            final ConfigModule configModule, final TransactionCollector transactionCollector,
            final @Nullable ThreadAllocatedBytes threadAllocatedBytes,
            @Nullable Instrumentation instrumentation, File dataDir,
            @Nullable ExtraBootResourceFinder extraBootResourceFinder,
            ScheduledExecutorService scheduledExecutor) throws Exception {
        this.threadAllocatedBytes = threadAllocatedBytes;
        ConfigService configService = configModule.getConfigService();
        transactionRegistry = new TransactionRegistry();
        adviceCache = new AdviceCache(configModule.getPluginDescriptors(),
                configModule.getPluginJars(), configService.getInstrumentationConfigs(),
                instrumentation, dataDir);
        analyzedWorld = new AnalyzedWorld(adviceCache.getAdvisorsSupplier(),
                adviceCache.getMixinTypes(), extraBootResourceFinder);
        final MetricNameCache metricNameCache = new MetricNameCache();
        weavingTimerService = new WeavingTimerServiceImpl(transactionRegistry, metricNameCache);

        metricWrapperMethods =
                configModule.getConfigService().getAdvancedConfig().metricWrapperMethods();
        // instrumentation is null when debugging with IsolatedWeavingClassLoader
        // instead of javaagent
        if (instrumentation != null) {
            ClassFileTransformer transformer = new WeavingClassFileTransformer(
                    adviceCache.getMixinTypes(), adviceCache.getAdvisorsSupplier(), analyzedWorld,
                    weavingTimerService, metricWrapperMethods);
            PreInitializeWeavingClasses.preInitializeClasses();
            if (instrumentation.isRetransformClassesSupported()) {
                instrumentation.addTransformer(transformer, true);
                jvmRetransformClassesSupported = true;
            } else {
                instrumentation.addTransformer(transformer);
                jvmRetransformClassesSupported = false;
            }
        } else {
            jvmRetransformClassesSupported = false;
        }

        immedateTraceStoreWatcher = new ImmediateTraceStoreWatcher(scheduledExecutor,
                transactionRegistry, transactionCollector, configService, ticker);
        immedateTraceStoreWatcher.scheduleWithFixedDelay(scheduledExecutor, 0,
                ImmediateTraceStoreWatcher.PERIOD_MILLIS, MILLISECONDS);
        final UserProfileScheduler userProfileScheduler =
                new UserProfileScheduler(scheduledExecutor, configService);
        // this assignment to local variable is just to make checker framework happy
        // instead of directly accessing the field from inside the anonymous inner class below
        // (in which case checker framework thinks the field may still be null)
        final TransactionRegistry transactionRegistry = this.transactionRegistry;
        pluginServicesFactory = new PluginServicesFactory() {
            @Override
            public PluginServices create(@Nullable String pluginId) {
                return PluginServicesImpl.create(transactionRegistry, transactionCollector,
                        configModule.getConfigService(), metricNameCache, threadAllocatedBytes,
                        userProfileScheduler, ticker, clock, configModule.getPluginDescriptors(),
                        pluginId);
            }
        };
        PluginServicesRegistry.initStaticState(pluginServicesFactory);
    }

    public AnalyzedWorld getAnalyzedWorld() {
        return analyzedWorld;
    }

    public TransactionRegistry getTransactionRegistry() {
        return transactionRegistry;
    }

    public AdviceCache getAdviceCache() {
        return adviceCache;
    }

    public WeavingTimerService getWeavingTimerService() {
        return weavingTimerService;
    }

    public boolean isMetricWrapperMethods() {
        return metricWrapperMethods;
    }

    public boolean isJvmRetransformClassesSupported() {
        return jvmRetransformClassesSupported;
    }

    @OnlyUsedByTests
    public void reopen() {
        PluginServicesRegistry.initStaticState(pluginServicesFactory);
    }

    @OnlyUsedByTests
    public void close() {
        immedateTraceStoreWatcher.cancel();
        PluginServicesRegistry.clearStaticState();
    }
}
