/*
 * Copyright 2011-2016 the original author or authors.
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
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
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

import com.google.common.base.Objects;
import com.google.common.base.Strings;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import org.checkerframework.checker.nullness.qual.EnsuresNonNull;
import org.checkerframework.checker.nullness.qual.MonotonicNonNull;
import org.checkerframework.checker.nullness.qual.RequiresNonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.glowroot.agent.fat.init.DataDirLocking.BaseDirLockedException;
import org.glowroot.agent.fat.init.GlowrootFatAgentInit;
import org.glowroot.agent.init.GlowrootAgentInit;
import org.glowroot.agent.init.GlowrootThinAgentInit;
import org.glowroot.agent.util.AppServerDetection;
import org.glowroot.common.util.OnlyUsedByTests;
import org.glowroot.common.util.Version;
import org.glowroot.wire.api.Collector;

import static com.google.common.base.Preconditions.checkNotNull;

public class MainEntryPoint {

    // need to wait to init logger until
    private static volatile @MonotonicNonNull Logger startupLogger;

    @OnlyUsedByTests
    private static @MonotonicNonNull GlowrootAgentInit glowrootAgentInit;

    private MainEntryPoint() {}

    public static void premain(Instrumentation instrumentation, @Nullable File glowrootJarFile) {
        boolean jbossModules = AppServerDetection.isJBossModules();
        if (jbossModules) {
            String jbossModulesSystemPkgs = System.getProperty("jboss.modules.system.pkgs");
            if (Strings.isNullOrEmpty(jbossModulesSystemPkgs)) {
                jbossModulesSystemPkgs = "org.glowroot.agent";
            } else {
                jbossModulesSystemPkgs += ",org.glowroot.agent";
            }
            System.setProperty("jboss.modules.system.pkgs", jbossModulesSystemPkgs);
        }
        String baseDirPath = System.getProperty("glowroot.base.dir");
        File baseDir = BaseDir.getBaseDir(baseDirPath, glowrootJarFile);
        // init logger as early as possible
        instrumentation.addTransformer(new LogbackPatch());
        initLogging(baseDir);
        try {
            ImmutableMap<String, String> properties = getGlowrootProperties(baseDir);
            start(baseDir, properties, instrumentation, glowrootJarFile);
        } catch (BaseDirLockedException e) {
            logBaseDirLockedException(baseDir);
        } catch (Throwable t) {
            // log error but don't re-throw which would prevent monitored app from starting
            startupLogger.error("Glowroot not started: {}", t.getMessage(), t);
        }
    }

    static void runViewer(File baseDir, @Nullable File glowrootJarFile)
            throws InterruptedException {
        // initLogging() already called by OfflineViewer.main()
        checkNotNull(startupLogger);
        String version;
        try {
            version = Version.getVersion(MainEntryPoint.class);
            startupLogger.info("Glowroot version: {}", version);
            ImmutableMap<String, String> properties = getGlowrootProperties(baseDir);
            new GlowrootFatAgentInit().init(baseDir, null, null, properties, null, glowrootJarFile,
                    version, true);
        } catch (BaseDirLockedException e) {
            logBaseDirLockedException(baseDir);
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
    static void initLogging(File baseDir) {
        File logbackXmlOverride = new File(baseDir, "glowroot.logback.xml");
        if (logbackXmlOverride.exists()) {
            System.setProperty("glowroot.logback.configurationFile",
                    logbackXmlOverride.getAbsolutePath());
        }
        String prior = System.getProperty("glowroot.base.dir");
        try {
            System.setProperty("glowroot.base.dir", baseDir.getPath());
            startupLogger = LoggerFactory.getLogger("org.glowroot");
        } finally {
            System.clearProperty("glowroot.logback.configurationFile");
            if (prior == null) {
                System.clearProperty("glowroot.base.dir");
            } else {
                System.setProperty("glowroot.base.dir", prior);
            }
        }
        // TODO report checker framework issue that occurs without checkNotNull
        checkNotNull(startupLogger);
    }

    @RequiresNonNull("startupLogger")
    private static void start(File baseDir, Map<String, String> properties,
            @Nullable Instrumentation instrumentation, @Nullable File glowrootJarFile)
            throws Exception {
        ManagementFactory.getThreadMXBean().setThreadCpuTimeEnabled(true);
        ManagementFactory.getThreadMXBean().setThreadContentionMonitoringEnabled(true);
        String version = Version.getVersion(MainEntryPoint.class);
        startupLogger.info("Glowroot version: {}", version);
        String collectorHost = properties.get("glowroot.collector.host");
        if (Strings.isNullOrEmpty(collectorHost)) {
            collectorHost = System.getProperty("glowroot.collector.host");
        }
        Collector customCollector = loadCustomCollector(baseDir);
        if (Strings.isNullOrEmpty(collectorHost) && customCollector == null) {
            glowrootAgentInit = new GlowrootFatAgentInit();
        } else {
            if (customCollector != null) {
                startupLogger.info("Using collector: {}", customCollector.getClass().getName());
            }
            glowrootAgentInit = new GlowrootThinAgentInit();
        }
        glowrootAgentInit.init(baseDir, collectorHost, customCollector, properties, instrumentation,
                glowrootJarFile, version, false);
    }

    private static ImmutableMap<String, String> getGlowrootProperties(File baseDir)
            throws IOException {
        Map<String, String> properties = Maps.newHashMap();
        File propFile = new File(baseDir, "glowroot.properties");
        if (propFile.exists()) {
            Properties props = new Properties();
            InputStream in = new FileInputStream(propFile);
            try {
                props.load(in);
            } finally {
                in.close();
            }
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
    private static void logBaseDirLockedException(File baseDir) {
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
            startupLogger.error("Glowroot not started: data dir in used by another jvm process",
                    baseDir.getAbsolutePath());
        }
    }

    private static boolean isTomcatStop() {
        return Objects.equal(System.getProperty("sun.java.command"),
                "org.apache.catalina.startup.Bootstrap stop");
    }

    private static @Nullable Collector loadCustomCollector(File baseDir)
            throws MalformedURLException {
        Collector collector = loadCollector(MainEntryPoint.class.getClassLoader());
        if (collector != null) {
            return collector;
        }
        File servicesDir = new File(baseDir, "services");
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

    @OnlyUsedByTests
    public static void start(Map<String, String> properties) throws Exception {
        String baseDirPath = properties.get("glowroot.base.dir");
        File baseDir = BaseDir.getBaseDir(baseDirPath, null);
        // init logger as early as possible
        initLogging(baseDir);
        start(baseDir, properties, null, null);
    }

    @OnlyUsedByTests
    public static @Nullable GlowrootAgentInit getGlowrootAgentInit() {
        return glowrootAgentInit;
    }
}
