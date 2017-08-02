/*
 * Copyright 2015-2017 the original author or authors.
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

import javax.annotation.Nullable;

import com.google.common.base.StandardSystemProperty;

import org.glowroot.common.util.OnlyUsedByTests;

public class AppServerDetection {

    private static final @Nullable String command = makeCommand();

    private AppServerDetection() {}

    public static @Nullable String getCommand() {
        return command;
    }

    public static boolean isIbmJvm() {
        String vmName = StandardSystemProperty.JAVA_VM_NAME.value();
        return vmName != null && vmName.equals("IBM J9 VM");
    }

    public static boolean isJBossModules() {
        return isJBossModules(command);
    }

    static boolean isOldJBoss() {
        return command != null && command.equals("org.jboss.Main");
    }

    static boolean isGlassfish() {
        return command != null && command.equals("com.sun.enterprise.glassfish.bootstrap.ASMain");
    }

    static boolean isWebLogic() {
        return command != null && command.equals("weblogic.Server");
    }

    static boolean isWebSphere() {
        return command != null && command.equals("com.ibm.wsspi.bootstrap.WSPreLauncher");
    }

    @OnlyUsedByTests
    static boolean isJBossModules(@Nullable String command) {
        return command != null && (command.equals("org.jboss.modules.Main")
                || command.endsWith("jboss-modules.jar"));
    }

    @OnlyUsedByTests
    static @Nullable String makeCommand() {
        String sunJavaCommand = System.getProperty("sun.java.command");
        if (sunJavaCommand == null) {
            return null;
        }
        int index = sunJavaCommand.indexOf(' ');
        if (index == -1) {
            return sunJavaCommand;
        }
        String firstArg = sunJavaCommand.substring(0, index);
        if (firstArg.startsWith("org.tanukisoftware.wrapper.")) {
            int nextIndex = sunJavaCommand.indexOf(' ', index + 1);
            if (nextIndex == -1) {
                return sunJavaCommand.substring(index + 1);
            }
            return sunJavaCommand.substring(index + 1, nextIndex);
        }
        return firstArg;
    }
}
