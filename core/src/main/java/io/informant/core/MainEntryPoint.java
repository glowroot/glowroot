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
import io.informant.api.PluginServices;
import io.informant.api.weaving.Mixin;
import io.informant.core.PluginServicesImpl.PluginServicesImplFactory;
import io.informant.core.config.PluginInfoCache;
import io.informant.core.log.LogMessageSink;
import io.informant.core.log.LoggerFactoryImpl;
import io.informant.core.util.OnlyUsedByTests;
import io.informant.core.util.Static;
import io.informant.core.weaving.Advice;
import io.informant.core.weaving.ParsedTypeCache;
import io.informant.core.weaving.WeavingClassFileTransformer;
import io.informant.core.weaving.WeavingMetric;
import io.informant.local.ui.HttpServer;

import java.io.File;
import java.lang.instrument.Instrumentation;
import java.sql.SQLException;
import java.util.Map;
import java.util.Map.Entry;

import org.h2.store.FileLister;

import checkers.igj.quals.ReadOnly;
import checkers.nullness.quals.Nullable;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Objects;
import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Iterables;
import com.google.common.collect.Lists;
import com.google.inject.Guice;
import com.google.inject.Injector;

/**
 * This class is registered as the Premain-Class in the MANIFEST.MF of informant-core.jar:
 * 
 * Premain-Class: io.informant.core.MainEntryPoint
 * 
 * This defines the entry point when the JVM is launched via -javaagent:informant-core.jar.
 * 
 * @author Trask Stalnaker
 * @since 0.5
 */
@Static
public class MainEntryPoint {

    private static final Logger logger = LoggerFactory.getLogger(MainEntryPoint.class);

    @Nullable
    private static volatile Injector injector;

    private static final LoadingCache<String, PluginServices> pluginServices =
            CacheBuilder.newBuilder().build(new CacheLoader<String, PluginServices>() {
                @Override
                public PluginServices load(String pluginId) {
                    if (injector == null) {
                        throw new NullPointerException("Call to start() is required");
                    }
                    return injector.getInstance(PluginServicesImplFactory.class).create(pluginId);
                }
            });

    private MainEntryPoint() {}

    // javaagent entry point
    public static void premain(@Nullable String agentArgs, Instrumentation instrumentation) {
        logger.debug("premain(): agentArgs={}", agentArgs);
        ImmutableMap<String, String> properties = getInformantProperties();
        // ...WithNoWarning since warning is displayed during start so no need for it twice
        File dataDir = DataDir.getDataDirWithNoWarning(properties);
        try {
            File h2DatabaseFile = new File(dataDir, "informant.lock.db");
            FileLister.tryUnlockDatabase(Lists.newArrayList(h2DatabaseFile.getPath()), null);
        } catch (SQLException e) {
            // this is common when stopping tomcat since 'catalina.sh stop' launches a java process
            // to stop the tomcat jvm, and it uses the same JAVA_OPTS environment variable that may
            // have been used to specify '-javaagent:informant.jar', in which case Informant tries
            // to start up, but it finds the h2 database is locked (by the tomcat jvm).
            // this can be avoided by using CATALINA_OPTS instead of JAVA_OPTS to specify
            // -javaagent:informant.jar, since CATALINA_OPTS is not used by the 'catalina.sh stop'.
            // however, when running tomcat from inside eclipse, the tomcat server adapter uses the
            // same 'VM arguments' for both starting and stopping tomcat, so this code path seems
            // inevitable at least in this case
            if (!isTomcatStop()) {
                // no need for logging in the special (but common) case described above
                logger.error("informant failed to start: embedded database '"
                        + dataDir.getAbsolutePath() + "' is locked by another process.");
            }
            return;
        }
        try {
            start(properties);
            WeavingClassFileTransformer weavingClassFileTransformer =
                    createWeavingClassFileTransformer();
            instrumentation.addTransformer(weavingClassFileTransformer);
        } catch (Throwable t) {
            logger.error("informant failed to start: {}", t.getMessage(), t);
        }
    }

    // called via reflection from io.informant.api.PluginServices
    public static PluginServices getPluginServices(String pluginId) {
        return pluginServices.getUnchecked(pluginId);
    }

    // used by Viewer
    static void startUsingSystemProperties() throws Exception {
        start(getInformantProperties());
    }

    private static ImmutableMap<String, String> getInformantProperties() {
        ImmutableMap.Builder<String, String> builder = ImmutableMap.builder();
        for (Entry<Object, Object> entry : System.getProperties().entrySet()) {
            if (entry.getKey() instanceof String && entry.getValue() instanceof String
                    && ((String) entry.getKey()).startsWith("informant.")) {
                String key = (String) entry.getKey();
                builder.put(key.substring("informant.".length()), (String) entry.getValue());
            }
        }
        return builder.build();
    }

    private static boolean isTomcatStop() {
        return Objects.equal(System.getProperty("sun.java.command"),
                "org.apache.catalina.startup.Bootstrap stop");
    }

    @VisibleForTesting
    public static void start(@ReadOnly Map<String, String> properties) throws Exception {
        logger.debug("start(): classLoader={}", MainEntryPoint.class.getClassLoader());
        if (injector != null) {
            throw new IllegalStateException("Informant is already started");
        }
        injector = Guice.createInjector(new InformantModule(properties));
        InformantModule.start(injector);
        LoggerFactoryImpl.setLogMessageSink(injector.getInstance(LogMessageSink.class));
    }

    private static WeavingClassFileTransformer createWeavingClassFileTransformer() {
        PluginInfoCache pluginInfoCache = getInstance(PluginInfoCache.class);
        ParsedTypeCache parsedTypeCache = getInstance(ParsedTypeCache.class);
        WeavingMetric weavingMetric = getInstance(WeavingMetric.class);
        Mixin[] mixins = Iterables.toArray(pluginInfoCache.getMixins(), Mixin.class);
        Advice[] advisors = Iterables.toArray(pluginInfoCache.getAdvisors(), Advice.class);
        return new WeavingClassFileTransformer(mixins, advisors, parsedTypeCache, weavingMetric);
    }

    @VisibleForTesting
    public static <T> T getInstance(Class<T> type) {
        if (injector == null) {
            throw new NullPointerException("Call to start() is required");
        }
        return injector.getInstance(type);
    }

    @OnlyUsedByTests
    public static int getPort() {
        return getInstance(HttpServer.class).getPort();
    }

    @OnlyUsedByTests
    public static void shutdown() {
        logger.debug("shutdown()");
        if (injector == null) {
            throw new IllegalStateException("Informant is not started");
        }
        InformantModule.close(injector);
        injector = null;
        pluginServices.invalidateAll();
    }
}
