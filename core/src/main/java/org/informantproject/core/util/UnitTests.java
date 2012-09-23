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
package org.informantproject.core.util;

import java.io.File;
import java.io.FilenameFilter;
import java.lang.Thread.State;
import java.lang.annotation.Documented;
import java.util.Collection;
import java.util.List;

import javax.annotation.Nullable;

import com.google.common.base.Objects;
import com.google.common.base.Objects.ToStringHelper;
import com.google.common.base.Predicate;
import com.google.common.base.Predicates;
import com.google.common.collect.Collections2;
import com.google.common.collect.Lists;

/**
 * Only used by tests (except for the {@link OnlyUsedByTests} annotation itself). The placement of
 * this code (other than {@link OnlyUsedByTests}) in the main Informant code base (and not inside of
 * the tests folder) is not ideal, but the alternative is to create a separate artifact (or at least
 * classifier) for this small amount of code, which also seems to be not ideal.
 * 
 * @author Trask Stalnaker
 * @since 0.5
 */
@Static
public final class UnitTests {

    // marker annotation
    @Documented
    public @interface OnlyUsedByTests {}

    public static Collection<Thread> currentThreads() {
        return Collections2.filter(Thread.getAllStackTraces().keySet(),
                new Predicate<Thread>() {
                    public boolean apply(Thread input) {
                        return input.getState() != State.TERMINATED;
                    }
                });
    }

    // ensure the test didn't create any non-daemon threads
    public static void preShutdownCheck(final Collection<Thread> preExistingThreads)
            throws InterruptedException {

        // give the test 5 seconds to shutdown any threads they may have created, e.g. give tomcat
        // time to shutdown when testing tomcat plugin
        long startedAt = System.currentTimeMillis();
        while (true) {
            List<Thread> rogueThreads = Lists.newArrayList();
            for (Thread thread : currentThreads()) {
                if (thread != Thread.currentThread() && !preExistingThreads.contains(thread)
                        && isRogueThread(thread)) {
                    rogueThreads.add(thread);
                }
            }
            if (rogueThreads.isEmpty()) {
                // success
                return;
            } else if (System.currentTimeMillis() - startedAt > 5000) {
                throw new RogueThreadsException(rogueThreads);
            } else {
                // failure, wait a few milliseconds before trying again
                Thread.sleep(10);
            }
        }
    }

    // ensure the test shutdown all threads that it created
    public static void postShutdownCheck(Collection<Thread> preExistingThreads)
            throws InterruptedException {

        // give it 5 seconds to shutdown threads
        long startedAt = System.currentTimeMillis();
        while (true) {
            Collection<Thread> rogueThreads = Collections2.filter(currentThreads(),
                    Predicates.not(Predicates.in(preExistingThreads)));
            if (rogueThreads.isEmpty()) {
                // success
                return;
            } else if (System.currentTimeMillis() - startedAt > 5000) {
                throw new RogueThreadsException(rogueThreads);
            } else {
                // failure, wait a few milliseconds before trying again
                Thread.sleep(10);
            }
        }
    }

    public static File findInformantCoreJarFile() {
        File informantCoreJarFile = findInformantCoreJarFileFromClasspath();
        if (informantCoreJarFile != null) {
            return informantCoreJarFile;
        }
        informantCoreJarFile = findInformantCoreJarFileFromRelativePath();
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

    private static boolean isRogueThread(Thread thread) {
        if (!thread.isDaemon()) {
            return true;
        } else if (isShaded() && !thread.getName().startsWith("Informant-")) {
            return true;
        } else if (!isShaded()
                && !(thread.getName().startsWith("Informant-") || thread.getName().startsWith(
                        "InformantTest-"))
                && !thread.getName().startsWith("H2 File Lock Watchdog ")
                && !thread.getName().startsWith("H2 Log Writer ")) {
            return true;
        }
        return false;
    }

    private static boolean isShaded() {
        return org.h2.Driver.class.getName().startsWith("org.informantproject.shaded.");
    }

    // cover the standard case when running from maven
    @Nullable
    private static File findInformantCoreJarFileFromClasspath() {
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
    private static File findInformantCoreJarFileFromRelativePath() {
        String classesDir = Files.class.getProtectionDomain().getCodeSource().getLocation()
                .getFile();
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

    @SuppressWarnings("serial")
    public static class RogueThreadsException extends RuntimeException {
        private final Collection<Thread> rogueThreads;
        private RogueThreadsException(Collection<Thread> rogueThreads) {
            this.rogueThreads = rogueThreads;
        }
        @Override
        public String getMessage() {
            StringBuilder sb = new StringBuilder();
            for (Thread rogueThread : rogueThreads) {
                sb.append(threadToString(rogueThread));
                sb.append("\n");
            }
            return sb.toString();
        }
        private static String threadToString(Thread thread) {
            ToStringHelper toStringHelper = Objects.toStringHelper(thread)
                    .add("name", thread.getName())
                    .add("class", thread.getClass().getName())
                    .add("state", thread.getState());
            for (int i = 0; i < Math.min(30, thread.getStackTrace().length); i++) {
                toStringHelper.add("stackTrace." + i, thread.getStackTrace()[i].getClassName());
            }
            return toStringHelper.toString();
        }
    }

    private UnitTests() {}
}
