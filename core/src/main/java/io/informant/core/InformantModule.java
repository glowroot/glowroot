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
import io.informant.core.weaving.WeavingMetric;
import io.informant.local.log.LogMessageDao;
import io.informant.local.log.LogMessageSinkLocal;
import io.informant.local.trace.TraceSinkLocal;
import io.informant.local.trace.TraceSnapshotDao;

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
import com.google.inject.ProvisionException;
import com.google.inject.Singleton;
import com.google.inject.assistedinject.FactoryModuleBuilder;
import com.google.inject.name.Names;

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

    private final File dataDir;
    private final DataSource dataSource;
    private final PluginInfoCache pluginInfoCache;
    private final ConfigService configService;
    private final RollingFile rollingFile;

    // singletons that throw checked exceptions in their constructor are initialized here so the
    // checked exceptions can very explicitly bubble up and be used to disable informant without
    // harming the monitored app
    InformantModule(@ReadOnly Map<String, String> properties) throws SQLException, IOException {
        this.properties = ImmutableMap.copyOf(properties);
        dataDir = DataDir.getDataDir(properties);
        // mem db is only used for testing (by informant-testkit)
        String h2MemDb = properties.get("internal.h2.memdb");
        if (Boolean.parseBoolean(h2MemDb)) {
            dataSource = new DataSource();
        } else {
            dataSource = new DataSource(new File(dataDir, "informant.h2.db"));
        }
        pluginInfoCache = new PluginInfoCache();
        configService = new ConfigService(dataDir, pluginInfoCache);
        int rollingSizeMb = configService.getGeneralConfig().getRollingSizeMb();
        rollingFile = new RollingFile(new File(dataDir, "informant.rolling.db"),
                rollingSizeMb * 1024);
    }

    @Override
    protected void configure() {
        logger.debug("configure()");
        bind(File.class).annotatedWith(Names.named("data.dir")).toInstance(dataDir);
        bind(DataSource.class).toInstance(dataSource);
        bind(PluginInfoCache.class).toInstance(pluginInfoCache);
        bind(ConfigService.class).toInstance(configService);
        bind(RollingFile.class).toInstance(rollingFile);
        bind(WeavingMetric.class).to(WeavingMetricImpl.class).in(Singleton.class);
        bind(Clock.class).toInstance(Clock.systemClock());
        bind(Ticker.class).toInstance(Ticker.systemTicker());
        install(new LocalModule(properties));
        install(new FactoryModuleBuilder().build(PluginServicesImplFactory.class));
        // this needs to be set early since both async-http-client and netty depend on it
        ThreadRenamingRunnable.setThreadNameDeterminer(new ThreadNameDeterminer() {
            public String determineThreadName(String currentThreadName, String proposedThreadName) {
                return "Informant-" + proposedThreadName;
            }
        });
    }

    static void start(Injector injector) throws ProvisionException {
        logger.debug("start()");
        // these three can throw SQLException (wrapped in guice ProvisionException)
        injector.getInstance(DataSource.class);
        injector.getInstance(LogMessageDao.class);
        injector.getInstance(TraceSnapshotDao.class);
        // this can throw IOException (wrapped in guice ProvisionException)
        injector.getInstance(RollingFile.class);
        // these have threads that need to be started
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
