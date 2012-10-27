/**
 * Copyright 2011-2012 the original author or authors.
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
package io.informant.test;

import static org.fest.assertions.api.Assertions.assertThat;
import io.informant.testkit.internal.InformantCoreJar;

import java.io.File;
import java.io.IOException;
import java.util.Enumeration;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

import org.junit.Assume;
import org.junit.Test;

import com.google.common.collect.Lists;

/**
 * @author Trask Stalnaker
 * @since 0.5
 */
public class JarFileShadingTest {

    @Test
    public void shouldCheckThatJarIsWellShaded() throws IOException {
        File informantCoreJarFile = InformantCoreJar.getFileOptional();
        if (informantCoreJarFile == null) {
            // if not running maven build (e.g. running in IDE), then just silently skip this test
            Assume.assumeNotNull(System.getProperty("surefire.test.class.path"));
            // got past assumption, must be running maven build, fail the test instead of skipping
            throw new IllegalStateException(
                    "Running inside maven and can't find informant-core.jar");
        }
        List<String> acceptableEntries = Lists.newArrayList();
        acceptableEntries.add("io.informant\\..*");
        acceptableEntries.add("io/");
        acceptableEntries.add("io/informant/.*");
        acceptableEntries.add("META-INF/");
        acceptableEntries.add("META-INF/maven/.*");
        acceptableEntries.add("META-INF/io.informant\\..*");
        acceptableEntries.add("META-INF/MANIFEST\\.MF");
        acceptableEntries.add("META-INF/THIRD-PARTY\\.txt");
        acceptableEntries.add("META-INF/THIRD-PARTY-RESOURCES\\.txt");
        JarFile jarFile = new JarFile(informantCoreJarFile);
        List<String> unacceptableEntries = Lists.newArrayList();
        for (Enumeration<JarEntry> e = jarFile.entries(); e.hasMoreElements();) {
            JarEntry jarEntry = e.nextElement();
            boolean acceptable = false;
            for (String acceptableEntry : acceptableEntries) {
                if (jarEntry.getName().matches(acceptableEntry)) {
                    acceptable = true;
                    break;
                }
            }
            if (!acceptable) {
                unacceptableEntries.add(jarEntry.getName());
            }
        }
        assertThat(unacceptableEntries).isEmpty();
    }
}
