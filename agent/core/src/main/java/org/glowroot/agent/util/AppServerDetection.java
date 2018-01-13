/*
 * Copyright 2015-2018 the original author or authors.
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
package org.glowroot.agent.util;

import java.io.IOException;
import java.util.jar.JarFile;
import java.util.jar.Manifest;

import javax.annotation.Nullable;

import com.google.common.base.StandardSystemProperty;
import com.google.common.io.Closer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.glowroot.common.util.OnlyUsedByTests;

public class AppServerDetection {

    private static final Logger logger = LoggerFactory.getLogger(AppServerDetection.class);

    private static final @Nullable String MAIN_CLASS = buildMainClass();

    private AppServerDetection() {}

    public static boolean isIbmJvm() {
        return "IBM J9 VM".equals(StandardSystemProperty.JAVA_VM_NAME.value());
    }

    static boolean isJBossModules() {
        return "org.jboss.modules.Main".equals(MAIN_CLASS);
    }

    static boolean isWildflySwarm() {
        return "org.wildfly.swarm.bootstrap.Main".equals(MAIN_CLASS);
    }

    static boolean isOldJBoss() {
        return "org.jboss.Main".equals(MAIN_CLASS);
    }

    static boolean isGlassfish() {
        return "com.sun.enterprise.glassfish.bootstrap.ASMain".equals(MAIN_CLASS);
    }

    static boolean isWebLogic() {
        return "weblogic.Server".equals(MAIN_CLASS);
    }

    static boolean isWebSphere() {
        return "com.ibm.wsspi.bootstrap.WSPreLauncher".equals(MAIN_CLASS);
    }

    @OnlyUsedByTests
    static @Nullable String buildMainClass() {
        String sunJavaCommand = System.getProperty("sun.java.command");
        if (sunJavaCommand == null) {
            return null;
        }
        int index = sunJavaCommand.indexOf(' ');
        if (index == -1) {
            return getMainClassFromJarIfNeeded(sunJavaCommand);
        }
        String firstArg = sunJavaCommand.substring(0, index);
        if (firstArg.startsWith("org.tanukisoftware.wrapper.")) {
            int nextIndex = sunJavaCommand.indexOf(' ', index + 1);
            if (nextIndex == -1) {
                return sunJavaCommand.substring(index + 1);
            }
            return sunJavaCommand.substring(index + 1, nextIndex);
        }
        return getMainClassFromJarIfNeeded(firstArg);
    }

    private static @Nullable String getMainClassFromJarIfNeeded(String mainClassOrJarFile) {
        if (!mainClassOrJarFile.endsWith(".jar")) {
            return mainClassOrJarFile;
        }
        Manifest manifest = null;
        try {
            Closer closer = Closer.create();
            try {
                JarFile jarFile = closer.register(new JarFile(mainClassOrJarFile));
                manifest = jarFile.getManifest();
            } catch (Throwable t) {
                throw closer.rethrow(t);
            } finally {
                closer.close();
            }
        } catch (IOException e) {
            logger.debug(e.getMessage(), e);
        }
        return manifest == null ? null : manifest.getMainAttributes().getValue("Main-Class");
    }
}
