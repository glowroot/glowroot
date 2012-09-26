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
package org.informantproject.testkit.internal;

import java.io.File;
import java.io.FilenameFilter;

import javax.annotation.Nullable;

import org.informantproject.core.MainEntryPoint;

/**
 * @author Trask Stalnaker
 * @since 0.5
 */
public final class InformantCoreJar {

    public static File getFile() {
        File informantCoreJarFile = getFileFromClasspath();
        if (informantCoreJarFile != null) {
            return informantCoreJarFile;
        }
        informantCoreJarFile = getFileFromRelativePath();
        if (informantCoreJarFile != null) {
            return informantCoreJarFile;
        }
        // could not find jar file, try to give intelligible error
        if (System.getProperty("surefire.test.class.path") != null) {
            throw new IllegalStateException(
                    "Running inside maven and can't find informant-core.jar");
        } else {
            throw new IllegalStateException("You are probably running this test outside of maven"
                    + " (e.g. you are running this test from inside of your IDE).  This test"
                    + " requires informant-core.jar to be available.  The easiest way to build"
                    + " informant-core.jar is to run 'mvn clean package' from the root directory"
                    + " of this git repository.  After that you can re-run this test outside of"
                    + " maven (e.g. from inside of your IDE) and it should succeed.");
        }
    }

    // cover the standard case when running from maven
    @Nullable
    private static File getFileFromClasspath() {
        String classpath = System.getProperty("java.class.path");
        String[] classpathElements = classpath.split(File.pathSeparator);
        for (String classpathElement : classpathElements) {
            File classpathElementFile = new File(classpathElement);
            if (isInformantCoreJar(classpathElementFile.getName())) {
                return classpathElementFile;
            }
        }
        return null;
    }

    // cover the non-standard case when running from inside an IDE
    @Nullable
    private static File getFileFromRelativePath() {
        String classesDir = MainEntryPoint.class.getProtectionDomain().getCodeSource()
                .getLocation().getFile();
        // guessing this is target/classes
        File targetDir = new File(classesDir).getParentFile();
        File[] possibleMatches = targetDir.listFiles(new FilenameFilter() {
            public boolean accept(File dir, String name) {
                return isInformantCoreJar(name);
            }
        });
        if (possibleMatches == null || possibleMatches.length == 0) {
            return null;
        } else if (possibleMatches.length == 1) {
            return possibleMatches[0];
        } else {
            throw new IllegalStateException("More than one possible match found for"
                    + " informant-core.jar");
        }
    }

    private static boolean isInformantCoreJar(String name) {
        return name.matches("informant-core-[0-9.]+(-SNAPSHOT)?.jar");
    }

    private InformantCoreJar() {}
}
