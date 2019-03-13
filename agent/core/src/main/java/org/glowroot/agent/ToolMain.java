/*
 * Copyright 2018-2019 the original author or authors.
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
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.security.CodeSource;

import org.glowroot.common.util.Version;

public abstract class ToolMain {

    public static void main(String[] args) throws Exception {
        CodeSource codeSource = AgentPremain.class.getProtectionDomain().getCodeSource();
        // suppress warnings is used instead of annotating this method with @Nullable
        // just to avoid dependencies on other classes (in this case the @Nullable annotation)
        @SuppressWarnings("argument.type.incompatible")
        File glowrootJarFile = AgentPremain.getGlowrootJarFile(codeSource);
        Directories directories = new Directories(glowrootJarFile);
        MainEntryPoint.initLogging(directories.getConfDirs(), directories.getLogDir(),
                directories.getLoggingLogstashJarFile(), null);
        if (args.length == 1 && args[0].equals("version")) {
            System.out.println(Version.getVersion(MainEntryPoint.class));
            return;
        }
        File embeddedCollectorJarFile = Directories.getEmbeddedCollectorJarFile(glowrootJarFile);
        if (embeddedCollectorJarFile == null) {
            System.err.println("missing lib/glowroot-embedded-collector.jar");
            return;
        }
        ClassLoader loader =
                new URLClassLoader(new URL[] {embeddedCollectorJarFile.toURI().toURL()});
        Class<?> clazz = Class.forName("org.glowroot.agent.embedded.ToolMain", true, loader);
        Method method = clazz.getMethod("main", String[].class, Directories.class);
        method.invoke(null, args, directories);
    }
}
