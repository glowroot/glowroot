/*
 * Copyright 2011-2017 the original author or authors.
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
package org.glowroot.agent;

import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.lang.instrument.Instrumentation;
import java.lang.management.ManagementFactory;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Properties;
import java.util.ServiceLoader;

import javax.annotation.Nullable;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Charsets;
import com.google.common.base.Objects;
import com.google.common.base.Strings;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.io.Files;
import org.checkerframework.checker.nullness.qual.EnsuresNonNull;
import org.checkerframework.checker.nullness.qual.MonotonicNonNull;
import org.checkerframework.checker.nullness.qual.RequiresNonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.glowroot.agent.collector.Collector;
import org.glowroot.agent.embedded.init.EmbeddedGlowrootAgentInit;
import org.glowroot.agent.init.AgentDirLocking.AgentDirLockedException;
import org.glowroot.agent.init.CentralGlowrootAgentInit;
import org.glowroot.agent.init.GlowrootAgentInit;
import org.glowroot.agent.util.AppServerDetection;
import org.glowroot.common.util.OnlyUsedByTests;
import org.glowroot.common.util.PropertiesFiles;
import org.glowroot.common.util.Version;

import static com.google.common.base.Preconditions.checkNotNull;

public class MainEntryPoint {

    // need to wait to init logger until after establishing agentDir
    private static volatile @MonotonicNonNull Logger startupLogger;

    @OnlyUsedByTests
    private static @MonotonicNonNull GlowrootAgentInit glowrootAgentInit;

    private MainEntryPoint() {}

    public static void premain(Instrumentation instrumentation, @Nullable File glowrootJarFile) {
        // DO NOT USE ANY GUAVA CLASSES before initLogging() because they trigger loading of jul
        // (and thus org.glowroot.agent.jul.Logger and thus glowroot's shaded slf4j)
        boolean jbossModules = AppServerDetection.isJBossModules(AppServerDetection.getCommand());
        if (jbossModules) {
            String jbossModulesSystemPkgs = System.getProperty("jboss.modules.system.pkgs");
            if (jbossModulesSystemPkgs == null || jbossModulesSystemPkgs.isEmpty()) {
                jbossModulesSystemPkgs = "org.glowroot.agent";
            } else {
                jbossModulesSystemPkgs += ",org.glowroot.agent";
            }
            System.setProperty("jboss.modules.system.pkgs", jbossModulesSystemPkgs);
        }
        File glowrootDir;
        File agentDir;
        try {
            glowrootDir = GlowrootDir.getGlowrootDir(glowrootJarFile);
            agentDir = GlowrootDir.getAgentDir(glowrootDir);
            // init logger as early as possible
            File logDir = GlowrootDir.getLogDir(agentDir);
            initLogging(agentDir, logDir);
        } catch (Throwable t) {
            // log error but don't re-throw which would prevent monitored app from starting
            // also, don't use logger since not initialized yet
            System.err.println("Glowroot not started: " + t.getMessage());
            t.printStackTrace();
            return;
        }
        try {
            ImmutableMap<String, String> properties = getGlowrootProperties(glowrootDir);
            start(glowrootDir, agentDir, properties, instrumentation);
        } catch (AgentDirLockedException e) {
            logAgentDirLockedException(agentDir);
        } catch (Throwable t) {
            // log error but don't re-throw which would prevent monitored app from starting
            startupLogger.error("Glowroot not started: {}", t.getMessage(), t);
        }
    }

    static void runViewer(File glowrootDir, File agentDir) throws InterruptedException {
        // initLogging() already called by OfflineViewer.main()
        checkNotNull(startupLogger);
        String version;
        try {
            version = Version.getVersion(MainEntryPoint.class);
            startupLogger.info("Glowroot version: {}", version);
            ImmutableMap<String, String> properties = getGlowrootProperties(glowrootDir);
            new EmbeddedGlowrootAgentInit().init(glowrootDir, agentDir, null, null, properties,
                    null, version, true);
        } catch (AgentDirLockedException e) {
            logAgentDirLockedException(agentDir);
            return;
        } catch (Throwable t) {
            startupLogger.error("Glowroot cannot start: {}", t.getMessage(), t);
            return;
        }
        // Glowroot does not create any non-daemon threads, so need to block jvm from exiting when
        // running the viewer
        Thread.sleep(Long.MAX_VALUE);
    }

    @EnsuresNonNull("startupLogger")
    static void initLogging(File agentDir, File logDir) {
        File logbackXmlOverride = new File(agentDir, "glowroot.logback.xml");
        if (logbackXmlOverride.exists()) {
            System.setProperty("glowroot.logback.configurationFile",
                    logbackXmlOverride.getAbsolutePath());
        }
        String prior = System.getProperty("glowroot.log.dir");
        System.setProperty("glowroot.log.dir", logDir.getPath());
        try {
            startupLogger = LoggerFactory.getLogger("org.glowroot");
        } finally {
            System.clearProperty("glowroot.logback.configurationFile");
            if (prior == null) {
                System.clearProperty("glowroot.log.dir");
            } else {
                System.setProperty("glowroot.log.dir", prior);
            }
        }
        // TODO report checker framework issue that occurs without checkNotNull
        checkNotNull(startupLogger);
    }

    @RequiresNonNull("startupLogger")
    private static void start(File glowrootDir, File agentDir, Map<String, String> properties,
            @Nullable Instrumentation instrumentation) throws Exception {
        ManagementFactory.getThreadMXBean().setThreadCpuTimeEnabled(true);
        ManagementFactory.getThreadMXBean().setThreadContentionMonitoringEnabled(true);
        String version = Version.getVersion(MainEntryPoint.class);
        startupLogger.info("Glowroot version: {}", version);
        String collectorAddress = properties.get("glowroot.collector.address");
        Collector customCollector = loadCustomCollector(glowrootDir);
        if (Strings.isNullOrEmpty(collectorAddress) && customCollector == null) {
            glowrootAgentInit = new EmbeddedGlowrootAgentInit();
        } else {
            if (customCollector != null) {
                startupLogger.info("using collector: {}", customCollector.getClass().getName());
            }
            glowrootAgentInit = new CentralGlowrootAgentInit();
        }
        glowrootAgentInit.init(glowrootDir, agentDir, collectorAddress, customCollector,
                properties, instrumentation, version, false);
    }

    private static ImmutableMap<String, String> getGlowrootProperties(File glowrootDir)
            throws IOException {
        Map<String, String> properties = Maps.newHashMap();
        File propFile = new File(glowrootDir, "glowroot.properties");
        if (propFile.exists()) {
            // upgrade from 0.9.6 to 0.9.7
            PropertiesFiles.upgradeIfNeeded(propFile,
                    ImmutableMap.of("agent.rollup=", "agent.rollup.id="));
            // upgrade from 0.9.13 to 0.9.14
            upgradeToCollectorAddressIfNeeded(propFile);
            Properties props = PropertiesFiles.load(propFile);
            for (String key : props.stringPropertyNames()) {
                String value = props.getProperty(key);
                if (value != null) {
                    properties.put("glowroot." + key, value);
                }
            }
        }
        for (Entry<Object, Object> entry : System.getProperties().entrySet()) {
            if (entry.getKey() instanceof String && entry.getValue() instanceof String
                    && ((String) entry.getKey()).startsWith("glowroot.")) {
                String key = (String) entry.getKey();
                properties.put(key, (String) entry.getValue());
            }
        }
        return ImmutableMap.copyOf(properties);
    }

    @RequiresNonNull("startupLogger")
    private static void logAgentDirLockedException(File agentDir) {
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
            startupLogger.error("Glowroot not started, directory in use by another jvm process: {}",
                    agentDir.getAbsolutePath());
        }
    }

    private static boolean isTomcatStop() {
        return Objects.equal(System.getProperty("sun.java.command"),
                "org.apache.catalina.startup.Bootstrap stop");
    }

    private static @Nullable Collector loadCustomCollector(File glowrootDir)
            throws MalformedURLException {
        Collector collector = loadCollector(MainEntryPoint.class.getClassLoader());
        if (collector != null) {
            return collector;
        }
        File servicesDir = new File(glowrootDir, "services");
        if (!servicesDir.exists()) {
            return null;
        }
        if (!servicesDir.isDirectory()) {
            return null;
        }
        File[] files = servicesDir.listFiles();
        if (files == null) {
            return null;
        }
        List<URL> urls = Lists.newArrayList();
        for (File file : files) {
            if (file.isFile() && file.getName().endsWith(".jar")) {
                urls.add(file.toURI().toURL());
            }
        }
        if (urls.isEmpty()) {
            return null;
        }
        URLClassLoader servicesClassLoader = new URLClassLoader(urls.toArray(new URL[0]));
        return loadCollector(servicesClassLoader);
    }

    private static @Nullable Collector loadCollector(@Nullable ClassLoader classLoader) {
        ServiceLoader<Collector> serviceLoader = ServiceLoader.load(Collector.class, classLoader);
        Iterator<Collector> i = serviceLoader.iterator();
        if (!i.hasNext()) {
            return null;
        }
        return i.next();
    }

    private static void upgradeToCollectorAddressIfNeeded(File propFile) throws IOException {
        // properties files must be ISO_8859_1
        List<String> lines = Files.readLines(propFile, Charsets.ISO_8859_1);
        List<String> newLines = upgradeToCollectorAddressIfNeeded(lines);
        if (!newLines.equals(lines)) {
            // properties files must be ISO_8859_1
            PrintWriter out = new PrintWriter(Files.newWriter(propFile, Charsets.ISO_8859_1));
            try {
                for (String newLine : newLines) {
                    out.println(newLine);
                }
            } finally {
                out.close();
            }
        }
    }

    @VisibleForTesting
    static List<String> upgradeToCollectorAddressIfNeeded(List<String> lines) {
        List<String> newLines = Lists.newArrayList();
        String host = null;
        String port = null;
        int indexForAddress = -1;
        for (String line : lines) {
            if (line.startsWith("collector.host=")) {
                host = line.substring("collector.host=".length());
                if (indexForAddress == -1) {
                    indexForAddress = newLines.size();
                }
            } else if (line.startsWith("collector.port=")) {
                port = line.substring("collector.port=".length());
                if (indexForAddress == -1) {
                    indexForAddress = newLines.size();
                }
            } else if (line.startsWith("collector.address=")) {
                return lines;
            } else {
                newLines.add(line);
            }
        }
        if (indexForAddress == -1) {
            return newLines;
        }
        if (host == null) {
            return newLines;
        }
        if (host.isEmpty()) {
            newLines.add(indexForAddress, "collector.address=");
            return newLines;
        }
        if (port == null || port.isEmpty()) {
            port = "8181";
        }
        newLines.add(indexForAddress, "collector.address=" + host + ":" + port);
        return newLines;
    }

    @OnlyUsedByTests
    public static void start(Map<String, String> properties) throws Exception {
        String testDirPath = properties.get("glowroot.test.dir");
        checkNotNull(testDirPath);
        File testDir = new File(testDirPath);
        // init logger as early as possible
        initLogging(testDir, testDir);
        start(testDir, testDir, properties, null);
    }

    @OnlyUsedByTests
    public static @Nullable GlowrootAgentInit getGlowrootAgentInit() {
        return glowrootAgentInit;
    }
}
