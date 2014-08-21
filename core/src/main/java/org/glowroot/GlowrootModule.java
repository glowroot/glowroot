/*
 * Copyright 2013-2014 the original author or authors.
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
package org.glowroot;

import java.io.File;
import java.io.IOException;
import java.lang.instrument.Instrumentation;
import java.net.URISyntaxException;
import java.sql.SQLException;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.jar.JarFile;

import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.joran.JoranConfigurator;
import ch.qos.logback.core.joran.spi.JoranException;
import ch.qos.logback.core.util.StatusPrinter;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.io.Resources;
import com.google.common.util.concurrent.ThreadFactoryBuilder;
import org.checkerframework.checker.nullness.qual.MonotonicNonNull;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.glowroot.collector.CollectorModule;
import org.glowroot.common.Clock;
import org.glowroot.common.SpyingLogbackFilter;
import org.glowroot.common.Ticker;
import org.glowroot.config.ConfigModule;
import org.glowroot.config.PluginResourceFinder;
import org.glowroot.jvm.JvmModule;
import org.glowroot.local.store.DataSource.DataSourceLockedException;
import org.glowroot.local.store.StorageModule;
import org.glowroot.local.ui.LocalUiModule;
import org.glowroot.markers.OnlyUsedByTests;
import org.glowroot.markers.ThreadSafe;
import org.glowroot.trace.TraceCollector;
import org.glowroot.trace.TraceModule;
import org.glowroot.trace.model.Trace;

/**
 * @author Trask Stalnaker
 * @since 0.5
 */
@VisibleForTesting
@ThreadSafe
public class GlowrootModule {

    private static final Logger logger = LoggerFactory.getLogger(GlowrootModule.class);

    private static final boolean dummyTicker = Boolean.getBoolean("glowroot.internal.dummyTicker");

    private final Ticker ticker;
    private final Clock clock;
    private final ScheduledExecutorService scheduledExecutor;
    private final ConfigModule configModule;
    private final StorageModule storageModule;
    private final CollectorModule collectorModule;
    private final TraceModule traceModule;
    private final LocalUiModule uiModule;
    private final File dataDir;

    // this is used by tests to check that no warnings/errors are logged during tests
    private final boolean loggingSpy;

    GlowrootModule(File dataDir, Map<String, String> properties,
            @Nullable Instrumentation instrumentation, @Nullable File glowrootJarFile,
            String version, boolean viewerModeEnabled) throws StartupFailedException {

        loggingSpy = Boolean.valueOf(properties.get("internal.logging.spy"));
        initStaticLoggerState(dataDir, loggingSpy);
        if (dummyTicker) {
            ticker = new Ticker() {
                @Override
                public long read() {
                    return 0;
                }
            };
        } else {
            ticker = Ticker.systemTicker();
        }
        clock = Clock.systemClock();

        PluginResourceFinder pluginResourceFinder = null;
        if (instrumentation != null && glowrootJarFile != null) {
            try {
                pluginResourceFinder = new PluginResourceFinder(glowrootJarFile);
                for (File pluginJar : pluginResourceFinder.getPluginJars()) {
                    instrumentation.appendToBootstrapClassLoaderSearch(new JarFile(pluginJar));
                }
            } catch (IOException e) {
                throw new StartupFailedException(e);
            } catch (URISyntaxException e) {
                throw new StartupFailedException(e);
            }
        }

        ThreadFactory threadFactory = new ThreadFactoryBuilder().setDaemon(true)
                .setNameFormat("Glowroot-Background-%d").build();
        scheduledExecutor = Executors.newScheduledThreadPool(2, threadFactory);
        JvmModule jvmModule = new JvmModule();
        try {
            configModule = new ConfigModule(dataDir, instrumentation, pluginResourceFinder,
                    viewerModeEnabled);
        } catch (IOException e) {
            throw new StartupFailedException(e);
        } catch (URISyntaxException e) {
            throw new StartupFailedException(e);
        }
        // trace module needs to be started as early as possible, so that weaving will be applied to
        // as many classes as possible
        // in particular, it needs to be started before StorageModule which uses shaded H2, which
        // loads java.sql.DriverManager, which loads 3rd party jdbc drivers found via
        // services/java.sql.Driver, and those drivers need to be woven
        TraceCollectorProxy traceCollectorProxy = new TraceCollectorProxy();
        try {
            traceModule = new TraceModule(clock, ticker, configModule, traceCollectorProxy,
                    jvmModule.getThreadAllocatedBytes().getService(), instrumentation, dataDir,
                    scheduledExecutor);
        } catch (IOException e) {
            throw new StartupFailedException(e);
        }
        try {
            storageModule = new StorageModule(dataDir, properties, ticker, clock, configModule,
                    scheduledExecutor, viewerModeEnabled);
        } catch (DataSourceLockedException e) {
            throw new StartupFailedException(e, true);
        } catch (SQLException e) {
            throw new StartupFailedException(e);
        } catch (IOException e) {
            throw new StartupFailedException(e);
        }
        collectorModule = new CollectorModule(clock, ticker, configModule,
                storageModule.getSnapshotRepository(),
                storageModule.getTransactionPointRepository(), scheduledExecutor,
                viewerModeEnabled);
        // now inject the real TraceCollector into the proxy
        traceCollectorProxy.setInstance(collectorModule.getTraceCollector());
        uiModule = new LocalUiModule(ticker, clock, dataDir, jvmModule, configModule,
                storageModule, collectorModule, traceModule, instrumentation, properties, version);
        this.dataDir = dataDir;
    }

    @OnlyUsedByTests
    public Clock getClock() {
        return clock;
    }

    @OnlyUsedByTests
    public Ticker getTicker() {
        return ticker;
    }

    @OnlyUsedByTests
    public ConfigModule getConfigModule() {
        return configModule;
    }

    @OnlyUsedByTests
    public StorageModule getStorageModule() {
        return storageModule;
    }

    @OnlyUsedByTests
    public CollectorModule getCollectorModule() {
        return collectorModule;
    }

    @OnlyUsedByTests
    public TraceModule getTraceModule() {
        return traceModule;
    }

    @OnlyUsedByTests
    public LocalUiModule getUiModule() {
        return uiModule;
    }

    @OnlyUsedByTests
    public File getDataDir() {
        return dataDir;
    }

    @OnlyUsedByTests
    public void reopen() {
        initStaticLoggerState(dataDir, loggingSpy);
        traceModule.reopen();
    }

    @OnlyUsedByTests
    public void close() {
        logger.debug("close()");
        uiModule.close();
        collectorModule.close();
        traceModule.close();
        storageModule.close();
        scheduledExecutor.shutdownNow();
        // finally, close logger
        if (shouldOverrideLogging()) {
            ((LoggerContext) LoggerFactory.getILoggerFactory()).reset();
        }
    }

    private static void initStaticLoggerState(File dataDir, boolean loggingSpy) {
        if (shouldOverrideLogging()) {
            overrideLogging(dataDir);
        }
        if (loggingSpy) {
            SpyingLogbackFilter.init();
        }
    }

    private static boolean shouldOverrideLogging() {
        // don't override glowroot.logback-test.xml
        return isShaded() && ClassLoader.getSystemResource("glowroot.logback-test.xml") == null;
    }

    private static void overrideLogging(File dataDir) {
        LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();
        try {
            JoranConfigurator configurator = new JoranConfigurator();
            configurator.setContext(context);
            context.reset();
            context.putProperty("glowroot.data.dir", dataDir.getPath());
            File logbackXmlFile = new File(dataDir, "glowroot.logback.xml");
            if (logbackXmlFile.exists()) {
                configurator.doConfigure(logbackXmlFile);
            } else {
                configurator.doConfigure(Resources.getResource("glowroot.logback-override.xml"));
            }
        } catch (JoranException je) {
            // any errors are printed below by StatusPrinter
        }
        StatusPrinter.printInCaseOfErrorsOrWarnings(context);
    }

    private static boolean isShaded() {
        try {
            Class.forName("org.glowroot.shaded.slf4j.Logger");
            return true;
        } catch (ClassNotFoundException e) {
            // log exception at debug level
            logger.debug(e.getMessage(), e);
            return false;
        }
    }

    @VisibleForTesting
    @SuppressWarnings("serial")
    public static class StartupFailedException extends Exception {

        private final boolean dataSourceLocked;

        private StartupFailedException(Throwable cause) {
            super(cause);
            this.dataSourceLocked = false;
        }

        private StartupFailedException(String message) {
            super(message);
            this.dataSourceLocked = false;
        }

        private StartupFailedException(Throwable cause, boolean dataSourceLocked) {
            super(cause);
            this.dataSourceLocked = dataSourceLocked;
        }

        public boolean isDataSourceLocked() {
            return dataSourceLocked;
        }
    }

    private static class TraceCollectorProxy implements TraceCollector {

        @MonotonicNonNull
        private volatile TraceCollector instance;

        @Override
        public void onCompletedTrace(Trace trace) {
            if (instance != null) {
                instance.onCompletedTrace(trace);
            }
        }

        @Override
        public void onStuckTrace(Trace trace) {
            if (instance != null) {
                instance.onStuckTrace(trace);
            }
        }

        private void setInstance(TraceCollector instance) {
            this.instance = instance;
        }
    }
}
