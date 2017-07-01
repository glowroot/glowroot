/*
 * Copyright 2012-2017 the original author or authors.
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
package org.glowroot.agent.it.harness.impl;

import java.io.File;
import java.io.FileOutputStream;
import java.lang.instrument.Instrumentation;
import java.lang.reflect.Method;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;

import com.google.common.io.Resources;

import org.glowroot.agent.weaving.ClassNames;

class DelegatingJavaagent {

    private static final String DELEGATE_JAVA_AGENT_PROPERTY = "delegateJavaagent";

    private DelegatingJavaagent() {}

    public static void premain(String agentArgs, Instrumentation instrumentation) throws Exception {
        String delegateJavaagent = System.getProperty(DELEGATE_JAVA_AGENT_PROPERTY);
        if (delegateJavaagent == null) {
            throw new IllegalStateException(
                    "System property '" + DELEGATE_JAVA_AGENT_PROPERTY + "' is not set");
        }
        Class<?> delegateClass = Class.forName(delegateJavaagent);
        Method delegateMethod =
                delegateClass.getMethod("premain", String.class, Instrumentation.class);
        delegateMethod.invoke(null, agentArgs, instrumentation);
    }

    static File createDelegatingJavaagentJarFile(File dir) throws Exception {
        File jarFile = File.createTempFile("glowroot-", ".jar", dir);
        Manifest manifest = new Manifest();
        manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        manifest.getMainAttributes().put(new Attributes.Name("Premain-Class"),
                DelegatingJavaagent.class.getName());
        manifest.getMainAttributes().put(new Attributes.Name("Can-Redefine-Classes"), "true");
        manifest.getMainAttributes().put(new Attributes.Name("Can-Retransform-Classes"), "true");
        String resourceName =
                ClassNames.toInternalName(DelegatingJavaagent.class.getName()) + ".class";
        JarOutputStream out = new JarOutputStream(new FileOutputStream(jarFile), manifest);
        try {
            out.putNextEntry(new JarEntry(resourceName));
            Resources.asByteSource(Resources.getResource(resourceName)).copyTo(out);
            out.closeEntry();
        } finally {
            out.close();
        }
        return jarFile;
    }
}
