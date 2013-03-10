/**
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
package io.informant.core;

import io.informant.api.PluginServices;
import io.informant.api.weaving.Mixin;
import io.informant.config.ConfigModule;
import io.informant.config.ConfigService;
import io.informant.config.PluginDescriptorCache;
import io.informant.core.trace.WeavingMetricNameImpl;
import io.informant.util.DaemonExecutors;
import io.informant.util.OnlyUsedByTests;
import io.informant.util.ThreadSafe;
import io.informant.weaving.Advice;
import io.informant.weaving.ParsedTypeCache;
import io.informant.weaving.WeavingClassFileTransformer;

import java.util.Random;
import java.util.concurrent.ScheduledExecutorService;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.base.Ticker;
import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import com.google.common.collect.Iterables;

/**
 * @author Trask Stalnaker
 * @since 0.5
 */
@ThreadSafe
public class CoreModule {

    private static final Logger logger = LoggerFactory.getLogger(CoreModule.class);

    private final ConfigModule configModule;

    private final TraceSink traceSink;
    private final ParsedTypeCache parsedTypeCache;
    private final WeavingMetricNameImpl weavingMetricName;
    private final TraceRegistry traceRegistry;
    private final MetricCache metricCache;
    private final Random random;

    private final ScheduledExecutorService scheduledExecutor;
    private final StuckTraceCollector stuckTraceCollector;
    private final CoarseGrainedProfiler coarseGrainedProfiler;
    private final FineGrainedProfiler fineGrainedProfiler;

    private final LoadingCache<String, PluginServices> pluginServices =
            CacheBuilder.newBuilder().build(new CacheLoader<String, PluginServices>() {
                @Override
                public PluginServices load(String pluginId) {
                    return new PluginServicesImpl(traceRegistry, traceSink,
                            configModule.getConfigService(), metricCache, fineGrainedProfiler,
                            configModule.getTicker(), configModule.getClock(), random,
                            weavingMetricName, configModule.getPluginDescriptorCache(), pluginId);
                }
            });

    public CoreModule(ConfigModule configModule, SnapshotSink snapshotSink) throws Exception {
        this.configModule = configModule;

        ConfigService configService = configModule.getConfigService();
        Ticker ticker = configModule.getTicker();

        parsedTypeCache = new ParsedTypeCache();
        weavingMetricName = new WeavingMetricNameImpl(ticker);
        traceRegistry = new TraceRegistry();
        metricCache = new MetricCache(ticker);
        random = new Random();

        scheduledExecutor = DaemonExecutors.newSingleThreadScheduledExecutor("Informant-Core");
        traceSink = new TraceSink(scheduledExecutor, configService, snapshotSink, ticker);
        stuckTraceCollector = new StuckTraceCollector(scheduledExecutor, traceRegistry, traceSink,
                configService, ticker);
        coarseGrainedProfiler = new CoarseGrainedProfiler(scheduledExecutor, traceRegistry,
                configService, ticker);
        fineGrainedProfiler = new FineGrainedProfiler(scheduledExecutor, configService, ticker);
    }

    public WeavingClassFileTransformer createWeavingClassFileTransformer() {
        PluginDescriptorCache pluginDescriptorCache = configModule.getPluginDescriptorCache();
        Mixin[] mixins = Iterables.toArray(pluginDescriptorCache.getMixins(), Mixin.class);
        Advice[] advisors = Iterables.toArray(pluginDescriptorCache.getAdvisors(), Advice.class);
        return new WeavingClassFileTransformer(mixins, advisors, parsedTypeCache,
                weavingMetricName);
    }

    public PluginServices getPluginServices(String pluginId) {
        return pluginServices.getUnchecked(pluginId);
    }

    public TraceSink getTraceSink() {
        return traceSink;
    }

    public ParsedTypeCache getParsedTypeCache() {
        return parsedTypeCache;
    }

    public WeavingMetricNameImpl getWeavingMetricName() {
        return weavingMetricName;
    }

    public TraceRegistry getTraceRegistry() {
        return traceRegistry;
    }

    public StuckTraceCollector getStuckTraceCollector() {
        return stuckTraceCollector;
    }

    public CoarseGrainedProfiler getCoarseGrainedProfiler() {
        return coarseGrainedProfiler;
    }

    public FineGrainedProfiler getFineGrainedProfiler() {
        return fineGrainedProfiler;
    }

    @OnlyUsedByTests
    public void close() {
        logger.debug("close()");
        scheduledExecutor.shutdownNow();
    }
}
