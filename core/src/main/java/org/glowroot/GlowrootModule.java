/*
 * Copyright 2013-2015 the original author or authors.
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
import java.io.RandomAccessFile;
import java.lang.instrument.Instrumentation;
import java.nio.channels.FileLock;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.jar.JarFile;

import javax.annotation.Nullable;

import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.joran.JoranConfigurator;
import ch.qos.logback.core.joran.spi.JoranException;
import ch.qos.logback.core.util.StatusPrinter;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.io.Files;
import com.google.common.io.Resources;
import com.google.common.util.concurrent.ThreadFactoryBuilder;
import org.checkerframework.checker.nullness.qual.MonotonicNonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.glowroot.collector.CollectorModule;
import org.glowroot.common.Clock;
import org.glowroot.common.SpyingLogbackFilter;
import org.glowroot.common.Ticker;
import org.glowroot.config.ConfigModule;
import org.glowroot.config.PluginDescriptor;
import org.glowroot.jvm.JvmModule;
import org.glowroot.local.store.StorageModule;
import org.glowroot.local.ui.LocalUiModule;
import org.glowroot.markers.OnlyUsedByTests;
import org.glowroot.transaction.TransactionCollector;
import org.glowroot.transaction.TransactionModule;
import org.glowroot.transaction.model.Transaction;
import org.glowroot.weaving.ExtraBootResourceFinder;

@VisibleForTesting
public class GlowrootModule {

    private static final Logger logger = LoggerFactory.getLogger(GlowrootModule.class);

    private static final boolean dummyTicker = Boolean.getBoolean("glowroot.internal.dummyTicker");

    private final Ticker ticker;
    private final Clock clock;
    private final ScheduledExecutorService scheduledExecutor;
    private final ConfigModule configModule;
    private final StorageModule storageModule;
    private final CollectorModule collectorModule;
    private final TransactionModule transactionModule;
    private final LocalUiModule uiModule;
    private final File dataDir;

    private final RandomAccessFile dataDirLockFile;
    private final FileLock dataDirFileLock;

    // this is used by tests to check that no warnings/errors are logged during tests
    private final boolean loggingSpy;

    GlowrootModule(File dataDir, Map<String, String> properties,
            @Nullable Instrumentation instrumentation, @Nullable File glowrootJarFile,
            String version, boolean viewerModeEnabled, boolean jbossModules) throws Exception {

        loggingSpy = Boolean.valueOf(properties.get("internal.logging.spy"));
        initStaticLoggerState(dataDir, loggingSpy);

        // lock data dir
        File tmpDir = new File(dataDir, "tmp");
        File lockFile = new File(tmpDir, ".lock");
        try {
            Files.createParentDirs(lockFile);
            Files.touch(lockFile);
        } catch (IOException e) {
            throw new DataDirLockedException(e);
        }
        dataDirLockFile = new RandomAccessFile(lockFile, "rw");
        FileLock dataDirFileLock = dataDirLockFile.getChannel().tryLock();
        if (dataDirFileLock == null) {
            throw new DataDirLockedException();
        }
        this.dataDirFileLock = dataDirFileLock;
        lockFile.deleteOnExit();

        // init config module
        configModule = new ConfigModule(dataDir, glowrootJarFile, viewerModeEnabled);

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

        ExtraBootResourceFinder extraBootResourceFinder = createExtraBootResourceFinder(
                instrumentation, configModule.getPluginJars());
        ThreadFactory threadFactory = new ThreadFactoryBuilder().setDaemon(true)
                .setNameFormat("Glowroot-Background-%d").build();
        scheduledExecutor = Executors.newScheduledThreadPool(2, threadFactory);
        JvmModule jvmModule = new JvmModule(jbossModules);
        // trace module needs to be started as early as possible, so that weaving will be applied to
        // as many classes as possible
        // in particular, it needs to be started before StorageModule which uses shaded H2, which
        // loads java.sql.DriverManager, which loads 3rd party jdbc drivers found via
        // services/java.sql.Driver, and those drivers need to be woven
        TransactionCollectorProxy transactionCollectorProxy = new TransactionCollectorProxy();
        transactionModule = new TransactionModule(clock, ticker, configModule,
                transactionCollectorProxy, jvmModule.getThreadAllocatedBytes().getService(),
                instrumentation, dataDir, extraBootResourceFinder, scheduledExecutor);
        storageModule = new StorageModule(dataDir, properties, ticker, clock, configModule,
                scheduledExecutor, viewerModeEnabled);
        collectorModule = new CollectorModule(clock, ticker, jvmModule, configModule,
                storageModule.getTraceRepository(), storageModule.getAggregateRepository(),
                storageModule.getGaugePointDao(), transactionModule.getTransactionRegistry(),
                scheduledExecutor, viewerModeEnabled);
        // now inject the real TransactionCollector into the proxy
        transactionCollectorProxy.setInstance(collectorModule.getTransactionCollector());
        initPlugins(configModule.getPluginDescriptors());
        uiModule = new LocalUiModule(ticker, clock, jvmModule, configModule, storageModule,
                collectorModule, transactionModule, instrumentation, properties, version);
        this.dataDir = dataDir;
    }

    private static void initStaticLoggerState(File dataDir, boolean loggingSpy) {
        if (shouldOverrideLogging()) {
            overrideLogging(dataDir);
        }
        if (loggingSpy) {
            SpyingLogbackFilter.init();
        }
    }

    private static @Nullable ExtraBootResourceFinder createExtraBootResourceFinder(
            @Nullable Instrumentation instrumentation, List<File> pluginJars) throws IOException {
        if (instrumentation == null) {
            return null;
        }
        for (File pluginJar : pluginJars) {
            instrumentation.appendToBootstrapClassLoaderSearch(new JarFile(pluginJar));
        }
        return new ExtraBootResourceFinder(pluginJars);
    }

    // now init plugins to give them a chance to do something in their static initializer
    // e.g. append their package to jboss.modules.system.pkgs
    private static void initPlugins(List<PluginDescriptor> pluginDescriptors) {
        for (PluginDescriptor pluginDescriptor : pluginDescriptors) {
            for (String aspect : pluginDescriptor.aspects()) {
                try {
                    Class.forName(aspect, true, GlowrootModule.class.getClassLoader());
                } catch (ClassNotFoundException e) {
                    // this would have already been logged as a warning during advice construction
                    logger.debug(e.getMessage(), e);
                }
            }
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
            // log exception at trace level
            logger.trace(e.getMessage(), e);
            return false;
        }
    }

    @OnlyUsedByTests
    public ConfigModule getConfigModule() {
        return configModule;
    }

    @OnlyUsedByTests
    public TransactionModule getTransactionModule() {
        return transactionModule;
    }

    @OnlyUsedByTests
    public LocalUiModule getUiModule() {
        return uiModule;
    }

    @OnlyUsedByTests
    public void reopen() {
        initStaticLoggerState(dataDir, loggingSpy);
        transactionModule.reopen();
    }

    @OnlyUsedByTests
    public void close() throws Exception {
        uiModule.close();
        collectorModule.close();
        transactionModule.close();
        storageModule.close();
        scheduledExecutor.shutdownNow();
        // finally, close logger
        if (shouldOverrideLogging()) {
            ((LoggerContext) LoggerFactory.getILoggerFactory()).reset();
        }
        dataDirFileLock.release();
        dataDirLockFile.close();
    }

    @VisibleForTesting
    @SuppressWarnings("serial")
    public static class StartupFailedException extends Exception {

        private StartupFailedException() {
            super();
        }

        private StartupFailedException(Throwable cause) {
            super(cause);
        }
    }

    @SuppressWarnings("serial")
    static class DataDirLockedException extends StartupFailedException {

        private DataDirLockedException() {
            super();
        }

        private DataDirLockedException(Throwable cause) {
            super(cause);
        }
    }

    @VisibleForTesting
    static class TransactionCollectorProxy implements TransactionCollector {

        private volatile @MonotonicNonNull TransactionCollector instance;

        @Override
        public void onCompletedTransaction(Transaction transaction) {
            if (instance != null) {
                instance.onCompletedTransaction(transaction);
            }
        }

        @Override
        public void storePartialTrace(Transaction transaction) {
            if (instance != null) {
                instance.storePartialTrace(transaction);
            }
        }

        @VisibleForTesting
        void setInstance(TransactionCollector instance) {
            this.instance = instance;
        }
    }
}
