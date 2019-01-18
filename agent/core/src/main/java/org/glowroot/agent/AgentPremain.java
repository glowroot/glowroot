/*
 * Copyright 2014-2019 the original author or authors.
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
import java.lang.instrument.Instrumentation;
import java.lang.reflect.Method;
import java.security.CodeSource;
import java.util.jar.JarFile;

// this class is registered as the Premain-Class in the MANIFEST.MF of glowroot.jar
//
// this class should have minimal dependencies since it will live in the system class loader while
// the rest of glowroot will live in the bootstrap class loader
public class AgentPremain {

    private static final boolean PRE_CHECK_LOADED_CLASSES =
            Boolean.getBoolean("glowroot.debug.preCheckLoadedClasses");

    private AgentPremain() {}

    // javaagent entry point
    public static void premain(@SuppressWarnings("unused") String agentArgs,
            Instrumentation instrumentation) {
        try {
            Class<?>[] allPriorLoadedClasses;
            if (PRE_CHECK_LOADED_CLASSES) {
                allPriorLoadedClasses = instrumentation.getAllLoadedClasses();
            } else {
                allPriorLoadedClasses = new Class<?>[0];
            }
            CodeSource codeSource = AgentPremain.class.getProtectionDomain().getCodeSource();
            // suppress warnings is used instead of annotating this method with @Nullable
            // just to avoid dependencies on other classes (in this case the @Nullable annotation)
            @SuppressWarnings("argument.type.incompatible")
            File glowrootJarFile = getGlowrootJarFile(codeSource);
            Class<?> mainEntryPointClass;
            if (glowrootJarFile != null) {
                instrumentation.appendToBootstrapClassLoaderSearch(new JarFile(glowrootJarFile));
            }
            mainEntryPointClass = Class.forName("org.glowroot.agent.MainEntryPoint", true,
                    AgentPremain.class.getClassLoader());
            Method premainMethod = mainEntryPointClass.getMethod("premain", Instrumentation.class,
                    Class[].class, File.class);
            premainMethod.invoke(null, instrumentation, allPriorLoadedClasses, glowrootJarFile);
        } catch (Throwable t) {
            // log error but don't re-throw which would prevent monitored app from starting
            System.err.println("Glowroot failed to start: " + t.getMessage());
            t.printStackTrace();
        }
    }

    // suppress warnings is used instead of annotating this method with @Nullable
    // just to avoid dependencies on other classes (in this case the @Nullable annotation)
    @SuppressWarnings("return.type.incompatible")
    static File getGlowrootJarFile(CodeSource codeSource) throws Exception {
        if (codeSource == null) {
            if (System.getProperty("glowroot.test.dir") != null) {
                // this is ok, running tests under delegating javaagent
                return null;
            }
            throw new IOException("Could not determine glowroot jar location");
        }
        File codeSourceFile = new File(codeSource.getLocation().toURI());
        if (codeSourceFile.getName().endsWith(".jar")) {
            return codeSourceFile;
        }
        if (System.getProperty("glowroot.test.dir") != null) {
            // this is ok, running tests under delegating javaagent
            return null;
        }
        throw new IOException("Could not determine glowroot jar location");
    }
}
