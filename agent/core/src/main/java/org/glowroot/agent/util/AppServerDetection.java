/*
 * Copyright 2015-2016 the original author or authors.
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

public class AppServerDetection {

    private AppServerDetection() {}

    public static boolean isJBossModules() {
        String command = getCommand();
        return command != null && (command.equals("org.jboss.modules.Main")
                || command.endsWith("jboss-modules.jar"));
    }

    static boolean isOldJBoss() {
        String command = getCommand();
        return command != null && command.equals("org.jboss.Main");
    }

    static boolean isGlassfish() {
        String command = getCommand();
        return command != null && command.equals("com.sun.enterprise.glassfish.bootstrap.ASMain");
    }

    private static @Nullable String getCommand() {
        String sunJavaCommand = System.getProperty("sun.java.command");
        if (sunJavaCommand == null) {
            return null;
        }
        int index = sunJavaCommand.indexOf(' ');
        return index == -1 ? sunJavaCommand : sunJavaCommand.substring(0, index);
    }
}
