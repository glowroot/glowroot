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
import java.lang.instrument.Instrumentation;
import java.lang.management.ManagementFactory;
import java.util.Map;
import java.util.Map.Entry;

import javax.annotation.Nullable;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Objects;
import com.google.common.base.Strings;
import com.google.common.collect.ImmutableMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.glowroot.agent.DataDirLocking.BaseDirLockedException;
import org.glowroot.common.util.OnlyUsedByTests;

public class MainEntryPoint {

    // log startup messages using logger name "org.glowroot"
    private static final Logger startupLogger = LoggerFactory.getLogger("org.glowroot");

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
        ImmutableMap<String, String> properties = getGlowrootProperties();
        File baseDir = BaseDir.getBaseDir(properties, glowrootJarFile);
        try {
            start(baseDir, properties, instrumentation, glowrootJarFile, jbossModules);
        } catch (BaseDirLockedException e) {
            logBaseDirLockedException(baseDir);
        } catch (Throwable t) {
            // log error but don't re-throw which would prevent monitored app from starting
            startupLogger.error("Glowroot not started: {}", t.getMessage(), t);
        }
    }

    public static void runViewer(@Nullable File glowrootJarFile) throws InterruptedException {
        ImmutableMap<String, String> properties = getGlowrootProperties();
        File baseDir = BaseDir.getBaseDir(properties, glowrootJarFile);
        String version = Version.getVersion();
        GlowrootAgentInit glowrootModule = getGlowrootAgentInit();
        try {
            boolean loggingSpy = Boolean.parseBoolean(properties.get("internal.logging.spy"));
            LoggingInit.initStaticLoggerState(baseDir, loggingSpy);
            glowrootModule.init(baseDir, properties, null, glowrootJarFile, version, false, true);
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

    private static void start(File baseDir, Map<String, String> properties,
            @Nullable Instrumentation instrumentation, @Nullable File glowrootJarFile,
            boolean jbossModules) throws Exception {
        ManagementFactory.getThreadMXBean().setThreadCpuTimeEnabled(true);
        ManagementFactory.getThreadMXBean().setThreadContentionMonitoringEnabled(true);
        String version = Version.getVersion();
        GlowrootAgentInit glowrootModule = getGlowrootAgentInit();
        boolean loggingSpy = Boolean.parseBoolean(properties.get("internal.logging.spy"));
        LoggingInit.initStaticLoggerState(baseDir, loggingSpy);
        glowrootModule.init(baseDir, properties, instrumentation, glowrootJarFile, version, false,
                jbossModules);
        startupLogger.info("Glowroot started (version {})", version);
    }

    private static GlowrootAgentInit getGlowrootAgentInit() {
        try {
            Class<?> glowrootAgentInitClass =
                    Class.forName("org.glowroot.agent.fat.GlowrootFatAgentInit");
            return (GlowrootAgentInit) glowrootAgentInitClass.newInstance();
        } catch (Exception e) {
            return new GlowrootThinAgentInit();
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
        File baseDir = BaseDir.getBaseDir(properties, null);
        start(baseDir, properties, null, null, false);
    }
}
