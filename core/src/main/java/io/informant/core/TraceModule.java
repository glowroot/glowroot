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
import io.informant.config.PluginInfoCache;
import io.informant.util.ThreadSafe;
import io.informant.weaving.Advice;
import io.informant.weaving.ParsedTypeCache;
import io.informant.weaving.WeavingClassFileTransformer;

import java.util.Random;

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
public class TraceModule {

    private static final Logger logger = LoggerFactory.getLogger(TraceModule.class);

    private final ConfigModule configModule;
    private final TraceSink traceSink;

    private final ParsedTypeCache parsedTypeCache;
    private final WeavingMetricImpl weavingMetric;
    private final TraceRegistry traceRegistry;
    private final MetricCache metricCache;
    private final Random random;

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
                            weavingMetric, configModule.getPluginInfoCache(), pluginId);
                }
            });

    public TraceModule(ConfigModule configModule, TraceSink traceSink) throws Exception {
        this.configModule = configModule;
        this.traceSink = traceSink;

        ConfigService configService = configModule.getConfigService();
        Ticker ticker = configModule.getTicker();

        parsedTypeCache = new ParsedTypeCache();
        weavingMetric = new WeavingMetricImpl(ticker);
        traceRegistry = new TraceRegistry();
        metricCache = new MetricCache(ticker);
        random = new Random();

        stuckTraceCollector = new StuckTraceCollector(traceRegistry, traceSink, configService,
                ticker);
        coarseGrainedProfiler = new CoarseGrainedProfiler(traceRegistry, configService, ticker);
        fineGrainedProfiler = new FineGrainedProfiler(configService, ticker);
    }

    public WeavingClassFileTransformer createWeavingClassFileTransformer() {
        PluginInfoCache pluginInfoCache = configModule.getPluginInfoCache();
        Mixin[] mixins = Iterables.toArray(pluginInfoCache.getMixins(), Mixin.class);
        Advice[] advisors = Iterables.toArray(pluginInfoCache.getAdvisors(), Advice.class);
        return new WeavingClassFileTransformer(mixins, advisors, parsedTypeCache, weavingMetric);
    }

    public PluginServices getPluginServices(String pluginId) {
        return pluginServices.getUnchecked(pluginId);
    }

    public void close() {
        logger.debug("close()");
        stuckTraceCollector.close();
        coarseGrainedProfiler.close();
        fineGrainedProfiler.close();
    }

    public ParsedTypeCache getParsedTypeCache() {
        return parsedTypeCache;
    }

    public WeavingMetricImpl getWeavingMetric() {
        return weavingMetric;
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
}
