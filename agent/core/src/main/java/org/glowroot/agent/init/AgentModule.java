/*
 * Copyright 2011-2023 the original author or authors.
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
package org.glowroot.agent.init;

import java.io.File;
import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.lang.management.ManagementFactory;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ScheduledExecutorService;
import java.util.jar.JarFile;

import com.google.common.base.Joiner;
import com.google.common.base.Ticker;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;
import org.checkerframework.checker.nullness.qual.MonotonicNonNull;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.glowroot.agent.bytecode.api.BytecodeServiceHolder;
import org.glowroot.agent.collector.Collector;
import org.glowroot.agent.config.ConfigService;
import org.glowroot.agent.config.PluginCache;
import org.glowroot.agent.config.PluginDescriptor;
import org.glowroot.agent.impl.BytecodeServiceImpl;
import org.glowroot.agent.impl.BytecodeServiceImpl.OnEnteringMain;
import org.glowroot.agent.impl.ConfigServiceImpl;
import org.glowroot.agent.impl.GlowrootServiceHolder;
import org.glowroot.agent.impl.GlowrootServiceImpl;
import org.glowroot.agent.impl.PluginServiceImpl;
import org.glowroot.agent.impl.PluginServiceImpl.ConfigServiceFactory;
import org.glowroot.agent.impl.PreloadSomeSuperTypesCache;
import org.glowroot.agent.impl.StackTraceCollector;
import org.glowroot.agent.impl.TimerNameCache;
import org.glowroot.agent.impl.TraceCollector;
import org.glowroot.agent.impl.TransactionProcessor;
import org.glowroot.agent.impl.TransactionRegistry;
import org.glowroot.agent.impl.TransactionService;
import org.glowroot.agent.init.PreCheckLoadedClasses.PreCheckClassFileTransformer;
import org.glowroot.agent.live.LiveAggregateRepositoryImpl;
import org.glowroot.agent.live.LiveJvmServiceImpl;
import org.glowroot.agent.live.LiveTraceRepositoryImpl;
import org.glowroot.agent.live.LiveWeavingServiceImpl;
import org.glowroot.agent.plugin.api.internal.PluginService;
import org.glowroot.agent.plugin.api.internal.PluginServiceHolder;
import org.glowroot.agent.util.JavaVersion;
import org.glowroot.agent.util.LazyPlatformMBeanServer;
import org.glowroot.agent.util.OptionalService;
import org.glowroot.agent.util.ThreadAllocatedBytes;
import org.glowroot.agent.util.Tickers;
import org.glowroot.agent.weaving.AdviceCache;
import org.glowroot.agent.weaving.AnalyzedWorld;
import org.glowroot.agent.weaving.IsolatedWeavingClassLoader;
import org.glowroot.agent.weaving.PointcutClassFileTransformer;
import org.glowroot.agent.weaving.PreInitializeWeavingClasses;
import org.glowroot.agent.weaving.Weaver;
import org.glowroot.agent.weaving.WeavingClassFileTransformer;
import org.glowroot.common.util.Clock;
import org.glowroot.common.util.OnlyUsedByTests;
import org.glowroot.common.util.ScheduledRunnable;

import static com.google.common.base.Preconditions.checkNotNull;
import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.concurrent.TimeUnit.MINUTES;
import static java.util.concurrent.TimeUnit.SECONDS;

public class AgentModule {

    private static final Logger logger = LoggerFactory.getLogger(AgentModule.class);

    // log startup messages using logger name "org.glowroot"
    private static final Logger startupLogger = LoggerFactory.getLogger("org.glowroot");

    // 1 minute
    private static final long ROLLUP_0_INTERVAL_MILLIS =
            Long.getLong("glowroot.internal.rollup.0.intervalMillis", MINUTES.toMillis(1));

    private final Clock clock;
    private final Ticker ticker;

    private final ConfigService configService;
    private final TransactionRegistry transactionRegistry;
    private final AdviceCache adviceCache;
    private final PreloadSomeSuperTypesCache preloadSomeSuperTypesCache;
    private final AnalyzedWorld analyzedWorld;
    private final Weaver weaver;
    private final Random random;

    private final TransactionService transactionService;
    private final BytecodeServiceImpl bytecodeService;

    private volatile @MonotonicNonNull DeadlockedActiveWeavingRunnable deadlockedActiveWeavingRunnable;
    private volatile @MonotonicNonNull TraceCollector traceCollector;
    private volatile @MonotonicNonNull TransactionProcessor transactionProcessor;

    private volatile @MonotonicNonNull LazyPlatformMBeanServer lazyPlatformMBeanServer;

    private volatile @MonotonicNonNull GaugeCollector gaugeCollector;
    private volatile @MonotonicNonNull StackTraceCollector stackTraceCollector;

    private volatile @MonotonicNonNull ImmediateTraceStoreWatcher immedateTraceStoreWatcher;

    private final boolean jvmRetransformClassesSupported;

    private volatile @MonotonicNonNull LiveTraceRepositoryImpl liveTraceRepository;
    private volatile @MonotonicNonNull LiveAggregateRepositoryImpl liveAggregateRepository;
    private volatile @MonotonicNonNull LiveWeavingServiceImpl liveWeavingService;
    private volatile @MonotonicNonNull LiveJvmServiceImpl liveJvmService;

    // accepts @Nullable Ticker to deal with shading issues when called from GlowrootModule
    public AgentModule(Clock clock, @Nullable Ticker nullableTicker, final PluginCache pluginCache,
            final ConfigService configService, @Nullable Instrumentation instrumentation,
            @Nullable File glowrootJarFile, File tmpDir,
            @Nullable PreCheckClassFileTransformer preCheckClassFileTransformer) throws Exception {

        this.clock = clock;
        this.ticker = nullableTicker == null ? Tickers.getTicker() : nullableTicker;
        this.configService = configService;
        transactionRegistry = new TransactionRegistry();

        ClassFileTransformer pointcutClassFileTransformer = null;
        if (instrumentation != null) {
            for (File pluginJar : pluginCache.pluginJars()) {
                instrumentation.appendToBootstrapClassLoaderSearch(new JarFile(pluginJar));
            }
            pointcutClassFileTransformer = new PointcutClassFileTransformer();
            instrumentation.addTransformer(pointcutClassFileTransformer);
        }
        adviceCache = new AdviceCache(pluginCache.pluginDescriptors(),
                configService.getInstrumentationConfigs(), instrumentation, tmpDir);
        if (pointcutClassFileTransformer != null) {
            checkNotNull(instrumentation).removeTransformer(pointcutClassFileTransformer);
        }
        preloadSomeSuperTypesCache = new PreloadSomeSuperTypesCache(
                new File(tmpDir, "preload-some-super-types-cache"), 50000, clock);
        analyzedWorld =
                new AnalyzedWorld(adviceCache.getAdvisorsSupplier(), adviceCache.getShimTypes(),
                        adviceCache.getMixinTypes(), preloadSomeSuperTypesCache);
        TimerNameCache timerNameCache = new TimerNameCache();

        weaver = new Weaver(adviceCache.getAdvisorsSupplier(), adviceCache.getShimTypes(),
                adviceCache.getMixinTypes(), analyzedWorld, transactionRegistry, ticker,
                timerNameCache, configService);

        // need to initialize glowroot-agent-api, glowroot-agent-plugin-api and glowroot-weaving-api
        // services before enabling instrumentation
        GlowrootServiceHolder.set(new GlowrootServiceImpl(transactionRegistry));
        ConfigServiceFactory configServiceFactory = new ConfigServiceFactory() {
            @Override
            public org.glowroot.agent.plugin.api.config.ConfigService create(String pluginId) {
                return ConfigServiceImpl.create(configService, pluginCache.pluginDescriptors(),
                        pluginId);
            }
        };
        PluginService pluginService = new PluginServiceImpl(timerNameCache, configServiceFactory);
        PluginServiceHolder.set(pluginService);
        random = new Random();
        transactionService = TransactionService.create(transactionRegistry, configService,
                timerNameCache, ticker, clock);
        bytecodeService = new BytecodeServiceImpl(transactionRegistry, transactionService,
                preloadSomeSuperTypesCache);
        BytecodeServiceHolder.set(bytecodeService);

        if (instrumentation == null) {
            // instrumentation is null when debugging with LocalContainer
            IsolatedWeavingClassLoader isolatedWeavingClassLoader =
                    (IsolatedWeavingClassLoader) Thread.currentThread().getContextClassLoader();
            checkNotNull(isolatedWeavingClassLoader);
            isolatedWeavingClassLoader.setWeaver(weaver);
            jvmRetransformClassesSupported = false;
        } else {
            PreInitializeWeavingClasses.preInitializeClasses();
            WeavingClassFileTransformer transformer =
                    new WeavingClassFileTransformer(weaver, instrumentation);
            if (instrumentation.isRetransformClassesSupported()) {
                instrumentation.addTransformer(transformer, true);
                jvmRetransformClassesSupported = true;
            } else {
                instrumentation.addTransformer(transformer);
                jvmRetransformClassesSupported = false;
            }
            if (preCheckClassFileTransformer != null) {
                for (Map.Entry<String, Exception> entry : preCheckClassFileTransformer
                        .getImportantClassLoadingPoints().entrySet()) {
                    logger.warn("important class loaded before Glowroot instrumentation could be"
                            + " applied to it: {}", entry.getKey(), entry.getValue());
                }
                instrumentation.removeTransformer(preCheckClassFileTransformer);
            }
            Class<?>[] initialLoadedClasses = instrumentation.getAllLoadedClasses();
            adviceCache.initialReweave(initialLoadedClasses);
            logAnyImportantClassLoadedPriorToWeavingInit(initialLoadedClasses, glowrootJarFile,
                    false);
            instrumentation.retransformClasses(ClassLoader.class);
        }

        ManagementFactory.getThreadMXBean().setThreadCpuTimeEnabled(true);
        ManagementFactory.getThreadMXBean().setThreadContentionMonitoringEnabled(true);

        // need to initialize some classes while still single threaded in order to prevent possible
        // deadlock later on
        try {
            Class.forName("sun.net.www.protocol.ftp.Handler");
            Class.forName("sun.net.www.protocol.ftp.FtpURLConnection");
        } catch (ClassNotFoundException e) {
            logger.debug(e.getMessage(), e);
        }

        // verify initialization of glowroot-agent-api, glowroot-agent-plugin-api and
        // glowroot-weaving-api services
        Exception getterCalledTooEarlyLocation =
                GlowrootServiceHolder.getRetrievedTooEarlyLocation();
        if (getterCalledTooEarlyLocation != null) {
            logger.error("Glowroot Agent API was called too early", getterCalledTooEarlyLocation);
        }

        initPlugins(pluginCache.pluginDescriptors());

        // init stack trace collector early for profiling other agents
        stackTraceCollector = new StackTraceCollector(transactionRegistry, configService, random);
    }

    public void setOnEnteringMain(OnEnteringMain onEnteringMain) {
        bytecodeService.setOnEnteringMain(onEnteringMain);
    }

    public void onEnteringMain(ScheduledExecutorService backgroundExecutor, Collector collector,
            @Nullable Instrumentation instrumentation, @Nullable File glowrootJarFile,
            @Nullable String mainClass) throws Exception {

        weaver.setNoLongerNeedToWeaveMainMethods();

        deadlockedActiveWeavingRunnable = new DeadlockedActiveWeavingRunnable(weaver);
        deadlockedActiveWeavingRunnable.scheduleWithFixedDelay(backgroundExecutor, 5, 5, SECONDS);

        // complete initialization of glowroot-agent-api, glowroot-agent-plugin-api and
        // glowroot-weaving-api services
        OptionalService<ThreadAllocatedBytes> threadAllocatedBytes = ThreadAllocatedBytes.create();
        transactionService.setThreadAllocatedBytes(threadAllocatedBytes.getService());
        traceCollector = new TraceCollector(configService, collector, clock, ticker);
        transactionProcessor = new TransactionProcessor(collector, traceCollector, configService,
                ROLLUP_0_INTERVAL_MILLIS, clock);
        transactionService.setTransactionProcessor(transactionProcessor);

        lazyPlatformMBeanServer = LazyPlatformMBeanServer.create(mainClass);
        bytecodeService.setOnExitingGetPlatformMBeanServer(new Runnable() {
            @Override
            public void run() {
                // TODO report checker framework issue that occurs without checkNotNull
                checkNotNull(lazyPlatformMBeanServer);
                lazyPlatformMBeanServer.setPlatformMBeanServerAvailable();
            }
        });
        File[] roots = File.listRoots();
        if (roots != null) {
            for (File root : roots) {
                String name = root.getCanonicalPath();
                if (name.length() > 1 && (name.endsWith("/") || name.endsWith("\\"))) {
                    name = name.substring(0, name.length() - 1);
                }
                name = name.replaceAll(":", "");
                lazyPlatformMBeanServer.lazyRegisterMBean(new FileSystem(root),
                        "org.glowroot:type=FileSystem,name=" + name);
            }
        }
        gaugeCollector = new GaugeCollector(configService, collector, lazyPlatformMBeanServer,
                instrumentation, clock, ticker);
        // using fixed rate to keep gauge collections close to on the second mark
        long gaugeCollectionIntervalMillis = configService.getGaugeCollectionIntervalMillis();
        gaugeCollector.scheduleWithFixedDelay(gaugeCollectionIntervalMillis, MILLISECONDS);

        immedateTraceStoreWatcher = new ImmediateTraceStoreWatcher(backgroundExecutor,
                transactionRegistry, traceCollector, configService, ticker);
        immedateTraceStoreWatcher.scheduleWithFixedDelay(backgroundExecutor,
                ImmediateTraceStoreWatcher.PERIOD_MILLIS, ImmediateTraceStoreWatcher.PERIOD_MILLIS,
                MILLISECONDS);

        liveTraceRepository = new LiveTraceRepositoryImpl(transactionRegistry, traceCollector,
                clock, ticker);
        liveAggregateRepository = new LiveAggregateRepositoryImpl(transactionProcessor);
        liveWeavingService = new LiveWeavingServiceImpl(analyzedWorld, instrumentation,
                configService, adviceCache, jvmRetransformClassesSupported);
        liveJvmService = new LiveJvmServiceImpl(lazyPlatformMBeanServer, transactionRegistry,
                traceCollector, threadAllocatedBytes.getAvailability(), configService,
                glowrootJarFile, clock);

        preloadSomeSuperTypesCache.scheduleWithFixedDelay(backgroundExecutor, 5, 5, SECONDS);
    }

    public ConfigService getConfigService() {
        return configService;
    }

    public LazyPlatformMBeanServer getLazyPlatformMBeanServer() {
        if (lazyPlatformMBeanServer == null) {
            throw new IllegalStateException("onEnteringMain() was never called");
        }
        return lazyPlatformMBeanServer;
    }

    public LiveTraceRepositoryImpl getLiveTraceRepository() {
        if (liveTraceRepository == null) {
            throw new IllegalStateException("onEnteringMain() was never called");
        }
        return liveTraceRepository;
    }

    public LiveAggregateRepositoryImpl getLiveAggregateRepository() {
        if (liveAggregateRepository == null) {
            throw new IllegalStateException("onEnteringMain() was never called");
        }
        return liveAggregateRepository;
    }

    public LiveWeavingServiceImpl getLiveWeavingService() {
        if (liveWeavingService == null) {
            throw new IllegalStateException("onEnteringMain() was never called");
        }
        return liveWeavingService;
    }

    public LiveJvmServiceImpl getLiveJvmService() {
        if (liveJvmService == null) {
            throw new IllegalStateException("onEnteringMain() was never called");
        }
        return liveJvmService;
    }

    public static boolean logAnyImportantClassLoadedPriorToWeavingInit(
            Class<?>[] initialLoadedClasses, @Nullable File glowrootJarFile, boolean preCheck) {
        List<String> loadedImportantClassNames = Lists.newArrayList();
        for (Class<?> initialLoadedClass : initialLoadedClasses) {
            String className = initialLoadedClass.getName();
            if (PreCheckLoadedClasses.isImportantClass(className, initialLoadedClass)) {
                loadedImportantClassNames.add(className);
            }
        }
        if (loadedImportantClassNames.isEmpty()) {
            return false;
        } else {
            logLoadedImportantClassWarning(loadedImportantClassNames, glowrootJarFile, preCheck);
            return true;
        }
    }

    private static void logLoadedImportantClassWarning(List<String> loadedImportantClassNames,
            @Nullable File glowrootJarFile, boolean preCheck) {
        if (preCheck) {
            // this is only logged with -Dglowroot.debug.preCheckLoadedClasses=true
            startupLogger.warn("PRE-CHECK: one or more important classes were loaded before"
                    + " Glowroot startup: {}", Joiner.on(", ").join(loadedImportantClassNames));
            return;
        }
        List<String> javaAgentArgsBeforeGlowroot = getJavaAgentArgsBeforeGlowroot(glowrootJarFile);
        if (!javaAgentArgsBeforeGlowroot.isEmpty()) {
            startupLogger.warn("one or more important classes were loaded before Glowroot"
                    + " instrumentation could be applied to them: {}. This likely occurred because"
                    + " one or more other javaagents are listed in the JVM args prior to the"
                    + " Glowroot agent ({}) which gives them a higher loading precedence.",
                    Joiner.on(", ").join(loadedImportantClassNames),
                    Joiner.on(" ").join(javaAgentArgsBeforeGlowroot));
            return;
        }
        List<String> nativeAgentArgs = getNativeAgentArgs();
        if (!nativeAgentArgs.isEmpty()) {
            startupLogger.warn("one or more important classes were loaded before Glowroot"
                    + " instrumentation could be applied to them: {}. This likely occurred because"
                    + " there are one or more native agents listed in the JVM args ({}), and native"
                    + " agents have higher loading precedence than java agents.",
                    Joiner.on(", ").join(loadedImportantClassNames),
                    Joiner.on(" ").join(nativeAgentArgs));
            return;
        }
        startupLogger.warn("one or more important classes were loaded before Glowroot"
                + " instrumentation could be applied to them: {}",
                Joiner.on(", ").join(loadedImportantClassNames));
    }

    private static List<String> getNativeAgentArgs() {
        List<String> nativeAgentArgs = Lists.newArrayList();
        for (String jvmArg : ManagementFactory.getRuntimeMXBean().getInputArguments()) {
            if (jvmArg.startsWith("-agentpath:") || jvmArg.startsWith("-agentlib:")) {
                nativeAgentArgs.add(jvmArg);
            }
        }
        return nativeAgentArgs;
    }

    private static List<String> getJavaAgentArgsBeforeGlowroot(@Nullable File glowrootJarFile) {
        if (glowrootJarFile == null) {
            return ImmutableList.of();
        }
        List<String> javaAgentArgsBeforeGlowroot = Lists.newArrayList();
        for (String jvmArg : ManagementFactory.getRuntimeMXBean().getInputArguments()) {
            if (jvmArg.startsWith("-javaagent:") && jvmArg.endsWith(glowrootJarFile.getName())) {
                break;
            }
            if (jvmArg.startsWith("-javaagent:") || isIbmJ9HealthcenterArg(jvmArg)) {
                javaAgentArgsBeforeGlowroot.add(jvmArg);
            }
        }
        return javaAgentArgsBeforeGlowroot;
    }

    private static boolean isIbmJ9HealthcenterArg(String jvmArg) {
        return JavaVersion.isJ9Jvm()
                && (jvmArg.equals("-Xhealthcenter") || jvmArg.startsWith("-Xhealthcenter:"));
    }

    // now init plugins to give them a chance to do something in their static initializer
    // e.g. append their package to jboss.modules.system.pkgs
    private static void initPlugins(List<PluginDescriptor> pluginDescriptors) {
        for (PluginDescriptor pluginDescriptor : pluginDescriptors) {
            for (String aspect : pluginDescriptor.aspects()) {
                try {
                    Class.forName(aspect, true, AgentModule.class.getClassLoader());
                } catch (ClassNotFoundException e) {
                    // this would have already been logged as a warning during advice construction
                    logger.debug(e.getMessage(), e);
                }
            }
        }
    }

    @OnlyUsedByTests
    public void close() throws Exception {
        if (immedateTraceStoreWatcher != null) {
            immedateTraceStoreWatcher.cancel();
        }
        if (stackTraceCollector != null) {
            stackTraceCollector.close();
        }
        if (gaugeCollector != null) {
            gaugeCollector.close();
        }
        if (lazyPlatformMBeanServer != null) {
            lazyPlatformMBeanServer.close();
        }
        if (traceCollector != null) {
            traceCollector.close();
        }
        if (transactionProcessor != null) {
            transactionProcessor.close();
        }
        if (deadlockedActiveWeavingRunnable != null) {
            deadlockedActiveWeavingRunnable.cancel();
        }
    }

    private static class DeadlockedActiveWeavingRunnable extends ScheduledRunnable {

        private final Weaver weaver;

        private DeadlockedActiveWeavingRunnable(Weaver weaver) {
            this.weaver = weaver;
        }

        @Override
        public void runInternal() {
            weaver.checkForDeadlockedActiveWeaving();
        }
    }
}
