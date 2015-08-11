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

import com.google.common.base.Ticker;

import org.glowroot.api.internal.GlowrootService;
import org.glowroot.common.Clock;
import org.glowroot.config.ConfigModule;
import org.glowroot.config.ConfigService;
import org.glowroot.jvm.ThreadAllocatedBytes;
import org.glowroot.markers.OnlyUsedByTests;
import org.glowroot.plugin.api.transaction.TransactionService;
import org.glowroot.transaction.ServiceRegistryImpl.ConfigServiceFactory;
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

    private final ImmediateTraceStoreWatcher immedateTraceStoreWatcher;

    private final boolean timerWrapperMethods;
    private final boolean jvmRetransformClassesSupported;

    private final ServiceRegistryImpl serviceRegistry;

    public TransactionModule(final Clock clock, final Ticker ticker,
            final ConfigModule configModule, final TransactionCollector transactionCollector,
            final @Nullable ThreadAllocatedBytes threadAllocatedBytes,
            @Nullable Instrumentation instrumentation, File baseDir,
            @Nullable ExtraBootResourceFinder extraBootResourceFinder,
            ScheduledExecutorService scheduledExecutor) throws Exception {
        ConfigService configService = configModule.getConfigService();
        transactionRegistry = new TransactionRegistry();
        adviceCache =
                new AdviceCache(configModule.getPluginDescriptors(), configModule.getPluginJars(),
                        configService.getInstrumentationConfigs(), instrumentation, baseDir);
        analyzedWorld = new AnalyzedWorld(adviceCache.getAdvisorsSupplier(),
                adviceCache.getShimTypes(), adviceCache.getMixinTypes(), extraBootResourceFinder);
        final TimerNameCache timerNameCache = new TimerNameCache();
        weavingTimerService =
                new WeavingTimerServiceImpl(transactionRegistry, configService, timerNameCache);

        timerWrapperMethods =
                configModule.getConfigService().getAdvancedConfig().timerWrapperMethods();
        // instrumentation is null when debugging with IsolatedWeavingClassLoader
        // instead of javaagent
        if (instrumentation != null) {
            ClassFileTransformer transformer =
                    new WeavingClassFileTransformer(adviceCache.getShimTypes(),
                            adviceCache.getMixinTypes(), adviceCache.getAdvisorsSupplier(),
                            analyzedWorld, weavingTimerService, timerWrapperMethods);
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
        UserProfileScheduler userProfileScheduler =
                new UserProfileScheduler(scheduledExecutor, configService);
        GlowrootService glowrootService =
                new GlowrootServiceImpl(transactionRegistry, userProfileScheduler);
        TransactionService transactionService = TransactionServiceImpl.create(transactionRegistry,
                transactionCollector, configModule.getConfigService(), timerNameCache,
                threadAllocatedBytes, userProfileScheduler, ticker, clock);
        ConfigServiceFactory configServiceFactory = new ConfigServiceFactory() {
            @Override
            public org.glowroot.plugin.api.config.ConfigService create(String pluginId) {
                return ConfigServiceImpl.create(configModule.getConfigService(),
                        configModule.getPluginDescriptors(), pluginId);
            }
        };
        serviceRegistry =
                ServiceRegistryImpl.init(glowrootService, transactionService, configServiceFactory);
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

    public boolean isTimerWrapperMethods() {
        return timerWrapperMethods;
    }

    public boolean isJvmRetransformClassesSupported() {
        return jvmRetransformClassesSupported;
    }

    @OnlyUsedByTests
    public void reopen() throws Exception {
        ServiceRegistryImpl.reopen(serviceRegistry);
    }

    @OnlyUsedByTests
    public void close() {
        immedateTraceStoreWatcher.cancel();
    }
}
