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

import checkers.nullness.quals.Nullable;
import com.google.common.base.Supplier;
import com.google.common.base.Suppliers;
import com.google.common.base.Ticker;
import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;

import org.glowroot.api.PluginServices;
import org.glowroot.common.Clock;
import org.glowroot.config.ConfigModule;
import org.glowroot.config.ConfigService;
import org.glowroot.jvm.ThreadAllocatedBytes;
import org.glowroot.markers.OnlyUsedByTests;
import org.glowroot.markers.ThreadSafe;
import org.glowroot.weaving.MetricTimerService;
import org.glowroot.weaving.ParsedTypeCache;
import org.glowroot.weaving.WeavingClassFileTransformer;

import static java.util.concurrent.TimeUnit.MILLISECONDS;

/**
 * @author Trask Stalnaker
 * @since 0.5
 */
@ThreadSafe
public class TraceModule {

    private final Ticker ticker;
    private final Clock clock;

    private final ConfigModule configModule;
    private final TraceCollector traceCollector;
    private final ParsedTypeCache parsedTypeCache;
    private final TraceRegistry traceRegistry;
    private final MetricNameCache metricNameCache;
    private final PointcutConfigAdviceCache pointcutConfigAdviceCache;
    private final MetricTimerService metricTimerService;
    @Nullable
    private final ThreadAllocatedBytes threadAllocatedBytes;

    private final StuckTraceWatcher stuckTraceWatcher;
    private final CoarseProfilerWatcher coarseProfilerWatcher;
    private final FineProfileScheduler fineProfileScheduler;

    private final boolean weavingDisabled;
    private final boolean metricWrapperMethodsDisabled;
    private final boolean jvmRetransformClassesSupported;

    private final LoadingCache<String, PluginServices> pluginServices =
            CacheBuilder.newBuilder().build(new CacheLoader<String, PluginServices>() {
                @Override
                public PluginServices load(String pluginId) {
                    return create(pluginId);
                }
            });

    private final Supplier<PluginServices> pluginServicesWithoutPlugin =
            Suppliers.memoize(new Supplier<PluginServices>() {
                @Override
                public PluginServices get() {
                    return create(null);
                }
            });

    public TraceModule(Ticker ticker, Clock clock, ConfigModule configModule,
            TraceCollector traceCollector, @Nullable ThreadAllocatedBytes threadAllocatedBytes,
            @Nullable Instrumentation instrumentation, ScheduledExecutorService scheduledExecutor) {
        this.ticker = ticker;
        this.clock = clock;
        this.configModule = configModule;
        this.traceCollector = traceCollector;
        this.threadAllocatedBytes = threadAllocatedBytes;
        ConfigService configService = configModule.getConfigService();
        parsedTypeCache = new ParsedTypeCache();
        traceRegistry = new TraceRegistry();
        metricNameCache = new MetricNameCache(ticker);
        pointcutConfigAdviceCache =
                new PointcutConfigAdviceCache(configService.getPointcutConfigs());
        metricTimerService = new MetricTimerServiceImpl(metricNameCache, traceRegistry);
        fineProfileScheduler =
                new FineProfileScheduler(scheduledExecutor, configService, ticker, new Random());
        stuckTraceWatcher = new StuckTraceWatcher(scheduledExecutor, traceRegistry,
                traceCollector, configService, ticker);
        coarseProfilerWatcher =
                new CoarseProfilerWatcher(scheduledExecutor, traceRegistry, configService, ticker);
        stuckTraceWatcher.scheduleAtFixedRate(scheduledExecutor, 0,
                StuckTraceWatcher.PERIOD_MILLIS, MILLISECONDS);
        coarseProfilerWatcher.scheduleAtFixedRate(scheduledExecutor, 0,
                CoarseProfilerWatcher.PERIOD_MILLIS, MILLISECONDS);

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
                    metricTimerService, metricWrapperMethodsDisabled);
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
    }

    public PluginServices getPluginServices(@Nullable String pluginId) {
        if (pluginId == null) {
            return pluginServicesWithoutPlugin.get();
        }
        return pluginServices.getUnchecked(pluginId);
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

    private PluginServices create(@Nullable String pluginId) {
        return PluginServicesImpl.create(traceRegistry, traceCollector,
                configModule.getConfigService(), metricNameCache, threadAllocatedBytes,
                fineProfileScheduler, ticker, clock, configModule.getPluginDescriptorCache(),
                pluginId);
    }

    @OnlyUsedByTests
    public void close() {
        stuckTraceWatcher.cancel();
        coarseProfilerWatcher.cancel();
    }
}
