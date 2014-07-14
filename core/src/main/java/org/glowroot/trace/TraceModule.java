/*
 * Copyright 2011-2014 the original author or authors.
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
package org.glowroot.trace;

import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.util.Random;
import java.util.concurrent.ScheduledExecutorService;

import org.checkerframework.checker.nullness.qual.Nullable;

import org.glowroot.api.PluginServices;
import org.glowroot.common.Clock;
import org.glowroot.common.Ticker;
import org.glowroot.config.ConfigModule;
import org.glowroot.config.ConfigService;
import org.glowroot.jvm.ThreadAllocatedBytes;
import org.glowroot.markers.OnlyUsedByTests;
import org.glowroot.markers.ThreadSafe;
import org.glowroot.trace.PluginServicesRegistry.PluginServicesFactory;
import org.glowroot.weaving.ParsedTypeCache;
import org.glowroot.weaving.PreInitializeWeavingClasses;
import org.glowroot.weaving.WeavingClassFileTransformer;
import org.glowroot.weaving.WeavingTimerService;

import static java.util.concurrent.TimeUnit.MILLISECONDS;

/**
 * @author Trask Stalnaker
 * @since 0.5
 */
@ThreadSafe
public class TraceModule {

    private final ParsedTypeCache parsedTypeCache;
    private final TraceRegistry traceRegistry;
    private final AdviceCache adviceCache;
    private final WeavingTimerService weavingTimerService;
    @Nullable
    private final ThreadAllocatedBytes threadAllocatedBytes;

    private final StuckTraceWatcher stuckTraceWatcher;
    private final OutlierProfilerWatcher outlierProfilerWatcher;

    private final boolean weavingDisabled;
    private final boolean traceMetricWrapperMethods;
    private final boolean jvmRetransformClassesSupported;

    private final PluginServicesFactory pluginServicesFactory;

    public TraceModule(final Clock clock, final Ticker ticker, final ConfigModule configModule,
            final TraceCollector traceCollector,
            final @Nullable ThreadAllocatedBytes threadAllocatedBytes,
            @Nullable Instrumentation instrumentation, ScheduledExecutorService scheduledExecutor) {
        this.threadAllocatedBytes = threadAllocatedBytes;
        ConfigService configService = configModule.getConfigService();
        traceRegistry = new TraceRegistry();
        adviceCache = new AdviceCache(configModule.getPluginDescriptorCache().getAdvisors(),
                configService.getPointcutConfigs());
        parsedTypeCache = new ParsedTypeCache(adviceCache.getAdvisorsSupplier(), configModule
                .getPluginDescriptorCache().getMixinTypes());
        final TraceMetricNameCache traceMetricNameCache = new TraceMetricNameCache();
        weavingTimerService = new WeavingTimerServiceImpl(traceRegistry, traceMetricNameCache);

        weavingDisabled = configModule.getConfigService().getAdvancedConfig().isWeavingDisabled();
        traceMetricWrapperMethods =
                configModule.getConfigService().getAdvancedConfig().isTraceMetricWrapperMethods();
        // instrumentation is null when debugging with IsolatedWeavingClassLoader
        // instead of javaagent
        if (instrumentation != null && !weavingDisabled) {
            ClassFileTransformer transformer = new WeavingClassFileTransformer(
                    configModule.getPluginDescriptorCache().getMixinTypes(),
                    adviceCache.getAdvisorsSupplier(), parsedTypeCache, weavingTimerService,
                    traceMetricWrapperMethods);
            PreInitializeWeavingClasses.preInitializeClasses(TraceModule.class.getClassLoader());
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

        stuckTraceWatcher = new StuckTraceWatcher(scheduledExecutor, traceRegistry,
                traceCollector, configService, ticker);
        outlierProfilerWatcher =
                new OutlierProfilerWatcher(scheduledExecutor, traceRegistry, configService, ticker);
        stuckTraceWatcher.scheduleWithFixedDelay(scheduledExecutor, 0,
                StuckTraceWatcher.PERIOD_MILLIS, MILLISECONDS);
        outlierProfilerWatcher.scheduleWithFixedDelay(scheduledExecutor, 0,
                OutlierProfilerWatcher.PERIOD_MILLIS, MILLISECONDS);
        final ProfileScheduler profileScheduler =
                new ProfileScheduler(scheduledExecutor, configService, ticker, new Random());
        // this assignment to local variable is just to make checker framework happy
        // instead of directly accessing the field from inside the anonymous inner class below
        // (in which case checker framework thinks the field may still be null)
        final TraceRegistry traceRegistry = this.traceRegistry;
        pluginServicesFactory = new PluginServicesFactory() {
            @Override
            public PluginServices create(@Nullable String pluginId) {
                return PluginServicesImpl.create(traceRegistry, traceCollector,
                        configModule.getConfigService(), traceMetricNameCache,
                        threadAllocatedBytes, profileScheduler, ticker, clock,
                        configModule.getPluginDescriptorCache(), pluginId);
            }
        };
        PluginServicesRegistry.initStaticState(pluginServicesFactory);
    }

    public ParsedTypeCache getParsedTypeCache() {
        return parsedTypeCache;
    }

    public TraceRegistry getTraceRegistry() {
        return traceRegistry;
    }

    public AdviceCache getAdviceCache() {
        return adviceCache;
    }

    public WeavingTimerService getWeavingTimerService() {
        return weavingTimerService;
    }

    public boolean isWeavingDisabled() {
        return weavingDisabled;
    }

    public boolean isTraceMetricWrapperMethods() {
        return traceMetricWrapperMethods;
    }

    public boolean isJvmRetransformClassesSupported() {
        return jvmRetransformClassesSupported;
    }

    @OnlyUsedByTests
    public void initStaticState() {
        PluginServicesRegistry.initStaticState(pluginServicesFactory);
    }

    @OnlyUsedByTests
    public void close() {
        stuckTraceWatcher.cancel();
        outlierProfilerWatcher.cancel();
        PluginServicesRegistry.clearStaticState();
    }
}
