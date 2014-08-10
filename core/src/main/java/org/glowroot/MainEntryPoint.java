/*
 * Copyright 2011-2014 the original author or authors.
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
import java.lang.instrument.Instrumentation;
import java.lang.management.ManagementFactory;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;

import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.joran.JoranConfigurator;
import ch.qos.logback.core.joran.spi.JoranException;
import ch.qos.logback.core.util.StatusPrinter;
import com.google.common.base.Joiner;
import com.google.common.base.Objects;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Lists;
import com.google.common.io.Resources;
import org.checkerframework.checker.nullness.qual.EnsuresNonNull;
import org.checkerframework.checker.nullness.qual.MonotonicNonNull;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.checkerframework.checker.nullness.qual.RequiresNonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.glowroot.GlowrootModule.StartupFailedException;
import org.glowroot.config.PluginDescriptor;
import org.glowroot.markers.OnlyUsedByTests;
import org.glowroot.markers.Static;

/**
 * @author Trask Stalnaker
 * @since 0.5
 */
@Static
public class MainEntryPoint {

    // log startup messages using logger name "org.glowroot"
    private static final Logger startupLogger = LoggerFactory.getLogger("org.glowroot");

    // this static field is only present for tests
    @MonotonicNonNull
    private static volatile GlowrootModule glowrootModule;

    private MainEntryPoint() {}

    public static void premain(Instrumentation instrumentation, @Nullable File glowrootJarFile) {
        ImmutableMap<String, String> properties = getGlowrootProperties();
        File dataDir = DataDir.getDataDir(properties, glowrootJarFile);
        if (isShaded()) {
            reconfigureLogging(dataDir);
        }
        try {
            start(dataDir, properties, instrumentation, glowrootJarFile);
        } catch (StartupFailedException e) {
            if (e.isDataSourceLocked()) {
                logDataSourceLockedException(dataDir);
            } else {
                // log error but don't re-throw which would prevent monitored app from starting
                startupLogger.error("Glowroot not started: {}", e.getMessage(), e);
            }
        } catch (Throwable t) {
            // log error but don't re-throw which would prevent monitored app from starting
            startupLogger.error("Glowroot not started: {}", t.getMessage(), t);
        }
    }

    static void runViewer(@Nullable File glowrootJarFile) throws StartupFailedException,
            InterruptedException {
        ImmutableMap<String, String> properties = getGlowrootProperties();
        File dataDir = DataDir.getDataDir(properties, glowrootJarFile);
        if (isShaded()) {
            reconfigureLogging(dataDir);
        }
        String version = Version.getVersion();
        try {
            glowrootModule = new GlowrootModule(dataDir, properties, null, glowrootJarFile,
                    version, true);
        } catch (StartupFailedException e) {
            if (e.isDataSourceLocked()) {
                // log nice message without stack trace for this common case
                startupLogger.error("Viewer cannot start: database file {} is locked by another"
                        + " process.", dataDir.getAbsolutePath());
                // log stack trace at debug level
                startupLogger.debug(e.getMessage(), e);
            } else {
                startupLogger.error("Viewer cannot start: {}", e.getMessage(), e);
            }
            return;
        } catch (Throwable t) {
            startupLogger.error("Viewer cannot start: {}", t.getMessage(), t);
            return;
        }
        startupLogger.info("Viewer started (version {})", version);
        startupLogger.info("Viewer listening at http://localhost:{}",
                glowrootModule.getUiModule().getPort());
        // Glowroot does not create any non-daemon threads, so need to block jvm from exiting when
        // running the viewer
        Thread.sleep(Long.MAX_VALUE);
    }

    @EnsuresNonNull("glowrootModule")
    private static void start(File dataDir, Map<String, String> properties,
            @Nullable Instrumentation instrumentation, @Nullable File glowrootJarFile)
            throws StartupFailedException {
        ManagementFactory.getThreadMXBean().setThreadCpuTimeEnabled(true);
        ManagementFactory.getThreadMXBean().setThreadContentionMonitoringEnabled(true);
        String version = Version.getVersion();
        glowrootModule = new GlowrootModule(dataDir, properties, instrumentation, glowrootJarFile,
                version, false);
        startupLogger.info("Glowroot started (version {})", version);
        startupLogger.info("Glowroot listening at http://localhost:{}",
                glowrootModule.getUiModule().getPort());
        List<PluginDescriptor> pluginDescriptors =
                glowrootModule.getConfigModule().getPluginDescriptorCache().getPluginDescriptors();
        List<String> pluginNames = Lists.newArrayList();
        for (PluginDescriptor pluginDescriptor : pluginDescriptors) {
            pluginNames.add(pluginDescriptor.getName());
        }
        if (!pluginNames.isEmpty()) {
            startupLogger.info("Glowroot plugins loaded: {}", Joiner.on(", ").join(pluginNames));
        }
    }

    private static ImmutableMap<String, String> getGlowrootProperties() {
        ImmutableMap.Builder<String, String> builder = ImmutableMap.builder();
        for (Entry<Object, Object> entry : System.getProperties().entrySet()) {
            if (entry.getKey() instanceof String && entry.getValue() instanceof String
                    && ((String) entry.getKey()).startsWith("glowroot.")) {
                String key = (String) entry.getKey();
                builder.put(key.substring("glowroot.".length()), (String) entry.getValue());
            }
        }
        return builder.build();
    }

    private static void logDataSourceLockedException(File dataDir) {
        // this is common when stopping tomcat since 'catalina.sh stop' launches a java process
        // to stop the tomcat jvm, and it uses the same JAVA_OPTS environment variable that may
        // have been used to specify '-javaagent:glowroot.jar', in which case Glowroot tries
        // to start up, but it finds the h2 database is locked (by the tomcat jvm).
        // this can be avoided by using CATALINA_OPTS instead of JAVA_OPTS to specify
        // -javaagent:glowroot.jar, since CATALINA_OPTS is not used by the 'catalina.sh stop'.
        // however, when running tomcat from inside eclipse, the tomcat server adapter uses the
        // same 'VM arguments' for both starting and stopping tomcat, so this code path seems
        // inevitable at least in this case
        //
        // no need for logging in the special (but common) case described above
        if (!isTomcatStop()) {
            startupLogger.error("Glowroot not started: database file {} is locked by another"
                    + " process.", dataDir.getAbsolutePath());
        }
    }

    private static void reconfigureLogging(File dataDir) {
        if (ClassLoader.getSystemResource("glowroot.logback-test.xml") != null) {
            // don't override glowroot.logback-test.xml
            return;
        }
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
            startupLogger.debug(e.getMessage(), e);
            return false;
        }
    }

    private static boolean isTomcatStop() {
        return Objects.equal(System.getProperty("sun.java.command"),
                "org.apache.catalina.startup.Bootstrap stop");
    }

    @OnlyUsedByTests
    @EnsuresNonNull("glowrootModule")
    public static void start(Map<String, String> properties) throws StartupFailedException {
        File dataDir = DataDir.getDataDir(properties, null);
        start(dataDir, properties, null, null);
    }

    @OnlyUsedByTests
    @RequiresNonNull("glowrootModule")
    public static GlowrootModule getGlowrootModule() {
        return glowrootModule;
    }

    @OnlyUsedByTests
    public static void initStaticState(GlowrootModule glowrootModule) {
        glowrootModule.getTraceModule().initStaticState();
        if (isShaded()) {
            reconfigureLogging(glowrootModule.getDataDir());
        }
        MainEntryPoint.glowrootModule = glowrootModule;
    }
}
