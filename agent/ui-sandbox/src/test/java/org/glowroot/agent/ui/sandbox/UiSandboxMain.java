/*
 * Copyright 2012-2018 the original author or authors.
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
package org.glowroot.agent.ui.sandbox;

import java.io.File;
import java.util.concurrent.Executors;

import com.google.common.base.Charsets;
import com.google.common.base.Stopwatch;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.io.Files;

import org.glowroot.agent.it.harness.AppUnderTest;
import org.glowroot.agent.it.harness.Container;
import org.glowroot.agent.it.harness.impl.JavaagentContainer;
import org.glowroot.agent.it.harness.impl.LocalContainer;

import static java.util.concurrent.TimeUnit.SECONDS;

public class UiSandboxMain {

    private static final boolean useJavaagent = Boolean.getBoolean("glowroot.sandbox.javaagent");
    private static final boolean useGlowrootCentral =
            Boolean.getBoolean("glowroot.sandbox.central");

    private UiSandboxMain() {}

    public static void main(String[] args) throws Exception {
        Container container;
        File testDir = new File("target");
        File configFile = new File(testDir, "config.json");
        if (!configFile.exists()) {
            Files.write(
                    "{\"transactions\":{\"profilingIntervalMillis\":100},"
                            + "\"ui\":{\"defaultTransactionType\":\"Sandbox\"}}",
                    configFile, Charsets.UTF_8);
        }
        if (useJavaagent && useGlowrootCentral) {
            container = new JavaagentContainer(testDir, false,
                    ImmutableList.of("-Dglowroot.agent.id=UI Sandbox",
                            "-Dglowroot.collector.address=localhost:8181"));
        } else if (useJavaagent) {
            container = new JavaagentContainer(testDir, true, ImmutableList.<String>of());
        } else if (useGlowrootCentral) {
            container = new LocalContainer(testDir, false,
                    ImmutableMap.of("glowroot.agent.id", "UI Sandbox",
                            "glowroot.collector.address", "localhost:8181"));
        } else {
            container = new LocalContainer(testDir, true, ImmutableMap.<String, String>of());
        }
        container.executeNoExpectedTrace(GenerateTraces.class);
    }

    public static class GenerateTraces implements AppUnderTest {
        @Override
        public void executeApp() throws Exception {
            startDeadlockingThreads();
            startDeadlockingThreads();
            while (true) {
                Stopwatch stopwatch = Stopwatch.createStarted();
                while (stopwatch.elapsed(SECONDS) < 300) {
                    // a very short trace that will have an empty profile
                    new NestableCall(1, 10, 100).execute();
                    // a trace that will have profile tree with only a single leaf
                    new NestableCall(1, 100, 100).execute();
                    new NestableCall(new NestableCall(10, 50, 5000), 20, 50, 5000).execute();
                    new NestableCall(new NestableCall(5, 50, 5000), 5, 50, 5000).execute();
                    new NestableCall(new NestableCall(10, 50, 5000), 10, 50, 5000).execute();
                    new NestableCall(new NestableCall(20, 50, 5000), 5, 50, 5000).execute();
                }
                new NestableCall(new NestableCall(5000, 50, 5000), 100, 50, 5000).execute();
                Thread.sleep(1000);
            }
        }
        private void startDeadlockingThreads() {
            final Object lock1 = new Object();
            final Object lock2 = new Object();
            Executors.newSingleThreadExecutor().execute(new Runnable() {
                @Override
                public void run() {
                    synchronized (lock1) {
                        try {
                            Thread.sleep(1000);
                        } catch (InterruptedException e) {
                            throw new RuntimeException(e);
                        }
                        synchronized (lock2) {
                            // should never gets here
                        }
                    }
                }
            });
            Executors.newSingleThreadExecutor().execute(new Runnable() {
                @Override
                public void run() {
                    synchronized (lock2) {
                        try {
                            Thread.sleep(1000);
                        } catch (InterruptedException e) {
                            throw new RuntimeException(e);
                        }
                        synchronized (lock1) {
                            // should never gets here
                        }
                    }
                }
            });
        }
    }
}
