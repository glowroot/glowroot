/**
 * Copyright 2016 the original author or authors.
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

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import javax.annotation.Nullable;

import com.google.common.base.Charsets;
import com.google.common.base.Joiner;
import com.google.common.collect.Lists;
import com.google.common.io.Files;

import static com.google.common.base.Preconditions.checkNotNull;
import static java.util.concurrent.TimeUnit.SECONDS;

class Util {

    private static final String BASE_DIR = System.getProperty("base.dir", ".");
    private static final String MVN = System.getProperty("mvn", "mvn");

    private static PrintWriter report;

    static {
        try {
            report = new PrintWriter(
                    new BufferedWriter(new OutputStreamWriter(new FileOutputStream("report.txt"))),
                    true);
        } catch (FileNotFoundException e) {
            throw new RuntimeException(e);
        }
    }

    static void updateLibVersion(String modulePath, String property, String version)
            throws IOException {
        File pomFile = new File(BASE_DIR + "/" + modulePath + "/pom.xml");
        String pom = Files.toString(pomFile, Charsets.UTF_8);
        pom = pom.replaceAll("<" + property + ">.*",
                "<" + property + ">" + version + "</" + property + ">");
        Files.write(pom, pomFile, Charsets.UTF_8);
        System.out.println(property + " : " + version);
        report.println(property + " : " + version);
    }

    static void runTests(String modulePath, String... profiles) throws Exception {
        runTest(modulePath, null, profiles);
    }

    static void runTest(String modulePath, @Nullable String test, String... profiles)
            throws Exception {
        List<String> command = Lists.newArrayList();
        command.add(MVN);
        if (profiles.length > 0) {
            command.add("-P");
            command.add(Joiner.on(',').join(profiles));
        }
        command.add("-pl");
        command.add(modulePath);
        if (test != null) {
            command.add("-Dit.test=" + test);
        }
        command.add("-Dglowroot.it.harness=javaagent");
        command.add("-Denforcer.skip");
        String sourceOfRandomness = System.getProperty("java.security.egd");
        if (sourceOfRandomness != null) {
            command.add("-Djava.security.egd=" + sourceOfRandomness);
        }
        command.add("clean");
        command.add("verify");
        ProcessBuilder processBuilder = new ProcessBuilder(command);
        processBuilder.redirectErrorStream(true);
        processBuilder.directory(new File(BASE_DIR));
        processBuilder.environment().put("JAVA_HOME", System.getProperty("java.home"));
        System.out.println("\n\n" + Joiner.on(' ').join(command) + "\n\n");
        Process process = processBuilder.start();
        InputStream in = checkNotNull(process.getInputStream());
        ConsoleOutputPipe consoleOutputPipe = new ConsoleOutputPipe(in, System.out);
        ExecutorService consolePipeExecutorService = Executors.newSingleThreadExecutor();
        consolePipeExecutorService.submit(consoleOutputPipe);
        int exit = process.waitFor();
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
