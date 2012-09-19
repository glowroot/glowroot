/**
 * Copyright 2011-2012 the original author or authors.
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
package org.informantproject.core;

import java.io.File;
import java.io.IOException;
import java.sql.SQLException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ScheduledExecutorService;

import org.informantproject.core.PluginServicesImpl.PluginServicesImplFactory;
import org.informantproject.core.config.ConfigService;
import org.informantproject.core.metric.MetricCollector;
import org.informantproject.core.trace.CoarseGrainedProfiler;
import org.informantproject.core.trace.FineGrainedProfiler;
import org.informantproject.core.trace.StuckTraceCollector;
import org.informantproject.core.trace.WeavingMetricImpl;
import org.informantproject.core.util.Clock;
import org.informantproject.core.util.DaemonExecutors;
import org.informantproject.core.util.DataSource;
import org.informantproject.core.util.RollingFile;
import org.informantproject.local.trace.TraceSinkLocal;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.base.Ticker;
import com.google.inject.AbstractModule;
import com.google.inject.Injector;
import com.google.inject.Provides;
import com.google.inject.Singleton;
import com.google.inject.assistedinject.FactoryModuleBuilder;
import com.ning.http.client.AsyncHttpClient;
import com.ning.http.client.AsyncHttpClientConfig;
import com.ning.http.client.providers.netty.NettyAsyncHttpProviderConfig;

/**
 * Primary Guice module.
 * 
 * @author Trask Stalnaker
 * @since 0.5
 */
class InformantModule extends AbstractModule {

    private static final Logger logger = LoggerFactory.getLogger(InformantModule.class);

    // TODO revisit this
    private static final boolean USE_NETTY_BLOCKING_IO = false;

    private final AgentArgs agentArgs;
    private final WeavingMetricImpl weavingMetric;

    InformantModule(AgentArgs agentArgs, WeavingMetricImpl weavingMetric) {
        this.agentArgs = agentArgs;
        this.weavingMetric = weavingMetric;
    }

    @Override
    protected void configure() {
        logger.debug("configure()");
        install(new LocalModule(agentArgs.getUiPort()));
        install(new FactoryModuleBuilder().build(PluginServicesImplFactory.class));
    }

    static void start(Injector injector) {
        logger.debug("start()");
        injector.getInstance(StuckTraceCollector.class);
        injector.getInstance(CoarseGrainedProfiler.class);
        injector.getInstance(MetricCollector.class);
        LocalModule.start(injector);
    }

    static void close(Injector injector) {
        logger.debug("close()");
        LocalModule.close(injector);
        injector.getInstance(StuckTraceCollector.class).close();
        injector.getInstance(CoarseGrainedProfiler.class).close();
        injector.getInstance(FineGrainedProfiler.class).close();
        injector.getInstance(MetricCollector.class).close();
        injector.getInstance(TraceSinkLocal.class).close();
        injector.getInstance(AsyncHttpClient.class).close();
        try {
            injector.getInstance(DataSource.class).close();
        } catch (SQLException e) {
            // warning only since it occurs during shutdown anyways
            logger.warn(e.getMessage(), e);
        }
        try {
            injector.getInstance(RollingFile.class).close();
        } catch (IOException e) {
            // warning only since it occurs during shutdown anyways
            logger.warn(e.getMessage(), e);
        }
    }

    @Provides
    @Singleton
    WeavingMetricImpl providesWeavingMetricImpl() {
        return weavingMetric;
    }

    @Provides
    @Singleton
    DataSource providesDataSource() {
        return new DataSource(new File(agentArgs.getDataDir(), "informant.h2.db"),
                agentArgs.isH2MemDb());
    }

    @Provides
    @Singleton
    RollingFile providesRollingFile(ConfigService configService) {
        int rollingSizeMb = configService.getCoreConfig().getRollingSizeMb();
        try {
            // 1gb
            return new RollingFile(new File(agentArgs.getDataDir(), "informant.rolling.db"),
                    rollingSizeMb * 1024);
        } catch (IOException e) {
            logger.error(e.getMessage(), e);
            throw new IllegalStateException(e);
        }
    }

    @Provides
    @Singleton
    AsyncHttpClient providesAsyncHttpClient() {
        ExecutorService executorService = DaemonExecutors
                .newCachedThreadPool("Informant-AsyncHttpClient");
        ScheduledExecutorService scheduledExecutor = DaemonExecutors
                .newSingleThreadScheduledExecutor("Informant-AsyncHttpClient-Reaper");
        AsyncHttpClientConfig.Builder builder = new AsyncHttpClientConfig.Builder()
                .setMaxRequestRetry(0)
                .setExecutorService(executorService)
                .setScheduledExecutorService(scheduledExecutor);
        NettyAsyncHttpProviderConfig providerConfig = new NettyAsyncHttpProviderConfig();
        if (USE_NETTY_BLOCKING_IO) {
            providerConfig.addProperty(NettyAsyncHttpProviderConfig.USE_BLOCKING_IO, true);
        }
        providerConfig.addProperty(NettyAsyncHttpProviderConfig.BOSS_EXECUTOR_SERVICE,
                executorService);
        builder.setAsyncHttpClientProviderConfig(providerConfig);
        return new AsyncHttpClient(builder.build());
    }

    @Provides
    @Singleton
    static Clock providesClock() {
        return Clock.systemClock();
    }

    @Provides
    @Singleton
    static Ticker providesTicker() {
        return Ticker.systemTicker();
    }
}
