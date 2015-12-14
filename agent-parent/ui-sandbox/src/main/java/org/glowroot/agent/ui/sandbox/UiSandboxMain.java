/*
 * Copyright 2012-2015 the original author or authors.
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

    private static final boolean useJavaagent = false;
    private static final boolean useCentral = true;
    private static final boolean useReverseProxy = false;

    static {
        if (useReverseProxy) {
            System.setProperty("glowroot.ui.base", "http://localhost:9000/xyzzy/");
        }
    }

    private UiSandboxMain() {}

    public static void main(String... args) throws Exception {
        Container container;
        File baseDir = new File("target");
        File configFile = new File(baseDir, "config.json");
        if (!configFile.exists()) {
            Files.write("{\"transactions\":{\"profilingIntervalMillis\":100},"
                    + "\"ui\":{\"defaultDisplayedTransactionType\":\"Sandbox\"}}", configFile,
                    Charsets.UTF_8);
        }
        if (useJavaagent) {
            container =
                    new JavaagentContainer(baseDir, false, true, false, ImmutableList.<String>of());
        } else if (useCentral) {
            container = new LocalContainer(baseDir, false, false,
                    ImmutableMap.of("glowroot.server.id", "UI Sandbox",
                            "glowroot.collector.host", "localhost",
                            "glowroot.collector.port", "8181"));
        } else {
            container = new LocalContainer(baseDir, false, true, ImmutableMap.<String, String>of());
        }
        container.executeNoExpectedTrace(GenerateTraces.class);
    }

    public static class GenerateTraces implements AppUnderTest {
        @Override
        public void executeApp() throws InterruptedException {
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
    }
}
