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

import javax.annotation.Nullable;
import javax.annotation.concurrent.ThreadSafe;

import com.google.common.base.Ticker;

import org.glowroot.api.PluginServices;
import org.glowroot.common.Clock;
import org.glowroot.config.ConfigModule;
import org.glowroot.config.ConfigService;
import org.glowroot.jvm.ThreadAllocatedBytes;
import org.glowroot.markers.OnlyUsedByTests;
import org.glowroot.trace.PluginServicesRegistry.PluginServicesFactory;
import org.glowroot.weaving.MetricTimerService;
import org.glowroot.weaving.ParsedTypeCache;
import org.glowroot.weaving.PreInitializeWeavingClasses;
import org.glowroot.weaving.WeavingClassFileTransformer;

import static java.util.concurrent.TimeUnit.MILLISECONDS;

/**
 * @author Trask Stalnaker
 * @since 0.5
 */
@ThreadSafe
public class TraceModule {

    private final ParsedTypeCache parsedTypeCache;
    private final TraceRegistry traceRegistry;
    private final PointcutConfigAdviceCache pointcutConfigAdviceCache;
    private final MetricTimerService metricTimerService;
    @Nullable
    private final ThreadAllocatedBytes threadAllocatedBytes;

    private final StuckTraceWatcher stuckTraceWatcher;
    private final CoarseProfilerWatcher coarseProfilerWatcher;

    private final boolean weavingDisabled;
    private final boolean metricWrapperMethodsDisabled;
    private final boolean jvmRetransformClassesSupported;

    private final PluginServicesFactory pluginServicesFactory;

    public TraceModule(final Ticker ticker, final Clock clock, final ConfigModule configModule,
            final TraceCollector traceCollector,
            final @Nullable ThreadAllocatedBytes threadAllocatedBytes,
            @Nullable Instrumentation instrumentation, ScheduledExecutorService scheduledExecutor) {
        this.threadAllocatedBytes = threadAllocatedBytes;
        ConfigService configService = configModule.getConfigService();
        parsedTypeCache = new ParsedTypeCache();
        traceRegistry = new TraceRegistry();
        pointcutConfigAdviceCache =
                new PointcutConfigAdviceCache(configService.getPointcutConfigs());
        metricTimerService = new MetricTimerServiceImpl(traceRegistry);

        weavingDisabled = configModule.getConfigService().getAdvancedConfig().isWeavingDisabled();
        metricWrapperMethodsDisabled = configModule.getConfigService().getAdvancedConfig()
                .isMetricWrapperMethodsDisabled();
        // instrumentation is null when debugging with IsolatedWeavingClassLoader
        // instead of javaagent
        if (instrumentation != null && !weavingDisabled) {
            ClassFileTransformer transformer = new WeavingClassFileTransformer(
                    configModule.getPluginDescriptorCache().getMixinTypes(),
                    configModule.getPluginDescriptorCache().getAdvisors(),
                    pointcutConfigAdviceCache.getAdvisorsSupplier(), parsedTypeCache,
                    metricTimerService, !metricWrapperMethodsDisabled);
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
        coarseProfilerWatcher =
                new CoarseProfilerWatcher(scheduledExecutor, traceRegistry, configService, ticker);
        stuckTraceWatcher.scheduleAtFixedRate(scheduledExecutor, 0,
                StuckTraceWatcher.PERIOD_MILLIS, MILLISECONDS);
        coarseProfilerWatcher.scheduleAtFixedRate(scheduledExecutor, 0,
                CoarseProfilerWatcher.PERIOD_MILLIS, MILLISECONDS);
        final FineProfileScheduler fineProfileScheduler =
                new FineProfileScheduler(scheduledExecutor, configService, ticker, new Random());
        // this assignment to local variable is just to make checker framework happy
        // instead of directly accessing the field from inside the anonymous inner class below
        // (in which case checker framework thinks the field may still be null)
        final TraceRegistry traceRegistry = this.traceRegistry;
        pluginServicesFactory = new PluginServicesFactory() {
            @Override
            public PluginServices create(@Nullable String pluginId) {
                return PluginServicesImpl.create(traceRegistry, traceCollector,
                        configModule.getConfigService(), threadAllocatedBytes,
                        fineProfileScheduler, ticker, clock,
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

    public PointcutConfigAdviceCache getPointcutConfigAdviceCache() {
        return pointcutConfigAdviceCache;
    }

    public MetricTimerService getMetricTimerService() {
        return metricTimerService;
    }

    public boolean isWeavingDisabled() {
        return weavingDisabled;
    }

    public boolean isMetricWrapperMethodsDisabled() {
        return metricWrapperMethodsDisabled;
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
        coarseProfilerWatcher.cancel();
        PluginServicesRegistry.clearStaticState();
    }
}
