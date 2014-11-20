/*
 * Copyright 2011-2014 the original author or authors.
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
package org.glowroot.tests;

import java.io.File;
import java.io.IOException;
import java.util.Enumeration;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

import com.google.common.collect.Lists;
import org.junit.Assume;
import org.junit.Test;

import org.glowroot.container.ClassPath;

import static org.assertj.core.api.Assertions.assertThat;

public class JarFileShadingIT {

    @Test
    public void shouldCheckThatJarIsWellShaded() throws IOException {
        Assume.assumeTrue(isShaded());
        List<String> acceptableEntries = Lists.newArrayList();
        acceptableEntries.add("glowroot\\..*");
        acceptableEntries.add("org/");
        acceptableEntries.add("org/glowroot/.*");
        acceptableEntries.add("META-INF/");
        acceptableEntries.add("META-INF/maven/.*");
        acceptableEntries.add("META-INF/glowroot\\..*");
        acceptableEntries.add("META-INF/services/");
        acceptableEntries.add("META-INF/services/org.glowroot\\..*");
        acceptableEntries.add("META-INF/MANIFEST\\.MF");
        acceptableEntries.add("META-INF/LICENSE");
        acceptableEntries.add("META-INF/NOTICE");
        File glowrootCoreJarFile = ClassPath.getGlowrootCoreJarFile();
        JarFile jarFile = new JarFile(glowrootCoreJarFile);
        List<String> unacceptableEntries = Lists.newArrayList();
        for (Enumeration<JarEntry> e = jarFile.entries(); e.hasMoreElements();) {
            JarEntry jarEntry = e.nextElement();
            if (!acceptableJarEntry(jarEntry, acceptableEntries)) {
                unacceptableEntries.add(jarEntry.getName());
            }
        }
        assertThat(unacceptableEntries).isEmpty();
    }

    private static boolean acceptableJarEntry(JarEntry jarEntry, List<String> acceptableEntries) {
        for (String acceptableEntry : acceptableEntries) {
            if (jarEntry.getName().matches(acceptableEntry)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isShaded() {
        try {
            Class.forName("org.glowroot.shaded.slf4j.Logger");
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }
}
