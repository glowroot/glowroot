/**
 * Copyright 2016-2019 the original author or authors.
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
package org.glowroot.testing;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import com.google.common.base.Joiner;
import com.google.common.collect.Lists;
import com.google.common.io.Files;
import org.checkerframework.checker.nullness.qual.Nullable;

import static com.google.common.base.Charsets.UTF_8;
import static com.google.common.base.Preconditions.checkNotNull;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.glowroot.testing.JavaVersion.JAVA7;

class Util {

    private static final String BASE_DIR = System.getProperty("base.dir", ".");
    private static final String MVN = System.getProperty("mvn", "mvn");

    private static PrintWriter report;

    static {
        try {
            report = new PrintWriter(Files.newWriter(new File("report.txt"), UTF_8), true);
        } catch (FileNotFoundException e) {
            throw new RuntimeException(e);
        }
    }

    static void updateLibVersion(String modulePath, String property, String version)
            throws IOException {
        File pomFile = new File(BASE_DIR + "/" + modulePath + "/pom.xml");
        String pom = Files.toString(pomFile, UTF_8);
        pom = pom.replaceAll("<" + property + ">.*",
                "<" + property + ">" + version + "</" + property + ">");
        Files.write(pom, pomFile, UTF_8);
        System.out.println(property + " : " + version);
        report.println(property + " : " + version);
    }

    static void log(String message) {
        System.out.println(message);
        report.println(message);
    }

    static void runTests(String modulePath, JavaVersion... javaVersions) throws Exception {
        runInternal(modulePath, null, new String[] {}, javaVersions);
    }

    static void runTests(String modulePath, String profile, JavaVersion... javaVersions)
            throws Exception {
        runInternal(modulePath, null, new String[] {profile}, javaVersions);
    }

    static void runTests(String modulePath, String[] profiles, JavaVersion... javaVersions)
            throws Exception {
        runInternal(modulePath, null, profiles, javaVersions);
    }

    static void runTest(String modulePath, String test, JavaVersion... javaVersions)
            throws Exception {
        runInternal(modulePath, test, new String[] {}, javaVersions);
    }

    static void runTest(String modulePath, String test, String profile, JavaVersion... javaVersions)
            throws Exception {
        runInternal(modulePath, test, new String[] {profile}, javaVersions);
    }

    private static void runInternal(String modulePath, @Nullable String test, String[] profiles,
            JavaVersion... javaVersions) throws Exception {
        for (JavaVersion javaVersion : javaVersions) {
            runTest(modulePath, test, javaVersion, profiles);
        }
    }

    private static void runTest(String modulePath, @Nullable String test, JavaVersion javaVersion,
            String... profiles) throws Exception {
        System.out.println(javaVersion);
        report.println(javaVersion);
        List<String> command = Lists.newArrayList();
        command.add(MVN);
        command.add("-P");
        // don't recompile plugin classes since in some cases they won't recompile with older
        // versions of libraries
        // need to recompile tests in some cases to align bytecode with test version
        // also need to recompile tests in some cases where default test compilation target is 1.7
        // but there is alternate 1.6 profile triggered above by -Dglowroot.test.java6
        StringBuilder sb = new StringBuilder("compile-test-classes-only,fail-failsafe-if-no-tests");
        List<String> propertyArgs = Lists.newArrayList();
        for (String profile : profiles) {
            if (profile.startsWith("-D")) {
                propertyArgs.add(profile);
            } else {
                sb.append(",");
                sb.append(profile);
            }
        }
        command.add(sb.toString());
        for (String propertyArg : propertyArgs) {
            command.add(propertyArg);
        }
        command.add("-pl");
        command.add(modulePath);
        command.add("-Djvm=" + javaVersion.getJavaHome() + File.separator + "bin" + File.separator
                + "java");
        if (javaVersion == JavaVersion.JAVA6 || javaVersion == JavaVersion.JAVA7) {
            // maven central no longer supports TLSv1.1 and below
            command.add("-Dhttps.protocols=TLSv1.2");
        }
        if (javaVersion == JavaVersion.JAVA6) {
            command.add("-Dglowroot.test.java6");
        }
        // cassandra plugin tests need java7.home when running under java 6 in order to run
        // cassandra itself
        command.add("-Djava7.home=" + JAVA7.getJavaHome());
        if (test != null) {
            command.add("-Dit.test=" + test);
        }
        command.add("-Dglowroot.it.harness=javaagent");
        command.add("-Dglowroot.test.shaded");
        command.add("-Denforcer.skip");
        command.add("-Danimal.sniffer.skip");
        String sourceOfRandomness = System.getProperty("java.security.egd");
        if (sourceOfRandomness != null) {
            command.add("-Djava.security.egd=" + sourceOfRandomness);
        }
        command.add("clean"); // using profile above "clean-test-classes-only"
        command.add("verify");
        ProcessBuilder processBuilder = new ProcessBuilder(command);
        processBuilder.redirectErrorStream(true);
        processBuilder.directory(new File(BASE_DIR));
        if (javaVersion == JavaVersion.JAVA6) {
            // maven requires Java 7+
            processBuilder.environment().put("JAVA_HOME", JavaVersion.JAVA7.getJavaHome());
        } else {
            processBuilder.environment().put("JAVA_HOME", javaVersion.getJavaHome());
        }
        System.out.println("\n\n" + Joiner.on(' ').join(command) + "\n\n");
        Process process = processBuilder.start();
        InputStream in = checkNotNull(process.getInputStream());
        ConsoleOutputPipe consoleOutputPipe = new ConsoleOutputPipe(in, System.out);
        ExecutorService consolePipeExecutorService = Executors.newSingleThreadExecutor();
        Future<?> future = consolePipeExecutorService.submit(consoleOutputPipe);
        int exit = process.waitFor();
        future.get();
        consolePipeExecutorService.shutdown();
        consolePipeExecutorService.awaitTermination(10, SECONDS);
        if (exit != 0) {
            report.println("FAIL");
        }
        System.out.println("\n\n");
    }

    private static class ConsoleOutputPipe implements Runnable {

        private final InputStream in;
        private final OutputStream out;

        private ConsoleOutputPipe(InputStream in, OutputStream out) {
            this.in = in;
            this.out = out;
        }

        @Override
        public void run() {
            byte[] buffer = new byte[100];
            try {
                while (true) {
                    int n = in.read(buffer);
                    if (n == -1) {
                        break;
                    }
                    out.write(buffer, 0, n);
                }
            } catch (IOException e) {
            }
        }
    }
}
