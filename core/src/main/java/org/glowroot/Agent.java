/*
 * Copyright 2014 the original author or authors.
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
import java.io.IOException;
import java.lang.instrument.Instrumentation;
import java.lang.reflect.Method;
import java.net.URISyntaxException;
import java.security.CodeSource;
import java.util.jar.JarFile;

/**
 * This class is registered as the Premain-Class in the MANIFEST.MF of glowroot.jar:
 * 
 * Premain-Class: org.glowroot.Agent
 * 
 * This defines the entry point when the JVM is launched via -javaagent:glowroot.jar.
 * 
 * @author Trask Stalnaker
 * @since 0.5
 */
// this class should have minimal dependencies since it will live in the system class loader while
// the rest of glowroot will live in the bootstrap class loader
public class Agent {

    private Agent() {}

    // javaagent entry point
    public static void premain(@SuppressWarnings("unused") String agentArgs,
            Instrumentation instrumentation) {
        try {
            File glowrootJarFile = getGlowrootJarFile();
            Class<?> mainEntryPointClass;
            if (glowrootJarFile == null) {
                // this is ok, running integration test in IDE
                mainEntryPointClass = Class.forName("org.glowroot.MainEntryPoint", true,
                        Agent.class.getClassLoader());
            } else {
                instrumentation.appendToBootstrapClassLoaderSearch(new JarFile(glowrootJarFile));
                mainEntryPointClass = Class.forName("org.glowroot.MainEntryPoint", true, null);
            }
            Method premainMethod = mainEntryPointClass.getMethod("premain", new Class<?>[] {
                    Instrumentation.class, File.class});
            premainMethod.invoke(null, instrumentation, glowrootJarFile);
        } catch (Throwable t) {
            // log error but don't re-throw which would prevent monitored app from starting
            System.err.println("Glowroot not started: " + t.getMessage());
            t.printStackTrace();
        }
    }

    // suppress warnings is used instead of annotating this method with @Nullable
    // just to avoid dependencies on other classes (in this case the @Nullable annotation)
    @SuppressWarnings("return.type.incompatible")
    public static File getGlowrootJarFile() throws IOException, URISyntaxException {
        CodeSource codeSource = Agent.class.getProtectionDomain().getCodeSource();
        if (codeSource == null) {
            if (System.getProperty("delegateJavaagent") != null) {
                // this is ok, running tests under delegating javaagent
                return null;
            }
            throw new IOException("Could not determine glowroot jar location");
        }
        File glowrootJarFile = new File(codeSource.getLocation().toURI());
        if (glowrootJarFile.getName().endsWith(".jar")) {
            return glowrootJarFile;
        }
        if (System.getProperty("delegateJavaagent") != null) {
            // this is ok, running tests under delegating javaagent
            return null;
        }
        throw new IOException("Could not determine glowroot jar location");
    }
}
