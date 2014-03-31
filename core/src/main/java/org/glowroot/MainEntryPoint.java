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
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;

import javax.annotation.Nullable;

import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.joran.JoranConfigurator;
import ch.qos.logback.core.joran.spi.JoranException;
import ch.qos.logback.core.util.StatusPrinter;
import com.google.common.base.Joiner;
import com.google.common.base.Objects;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Lists;
import com.google.common.io.Resources;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.glowroot.GlowrootModule.StartupFailedException;
import org.glowroot.api.PluginServices;
import org.glowroot.config.PluginDescriptor;
import org.glowroot.local.store.DataSource;
import org.glowroot.markers.OnlyUsedByTests;
import org.glowroot.markers.Static;
import org.glowroot.markers.UsedByReflection;

import static com.google.common.base.Preconditions.checkNotNull;

/**
 * This class is registered as the Premain-Class in the MANIFEST.MF of glowroot.jar:
 * 
 * Premain-Class: org.glowroot.MainEntryPoint
 * 
 * This defines the entry point when the JVM is launched via -javaagent:glowroot.jar.
 * 
 * @author Trask Stalnaker
 * @since 0.5
 */
@Static
public class MainEntryPoint {

    // log startup messages using logger name "org.glowroot"
    private static final Logger startupLogger = LoggerFactory.getLogger("org.glowroot");

    /*@MonotonicNonNull*/
    private static volatile GlowrootModule glowrootModule;

    private MainEntryPoint() {}

    // javaagent entry point
    public static void premain(@SuppressWarnings("unused") @Nullable String agentArgs,
            Instrumentation instrumentation) {
        ImmutableMap<String, String> properties = getGlowrootProperties();
        // ...WithNoWarning since warning is displayed during start so no need for it twice
        File dataDir = DataDir.getDataDir(properties);
        if (isShaded()) {
            reconfigureLogging(dataDir);
        }
        try {
            DataSource.tryUnlockDatabase(new File(dataDir, "glowroot.lock.db"));
        } catch (SQLException e) {
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
                // log exception stack trace at debug level
                startupLogger.debug(e.getMessage(), e);
            }
            return;
        }
        try {
            start(dataDir, properties, instrumentation);
        } catch (Throwable t) {
            // log error but don't re-throw which would prevent monitored app from starting
            startupLogger.error("Glowroot not started: {}", t.getMessage(), t);
        }
    }

    // called via reflection from org.glowroot.api.PluginServices
    // also called via reflection from generated pointcut config advice
    @UsedByReflection
    public static PluginServices getPluginServices(@Nullable String pluginId) {
        checkNotNull(glowrootModule, "Glowroot has not been started");
        return glowrootModule.getPluginServices(pluginId);
    }

    static void runViewer() throws StartupFailedException, InterruptedException {
        ImmutableMap<String, String> properties = getGlowrootProperties();
        File dataDir = DataDir.getDataDir(properties);
        try {
            DataSource.tryUnlockDatabase(new File(dataDir, "glowroot.lock.db"));
        } catch (SQLException e) {
            startupLogger.error("Viewer cannot start: database file {} is locked by another"
                    + " process.", dataDir.getAbsolutePath());
            // log exception stack trace at debug level
            startupLogger.debug(e.getMessage(), e);
            return;
        }
        String version = Version.getVersion();
        glowrootModule = new GlowrootModule(dataDir, properties, null, version, true);
        startupLogger.info("Viewer started (version {})", version);
        startupLogger.info("Viewer listening at http://localhost:{}",
                glowrootModule.getUiModule().getPort());
        // Glowroot does not create any non-daemon threads, so need to block jvm from exiting when
        // running the viewer
        Thread.sleep(Long.MAX_VALUE);
    }

    /*@EnsuresNonNull("glowrootModule")*/
    private static void start(File dataDir, Map<String, String> properties,
            @Nullable Instrumentation instrumentation) throws StartupFailedException {
        ManagementFactory.getThreadMXBean().setThreadCpuTimeEnabled(true);
        ManagementFactory.getThreadMXBean().setThreadContentionMonitoringEnabled(true);
        String version = Version.getVersion();
        glowrootModule =
                new GlowrootModule(dataDir, properties, instrumentation, version, false);
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
            return false;
        }
    }

    private static boolean isTomcatStop() {
        return Objects.equal(System.getProperty("sun.java.command"),
                "org.apache.catalina.startup.Bootstrap stop");
    }

    @OnlyUsedByTests
    /*@EnsuresNonNull("glowrootModule")*/
    public static void start(Map<String, String> properties)
            throws StartupFailedException {
        File dataDir = DataDir.getDataDir(properties);
        start(dataDir, properties, null);
    }

    @OnlyUsedByTests
    /*@RequiresNonNull("glowrootModule")*/
    public static GlowrootModule getGlowrootModule() {
        return glowrootModule;
    }

    public static void setGlowrootModule(GlowrootModule glowrootModule) {
        MainEntryPoint.glowrootModule = glowrootModule;
        if (isShaded()) {
            reconfigureLogging(glowrootModule.getDataDir());
        }
    }
}
