/*
 * Copyright 2011-2013 the original author or authors.
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
package io.informant.trace;

import java.util.Random;
import java.util.concurrent.ScheduledExecutorService;

import com.google.common.base.Ticker;
import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;

import io.informant.api.PluginServices;
import io.informant.common.Clock;
import io.informant.config.ConfigModule;
import io.informant.config.ConfigService;
import io.informant.markers.OnlyUsedByTests;
import io.informant.markers.ThreadSafe;
import io.informant.weaving.MetricTimerService;
import io.informant.weaving.ParsedTypeCache;
import io.informant.weaving.WeavingClassFileTransformer;

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
    private final AdhocAdviceCache adhocAdviceCache;
    private final MetricTimerService metricTimerService;

    private final StuckTraceCollector stuckTraceCollector;
    private final CoarseProfiler coarseProfiler;
    private final FineProfileScheduler fineProfileScheduler;

    private final LoadingCache<String, PluginServices> pluginServices =
            CacheBuilder.newBuilder().build(new CacheLoader<String, PluginServices>() {
                @Override
                public PluginServices load(String pluginId) {
                    return new PluginServicesImpl(traceRegistry, traceCollector,
                            configModule.getConfigService(), metricNameCache, fineProfileScheduler,
                            ticker, clock, configModule.getPluginDescriptorCache(), pluginId);
                }
            });

    public TraceModule(Ticker ticker, Clock clock, ConfigModule configModule,
            TraceCollector traceCollector, ScheduledExecutorService scheduledExecutor) {
        this.ticker = ticker;
        this.clock = clock;
        this.configModule = configModule;
        this.traceCollector = traceCollector;
        ConfigService configService = configModule.getConfigService();
        parsedTypeCache = new ParsedTypeCache();
        traceRegistry = new TraceRegistry();
        metricNameCache = new MetricNameCache(ticker);
        adhocAdviceCache = new AdhocAdviceCache(configService.getAdhocPointcutConfigs());
        metricTimerService = new MetricTimerServiceImpl(metricNameCache, traceRegistry);

        fineProfileScheduler = new FineProfileScheduler(scheduledExecutor, configService, ticker,
                new Random());
        stuckTraceCollector = new StuckTraceCollector(scheduledExecutor, traceRegistry,
                traceCollector,
                configService, ticker);
        coarseProfiler = new CoarseProfiler(scheduledExecutor, traceRegistry, configService,
                ticker);
        stuckTraceCollector.start();
        coarseProfiler.start();
    }

    public WeavingClassFileTransformer createWeavingClassFileTransformer() {
        return new WeavingClassFileTransformer(
                configModule.getPluginDescriptorCache().getMixinTypes(),
                configModule.getPluginDescriptorCache().getAdvisors(),
                adhocAdviceCache.getAdhocAdvisorsSupplier(), parsedTypeCache,
                metricTimerService, configModule.getConfigService().getGeneralConfig()
                        .isGenerateMetricNameWrapperMethods());
    }

    public PluginServices getPluginServices(String pluginId) {
        return pluginServices.getUnchecked(pluginId);
    }

    public ParsedTypeCache getParsedTypeCache() {
        return parsedTypeCache;
    }

    public TraceRegistry getTraceRegistry() {
        return traceRegistry;
    }

    public AdhocAdviceCache getDynamicAdviceCache() {
        return adhocAdviceCache;
    }

    public MetricTimerService getMetricTimerService() {
        return metricTimerService;
    }

    @OnlyUsedByTests
    public void close() {
        stuckTraceCollector.close();
        coarseProfiler.close();
    }
}
