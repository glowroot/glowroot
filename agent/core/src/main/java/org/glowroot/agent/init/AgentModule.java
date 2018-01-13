/*
 * Copyright 2011-2018 the original author or authors.
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
import java.util.Random;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ScheduledExecutorService;
import java.util.jar.JarFile;

import javax.annotation.Nullable;

import com.google.common.base.Joiner;
import com.google.common.base.Supplier;
import com.google.common.base.Ticker;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Lists;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.glowroot.agent.api.internal.GlowrootService;
import org.glowroot.agent.collector.Collector;
import org.glowroot.agent.config.ConfigService;
import org.glowroot.agent.config.PluginCache;
import org.glowroot.agent.config.PluginDescriptor;
import org.glowroot.agent.impl.Aggregator;
import org.glowroot.agent.impl.ConfigServiceImpl;
import org.glowroot.agent.impl.GlowrootServiceImpl;
import org.glowroot.agent.impl.ServiceRegistryImpl;
import org.glowroot.agent.impl.ServiceRegistryImpl.ConfigServiceFactory;
import org.glowroot.agent.impl.StackTraceCollector;
import org.glowroot.agent.impl.TimerNameCache;
import org.glowroot.agent.impl.TransactionCollector;
import org.glowroot.agent.impl.TransactionRegistry;
import org.glowroot.agent.impl.TransactionServiceImpl;
import org.glowroot.agent.impl.UserProfileScheduler;
import org.glowroot.agent.live.LiveAggregateRepositoryImpl;
import org.glowroot.agent.live.LiveJvmServiceImpl;
import org.glowroot.agent.live.LiveTraceRepositoryImpl;
import org.glowroot.agent.live.LiveWeavingServiceImpl;
import org.glowroot.agent.util.LazyPlatformMBeanServer;
import org.glowroot.agent.util.OptionalService;
import org.glowroot.agent.util.ThreadAllocatedBytes;
import org.glowroot.agent.util.Tickers;
import org.glowroot.agent.weaving.AdviceCache;
import org.glowroot.agent.weaving.AnalyzedWorld;
import org.glowroot.agent.weaving.IsolatedWeavingClassLoader;
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

    // java.util.logging is shaded to org.glowroot.agent.jul
    private static final String SHADE_PROOF_JUL_LOGGER_CLASS_NAME =
            "_java.util.logging.Logger".substring(1);

    @OnlyUsedByTests
    public static final ThreadLocal</*@Nullable*/ IsolatedWeavingClassLoader> isolatedWeavingClassLoader =
            new ThreadLocal</*@Nullable*/ IsolatedWeavingClassLoader>();

    private final ConfigService configService;
    private final AnalyzedWorld analyzedWorld;
    private final TransactionRegistry transactionRegistry;
    private final AdviceCache adviceCache;

    private final DeadlockedActiveWeavingRunnable deadlockedActiveWeavingRunnable;
    private final Aggregator aggregator;
    private final TransactionCollector transactionCollector;

    private final LazyPlatformMBeanServer lazyPlatformMBeanServer;

    private final GaugeCollector gaugeCollector;
    private final StackTraceCollector stackTraceCollector;

    private final ImmediateTraceStoreWatcher immedateTraceStoreWatcher;

    private final boolean jvmRetransformClassesSupported;

    private final LiveTraceRepositoryImpl liveTraceRepository;
    private final LiveAggregateRepositoryImpl liveAggregateRepository;
    private final LiveWeavingServiceImpl liveWeavingService;
    private final LiveJvmServiceImpl liveJvmService;

    // accepts @Nullable Ticker to deal with shading issues when called from GlowrootModule
    public AgentModule(Clock clock, @Nullable Ticker nullableTicker, final PluginCache pluginCache,
            final ConfigService configService,
            Supplier<ScheduledExecutorService> backgroundExecutorSupplier, Collector collector,
            @Nullable Instrumentation instrumentation, File tmpDir, @Nullable File glowrootJarFile)
            throws Exception {

        Ticker ticker = nullableTicker == null ? Tickers.getTicker() : nullableTicker;
        this.configService = configService;
        transactionRegistry = new TransactionRegistry();

        if (instrumentation != null) {
            for (File pluginJar : pluginCache.pluginJars()) {
                instrumentation.appendToBootstrapClassLoaderSearch(new JarFile(pluginJar));
            }
        }
        adviceCache = new AdviceCache(pluginCache.pluginDescriptors(), pluginCache.pluginJars(),
                configService.getInstrumentationConfigs(), instrumentation, tmpDir);
        analyzedWorld = new AnalyzedWorld(adviceCache.getAdvisorsSupplier(),
                adviceCache.getShimTypes(), adviceCache.getMixinTypes());
        final TimerNameCache timerNameCache = new TimerNameCache();

        final Weaver weaver = new Weaver(adviceCache.getAdvisorsSupplier(),
                adviceCache.getShimTypes(), adviceCache.getMixinTypes(), analyzedWorld,
                transactionRegistry, ticker, timerNameCache, configService);

        if (instrumentation == null) {
            // instrumentation is null when debugging with LocalContainer
            IsolatedWeavingClassLoader isolatedWeavingClassLoader =
                    AgentModule.isolatedWeavingClassLoader.get();
            checkNotNull(isolatedWeavingClassLoader);
            isolatedWeavingClassLoader.setWeaver(weaver);
            jvmRetransformClassesSupported = false;
        } else {
            PreInitializeWeavingClasses.preInitializeClasses();
            ClassFileTransformer transformer =
                    new WeavingClassFileTransformer(weaver, instrumentation);
            if (instrumentation.isRetransformClassesSupported()) {
                instrumentation.addTransformer(transformer, true);
                jvmRetransformClassesSupported = true;
            } else {
                instrumentation.addTransformer(transformer);
                jvmRetransformClassesSupported = false;
            }
            logJavaClassAlreadyLoadedWarningIfNeeded(instrumentation);
        }

        // need to initialize some classes while still single threaded in order to prevent possible
        // deadlock later on
        try {
            Class.forName("sun.net.www.protocol.ftp.Handler");
            Class.forName("sun.net.www.protocol.ftp.FtpURLConnection");
        } catch (ClassNotFoundException e) {
            logger.debug(e.getMessage(), e);
        }

        // now that instrumentation is set up, it is safe to create scheduled executor
        ScheduledExecutorService backgroundExecutor = backgroundExecutorSupplier.get();
        deadlockedActiveWeavingRunnable = new DeadlockedActiveWeavingRunnable(weaver);
        deadlockedActiveWeavingRunnable.scheduleWithFixedDelay(backgroundExecutor, 5, 5, SECONDS);

        aggregator = new Aggregator(collector, configService, ROLLUP_0_INTERVAL_MILLIS, clock);
        transactionCollector =
                new TransactionCollector(configService, collector, aggregator, clock, ticker);

        OptionalService<ThreadAllocatedBytes> threadAllocatedBytes = ThreadAllocatedBytes.create();

        Random random = new Random();
        UserProfileScheduler userProfileScheduler =
                new UserProfileScheduler(backgroundExecutor, configService, random);
        GlowrootService glowrootService = new GlowrootServiceImpl(transactionRegistry);
        TransactionServiceImpl.createSingleton(transactionRegistry, transactionCollector,
                configService, timerNameCache, threadAllocatedBytes.getService(),
                userProfileScheduler, ticker, clock);
        ConfigServiceFactory configServiceFactory = new ConfigServiceFactory() {
            @Override
            public org.glowroot.agent.plugin.api.config.ConfigService create(String pluginId) {
                checkNotNull(configService);
                checkNotNull(pluginCache);
                return ConfigServiceImpl.create(configService, pluginCache.pluginDescriptors(),
                        pluginId);
            }
        };
        ServiceRegistryImpl.init(glowrootService, timerNameCache, configServiceFactory);

        lazyPlatformMBeanServer = LazyPlatformMBeanServer.create();
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
                clock, ticker);
        // using fixed rate to keep gauge collections close to on the second mark
        long gaugeCollectionIntervalMillis = configService.getGaugeCollectionIntervalMillis();
        gaugeCollector.scheduleWithFixedDelay(gaugeCollectionIntervalMillis, MILLISECONDS);
        stackTraceCollector = new StackTraceCollector(transactionRegistry, configService, random);

        immedateTraceStoreWatcher = new ImmediateTraceStoreWatcher(backgroundExecutor,
                transactionRegistry, transactionCollector, configService, ticker);
        immedateTraceStoreWatcher.scheduleWithFixedDelay(backgroundExecutor,
                ImmediateTraceStoreWatcher.PERIOD_MILLIS, MILLISECONDS);

        liveTraceRepository = new LiveTraceRepositoryImpl(transactionRegistry, transactionCollector,
                clock, ticker);
        liveAggregateRepository = new LiveAggregateRepositoryImpl(aggregator);
        liveWeavingService = new LiveWeavingServiceImpl(analyzedWorld, instrumentation,
                configService, adviceCache, jvmRetransformClassesSupported);
        liveJvmService = new LiveJvmServiceImpl(lazyPlatformMBeanServer, transactionRegistry,
                transactionCollector, threadAllocatedBytes.getAvailability(), configService,
                glowrootJarFile);

        initPlugins(pluginCache.pluginDescriptors());

        List<PluginDescriptor> pluginDescriptors = pluginCache.pluginDescriptors();
        List<String> pluginNames = Lists.newArrayList();
        for (PluginDescriptor pluginDescriptor : pluginDescriptors) {
            pluginNames.add(pluginDescriptor.name());
        }
        if (!pluginNames.isEmpty()) {
            startupLogger.info("plugins loaded: {}", Joiner.on(", ").join(pluginNames));
        }
    }

    public ConfigService getConfigService() {
        return configService;
    }

    public LazyPlatformMBeanServer getLazyPlatformMBeanServer() {
        return lazyPlatformMBeanServer;
    }

    public LiveTraceRepositoryImpl getLiveTraceRepository() {
        return liveTraceRepository;
    }

    public LiveAggregateRepositoryImpl getLiveAggregateRepository() {
        return liveAggregateRepository;
    }

    public LiveWeavingServiceImpl getLiveWeavingService() {
        return liveWeavingService;
    }

    public LiveJvmServiceImpl getLiveJvmService() {
        return liveJvmService;
    }

    private static void logJavaClassAlreadyLoadedWarningIfNeeded(Instrumentation instrumentation) {
        List<String> runnableCallableClasses = Lists.newArrayList();
        boolean julLoggerLoaded = false;
        Set<String> hackClassNames = ImmutableSet.of(
                Weaver.JBOSS_WELD_HACK_CLASS_NAME.replace('/', '.'),
                Weaver.JBOSS_MODULES_HACK_CLASS_NAME.replace('/', '.'),
                Weaver.FELIX_OSGI_HACK_CLASS_NAME.replace('/', '.'),
                Weaver.FELIX3_OSGI_HACK_CLASS_NAME.replace('/', '.'),
                Weaver.ECLIPSE_OSGI_HACK_CLASS_NAME.replace('/', '.'),
                Weaver.JBOSS4_HACK_CLASS_NAME.replace('/', '.'));
        for (Class<?> clazz : instrumentation.getAllLoadedClasses()) {
            String className = clazz.getName();
            if (hackClassNames.contains(className)) {
                logHackClassWarning(className);
                // intentionally falling through here
            }
            if (clazz.isInterface()) {
                continue;
            }
            if (className.startsWith("java.util.concurrent.")
                    && (Runnable.class.isAssignableFrom(clazz)
                            || Callable.class.isAssignableFrom(clazz))) {
                runnableCallableClasses.add(clazz.getName());
            }
            if (className.equals(SHADE_PROOF_JUL_LOGGER_CLASS_NAME)) {
                julLoggerLoaded = true;
            }
        }
        if (!runnableCallableClasses.isEmpty()) {
            logRunnableCallableClassWarning(runnableCallableClasses);
        }
        if (julLoggerLoaded && isShaded()) {
            logger.warn("java.util.logging.Logger was loaded before Glowroot instrumentation could"
                    + " be applied to it. This may prevent Glowroot from capturing JUL logging.");
        }
    }

    private static void logHackClassWarning(String specialClassName) {
        logger.warn("{} was loaded before Glowroot instrumentation could be applied to it. {}This"
                + " will likely prevent Glowroot from functioning properly.", specialClassName,
                getExtraExplanation());
    }

    private static void logRunnableCallableClassWarning(List<String> runnableCallableClasses) {
        logger.warn("one or more java.lang.Runnable or java.util.concurrent.Callable"
                + " implementations were loaded before Glowroot instrumentation could be applied to"
                + " them: {}. {}This may prevent Glowroot from capturing async requests that span"
                + " multiple threads.", Joiner.on(", ").join(runnableCallableClasses),
                getExtraExplanation());
    }

    private static String getExtraExplanation() {
        List<String> nonGlowrootAgents = Lists.newArrayList();
        for (String jvmArg : ManagementFactory.getRuntimeMXBean().getInputArguments()) {
            if (jvmArg.startsWith("-javaagent:") && !jvmArg.endsWith("glowroot.jar")
                    || jvmArg.startsWith("-agentpath:")) {
                nonGlowrootAgents.add(jvmArg);
            }
        }
        if (nonGlowrootAgents.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder("This likely occurred because there");
        if (nonGlowrootAgents.size() == 1) {
            sb.append(" is another agent");
        } else {
            sb.append(" are other agents");
        }
        sb.append(" listed in the JVM args prior to the Glowroot agent (");
        for (int i = 0; i < nonGlowrootAgents.size(); i++) {
            if (i > 0) {
                sb.append(" ");
            }
            sb.append(nonGlowrootAgents.get(i));
        }
        sb.append(") which gives the other agent");
        if (nonGlowrootAgents.size() != 1) {
            sb.append("s");
        }
        sb.append(" higher loading precedence. ");
        return sb.toString();
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

    private static boolean isShaded() {
        try {
            Class.forName("org.glowroot.agent.shaded.org.slf4j.Logger");
            return true;
        } catch (ClassNotFoundException e) {
            // log exception at trace level
            logger.trace(e.getMessage(), e);
            return false;
        }
    }

    @OnlyUsedByTests
    public void close() throws Exception {
        immedateTraceStoreWatcher.cancel();
        stackTraceCollector.close();
        gaugeCollector.close();
        lazyPlatformMBeanServer.close();
        transactionCollector.close();
        aggregator.close();
        deadlockedActiveWeavingRunnable.cancel();
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
