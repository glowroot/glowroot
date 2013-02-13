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

import io.informant.api.Logger;
import io.informant.api.LoggerFactory;
import io.informant.core.PluginServicesImpl.PluginServicesImplFactory;
import io.informant.core.config.ConfigService;
import io.informant.core.config.PluginInfoCache;
import io.informant.core.trace.CoarseGrainedProfiler;
import io.informant.core.trace.FineGrainedProfiler;
import io.informant.core.trace.StuckTraceCollector;
import io.informant.core.trace.WeavingMetricImpl;
import io.informant.core.util.Clock;
import io.informant.core.util.DataSource;
import io.informant.core.util.RollingFile;
import io.informant.core.util.ThreadSafe;
import io.informant.core.weaving.ParsedTypeCache;
import io.informant.local.log.LogMessageSinkLocal;
import io.informant.local.trace.TraceSinkLocal;

import java.io.File;
import java.io.IOException;
import java.sql.SQLException;
import java.util.Map;

import org.jboss.netty.util.ThreadNameDeterminer;
import org.jboss.netty.util.ThreadRenamingRunnable;

import checkers.igj.quals.ReadOnly;

import com.google.common.base.Ticker;
import com.google.common.collect.ImmutableMap;
import com.google.inject.AbstractModule;
import com.google.inject.Injector;
import com.google.inject.Provides;
import com.google.inject.Singleton;
import com.google.inject.assistedinject.FactoryModuleBuilder;
import com.google.inject.name.Named;

/**
 * Primary Guice module.
 * 
 * @author Trask Stalnaker
 * @since 0.5
 */
@ThreadSafe
class InformantModule extends AbstractModule {

    private static final Logger logger = LoggerFactory.getLogger(InformantModule.class);

    private final ImmutableMap<String, String> properties;
    private final PluginInfoCache pluginInfoCache;
    private final ParsedTypeCache parsedTypeCache;
    private final WeavingMetricImpl weavingMetric;

    InformantModule(@ReadOnly Map<String, String> properties, PluginInfoCache pluginInfoCache,
            ParsedTypeCache parsedTypeCache, WeavingMetricImpl weavingMetric) {
        this.properties = ImmutableMap.copyOf(properties);
        this.pluginInfoCache = pluginInfoCache;
        this.parsedTypeCache = parsedTypeCache;
        this.weavingMetric = weavingMetric;
    }

    @Override
    protected void configure() {
        logger.debug("configure()");
        install(new LocalModule(properties));
        install(new FactoryModuleBuilder().build(PluginServicesImplFactory.class));
        // this needs to be set early since both async-http-client and netty depend on it
        ThreadRenamingRunnable.setThreadNameDeterminer(new ThreadNameDeterminer() {
            public String determineThreadName(String currentThreadName, String proposedThreadName) {
                return "Informant-" + proposedThreadName;
            }
        });
    }

    @Provides
    @Singleton
    PluginInfoCache providesPluginInfoCache() {
        return pluginInfoCache;
    }

    @Provides
    @Singleton
    ParsedTypeCache providesParsedTypeCache() {
        return parsedTypeCache;
    }

    @Provides
    @Singleton
    WeavingMetricImpl providesWeavingMetricImpl() {
        return weavingMetric;
    }

    @Provides
    @Singleton
    DataSource providesDataSource(@Named("data.dir") File dataDir) {
        // mem db is only used for testing (by informant-testkit)
        String h2MemDb = properties.get("internal.h2.memdb");
        if (Boolean.parseBoolean(h2MemDb)) {
            return new DataSource();
        } else {
            return new DataSource(new File(dataDir, "informant.h2.db"));
        }
    }

    @Provides
    @Singleton
    RollingFile providesRollingFile(ConfigService configService, @Named("data.dir") File dataDir) {
        int rollingSizeMb = configService.getGeneralConfig().getRollingSizeMb();
        try {
            // 1gb
            return new RollingFile(new File(dataDir, "informant.rolling.db"),
                    rollingSizeMb * 1024);
        } catch (IOException e) {
            logger.error(e.getMessage(), e);
            throw new IllegalStateException(e);
        }
    }

    @Provides
    @Singleton
    @Named("data.dir")
    File providesDataDir() {
        return DataDir.getDataDir(properties);
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

    static void start(Injector injector) {
        logger.debug("start()");
        injector.getInstance(StuckTraceCollector.class);
        injector.getInstance(CoarseGrainedProfiler.class);
        LocalModule.start(injector);
    }

    static void close(Injector injector) {
        logger.debug("close()");
        LocalModule.close(injector);
        injector.getInstance(StuckTraceCollector.class).close();
        injector.getInstance(CoarseGrainedProfiler.class).close();
        injector.getInstance(FineGrainedProfiler.class).close();
        injector.getInstance(TraceSinkLocal.class).close();
        injector.getInstance(LogMessageSinkLocal.class).close();
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
}
