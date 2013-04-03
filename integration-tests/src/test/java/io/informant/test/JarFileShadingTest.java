/**
 * Copyright 2011-2013 the original author or authors.
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

import io.informant.MainEntryPoint;
import io.informant.container.ClassPath;

import java.io.File;
import java.io.FilenameFilter;
import java.io.IOException;
import java.util.Enumeration;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

import org.junit.Assume;
import org.junit.Test;

import checkers.nullness.quals.Nullable;

import com.google.common.collect.Lists;

/**
 * @author Trask Stalnaker
 * @since 0.5
 */
public class JarFileShadingTest {

    @Test
    public void shouldCheckThatJarIsWellShaded() throws IOException {
        File informantJarFile = ClassPath.getInformantJarFile();
        if (informantJarFile == null) {
            if (System.getProperty("surefire.test.class.path") != null) {
                throw new IllegalStateException(
                        "Running inside maven and can't find informant.jar on class path");
            }
            // try to cover the non-standard case when running outside of maven (e.g. inside an IDE)
            informantJarFile = getInformantJarFileFromRelativePath();
            // don't worry if informant jar can't be found while running outside of maven
            Assume.assumeNotNull(informantJarFile);
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
        JarFile jarFile = new JarFile(informantJarFile);
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

    // try to cover the non-standard case when running from inside an IDE
    @Nullable
    private static File getInformantJarFileFromRelativePath() {
        String classesDir = MainEntryPoint.class.getProtectionDomain().getCodeSource()
                .getLocation().getFile();
        // guessing this is target/classes
        File targetDir = new File(classesDir).getParentFile();
        File[] possibleMatches = targetDir.listFiles(new FilenameFilter() {
            public boolean accept(File dir, String name) {
                return name.matches("informant-[0-9.]+(-SNAPSHOT)?.jar");
            }
        });
        if (possibleMatches == null || possibleMatches.length == 0) {
            return null;
        } else if (possibleMatches.length == 1) {
            return possibleMatches[0];
        } else {
            throw new IllegalStateException("More than one possible match found for informant.jar");
        }
    }
}
