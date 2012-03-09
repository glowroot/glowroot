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
package org.informantproject.test;

import static org.hamcrest.CoreMatchers.is;
import static org.junit.Assert.assertThat;

import java.io.File;
import java.io.FilenameFilter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Enumeration;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

import org.junit.Test;

/**
 * @author Trask Stalnaker
 * @since 0.5
 */
public class JarFileShadingTest {

    @Test
    public void shouldCheckThatJarIsWellShaded() throws IOException {
        File informantCoreJarFile = findInformantCoreJarFileFromClasspath();
        if (informantCoreJarFile == null) {
            informantCoreJarFile = findInformantCoreJarFileFromRelativePath();
        }
        if (informantCoreJarFile == null
                && System.getProperty("surefire.test.class.path") != null) {
            throw new IllegalStateException("running inside maven and can't find"
                    + " informant-core.jar");
        }
        if (informantCoreJarFile == null) {
            throw new IllegalStateException("You are probably running this test outside of maven"
                    + " (e.g. you are running this test from inside of Eclipse).  This test"
                    + " requires informant-core.jar to be available.  The easiest way to build"
                    + " informant-core.jar is to run 'mvn clean package' from the root directory"
                    + " of this git repository.  After that you can re-run this test outside of"
                    + " maven (e.g. from inside of Eclipse) and it should succeed.");
        }
        List<String> acceptableEntries = new ArrayList<String>();
        acceptableEntries.add("org.informantproject\\..*");
        acceptableEntries.add("org/");
        acceptableEntries.add("org/informantproject/.*");
        acceptableEntries.add("META-INF/");
        acceptableEntries.add("META-INF/maven/.*");
        acceptableEntries.add("META-INF/org.informantproject\\..*");
        acceptableEntries.add("META-INF/MANIFEST\\.MF");
        acceptableEntries.add("META-INF/THIRD-PARTY\\.txt");
        JarFile jarFile = new JarFile(informantCoreJarFile);
        List<String> unacceptableEntries = new ArrayList<String>();
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
        assertThat(unacceptableEntries, is(Arrays.asList(new String[0])));
    }

    // this covers the case where "mvn integration-test" is executed from inside the
    // informant-integration-test directory
    // this case wouldn't necessarily be covered by the relative path case below
    private static File findInformantCoreJarFileFromClasspath() {
        String classpath = System.getProperty("java.class.path");
        String[] classpathElements = classpath.split(File.pathSeparator);
        for (String classpathElement : classpathElements) {
            File classpathElementFile = new File(classpathElement);
            if (classpathElementFile.getName().matches("informant-core-[0-9.]+(-SNAPSHOT)?.jar")) {
                return classpathElementFile;
            }
        }
        return null;
    }

    // this is mostly for convenience as it covers the case where this test is executed from
    // inside eclipse after informant-core.jar file has been built into ../core/target
    private static File findInformantCoreJarFileFromRelativePath() {
        File[] possibleMatches = new File("../core/target").listFiles(new FilenameFilter() {
            public boolean accept(File dir, String name) {
                return name.matches("informant-core-[0-9.]+(-SNAPSHOT)?.jar");
            }
        });
        if (possibleMatches.length == 0) {
            return null;
        } else if (possibleMatches.length == 1) {
            return possibleMatches[0];
        } else {
            throw new IllegalStateException("More than one possible match found for"
                    + " informant-core.jar");
        }
    }
}
