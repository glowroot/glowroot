/*
 * Copyright 2011-2015 the original author or authors.
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
import java.util.Map;
import java.util.Map.Entry;
import java.util.Properties;

import javax.annotation.Nullable;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Objects;
import com.google.common.base.Strings;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Maps;
import org.checkerframework.checker.nullness.qual.EnsuresNonNull;
import org.checkerframework.checker.nullness.qual.MonotonicNonNull;
import org.checkerframework.checker.nullness.qual.RequiresNonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.glowroot.agent.init.GlowrootAgentInit;
import org.glowroot.agent.init.GlowrootThinAgentInit;
import org.glowroot.agent.init.fat.DataDirLocking.BaseDirLockedException;
import org.glowroot.agent.init.fat.GlowrootFatAgentInit;
import org.glowroot.common.util.OnlyUsedByTests;
import org.glowroot.common.util.Version;

import static com.google.common.base.Preconditions.checkNotNull;

public class MainEntryPoint {

    // need to wait to init logger until
    private static volatile @MonotonicNonNull Logger startupLogger;

    @OnlyUsedByTests
    private static @MonotonicNonNull GlowrootAgentInit glowrootAgentInit;

    private MainEntryPoint() {}

    public static void premain(Instrumentation instrumentation, @Nullable File glowrootJarFile) {
        boolean jbossModules = isJBossModules();
        if (jbossModules) {
            String jbossModulesSystemPkgs = System.getProperty("jboss.modules.system.pkgs");
            if (Strings.isNullOrEmpty(jbossModulesSystemPkgs)) {
                jbossModulesSystemPkgs = "org.glowroot";
            } else {
                jbossModulesSystemPkgs += ",org.glowroot";
            }
            System.setProperty("jboss.modules.system.pkgs", jbossModulesSystemPkgs);
        }
        String baseDirPath = System.getProperty("glowroot.base.dir");
        File baseDir = BaseDir.getBaseDir(baseDirPath, glowrootJarFile);
        // init logger as early as possible
        initLogging(baseDir);
        try {
            ImmutableMap<String, String> properties = getGlowrootProperties(baseDir);
            start(baseDir, properties, instrumentation, glowrootJarFile, jbossModules);
        } catch (BaseDirLockedException e) {
            logBaseDirLockedException(baseDir);
        } catch (Throwable t) {
            // log error but don't re-throw which would prevent monitored app from starting
            startupLogger.error("Glowroot not started: {}", t.getMessage(), t);
        }
    }

    static void runViewer(@Nullable File glowrootJarFile) throws InterruptedException {
        String baseDirPath = System.getProperty("glowroot.base.dir");
        File baseDir = BaseDir.getBaseDir(baseDirPath, glowrootJarFile);
        // init logger as early as possible
        initLogging(baseDir);
        String version;
        try {
            version = Version.getVersion();
            ImmutableMap<String, String> properties = getGlowrootProperties(baseDir);
            new GlowrootFatAgentInit().init(baseDir, null, properties, null, glowrootJarFile,
                    version, false, true);
        } catch (BaseDirLockedException e) {
            logBaseDirLockedException(baseDir);
            return;
        } catch (Throwable t) {
            startupLogger.error("Glowroot cannot start: {}", t.getMessage(), t);
            return;
        }
        startupLogger.info("Glowroot started (version {})", version);
        // Glowroot does not create any non-daemon threads, so need to block jvm from exiting when
        // running the viewer
        Thread.sleep(Long.MAX_VALUE);
    }

    @EnsuresNonNull("startupLogger")
    private static void initLogging(File baseDir) {
        String prior = System.getProperty("glowroot.base.dir");
        try {
            System.setProperty("glowroot.base.dir", baseDir.getPath());
            startupLogger = LoggerFactory.getLogger("org.glowroot");
        } finally {
            if (prior == null) {
                System.clearProperty("glowroot.base.dir");
            } else {
                System.setProperty("glowroot.base.dir", prior);
            }
        }
        // checker framework is not currently detecting that startupLogger is non-null here
        checkNotNull(startupLogger);
    }

    @RequiresNonNull("startupLogger")
    private static void start(File baseDir, Map<String, String> properties,
            @Nullable Instrumentation instrumentation, @Nullable File glowrootJarFile,
            boolean jbossModules) throws Exception {
        ManagementFactory.getThreadMXBean().setThreadCpuTimeEnabled(true);
        ManagementFactory.getThreadMXBean().setThreadContentionMonitoringEnabled(true);
        String version = Version.getVersion();
        String collectorHost = properties.get("glowroot.collector.host");
        if (Strings.isNullOrEmpty(collectorHost)) {
            collectorHost = System.getProperty("glowroot.collector.host");
        }
        if (Strings.isNullOrEmpty(collectorHost)) {
            glowrootAgentInit = new GlowrootFatAgentInit();
        } else {
            glowrootAgentInit = new GlowrootThinAgentInit();
        }
        glowrootAgentInit.init(baseDir, collectorHost, properties, instrumentation, glowrootJarFile,
                version, false, jbossModules);
        startupLogger.info("Glowroot started (version {})", version);
    }

    private static ImmutableMap<String, String> getGlowrootProperties(File baseDir)
            throws IOException {
        Map<String, String> properties = Maps.newHashMap();
        File propFile = new File(baseDir, "glowroot.properties");
        if (propFile.exists()) {
            Properties props = new Properties();
            InputStream in = new FileInputStream(propFile);
            props.load(in);
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

    private static boolean isJBossModules() {
        return isJBossModules(System.getProperty("sun.java.command"));
    }

    @VisibleForTesting
    static boolean isJBossModules(@Nullable String command) {
        if (command == null) {
            return false;
        }
        int index = command.indexOf(' ');
        String className = index == -1 ? command : command.substring(0, index);
        return className.equals("org.jboss.modules.Main")
                || className.endsWith("jboss-modules.jar");
    }

    @OnlyUsedByTests
    public static void start(Map<String, String> properties) throws Exception {
        String baseDirPath = properties.get("glowroot.base.dir");
        File baseDir = BaseDir.getBaseDir(baseDirPath, null);
        // init logger as early as possible
        initLogging(baseDir);
        start(baseDir, properties, null, null, false);
    }

    @OnlyUsedByTests
    public static @Nullable GlowrootAgentInit getGlowrootAgentInit() {
        return glowrootAgentInit;
    }
}
