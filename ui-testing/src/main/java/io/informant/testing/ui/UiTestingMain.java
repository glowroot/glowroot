/*
 * Copyright 2012-2013 the original author or authors.
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
package io.informant.testing.ui;

import java.io.File;
import java.io.IOException;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonGenerator;
import com.google.common.base.Charsets;
import com.google.common.base.Stopwatch;
import com.google.common.io.CharStreams;
import com.google.common.io.Files;

import io.informant.container.AppUnderTest;
import io.informant.container.Container;
import io.informant.container.config.CoarseProfilingConfig;
import io.informant.container.config.FineProfilingConfig;
import io.informant.container.config.GeneralConfig;
import io.informant.container.config.StorageConfig;
import io.informant.container.javaagent.JavaagentContainer;
import io.informant.container.local.LocalContainer;

import static java.util.concurrent.TimeUnit.SECONDS;

/**
 * @author Trask Stalnaker
 * @since 0.5
 */
public class UiTestingMain {

    private static final JsonFactory jsonFactory = new JsonFactory();

    // need to use javaagent when testing pointcuts.html, otherwise class/method auto completion
    // won't be available
    private static final boolean useJavaagent = false;
    private static final int INITIAL_UI_PORT = 4001;
    private static final boolean rollOverQuickly = false;

    static {
        System.setProperty("informant.internal.collector.aggregateInterval", "15");
    }

    private UiTestingMain() {}

    public static void main(String... args) throws Exception {
        Container container;
        File dataDir = new File("target");
        File configFile = new File(dataDir, "config.json");
        if (!configFile.exists()) {
            writeConfigJson(dataDir, INITIAL_UI_PORT);
        }
        if (useJavaagent) {
            container = new JavaagentContainer(dataDir, true, false, false);
        } else {
            container = new LocalContainer(dataDir, true, false);
        }
        // set thresholds low so there will be lots of data to view
        GeneralConfig generalConfig = container.getConfigService().getGeneralConfig();
        generalConfig.setStoreThresholdMillis(0);
        container.getConfigService().updateGeneralConfig(generalConfig);
        CoarseProfilingConfig coarseProfilingConfig = container.getConfigService()
                .getCoarseProfilingConfig();
        coarseProfilingConfig.setInitialDelayMillis(500);
        coarseProfilingConfig.setIntervalMillis(500);
        coarseProfilingConfig.setTotalSeconds(2);
        container.getConfigService().updateCoarseProfilingConfig(coarseProfilingConfig);
        FineProfilingConfig fineProfilingConfig = container.getConfigService()
                .getFineProfilingConfig();
        fineProfilingConfig.setTracePercentage(50);
        fineProfilingConfig.setIntervalMillis(10);
        fineProfilingConfig.setTotalSeconds(1);
        container.getConfigService().updateFineProfilingConfig(fineProfilingConfig);
        if (rollOverQuickly) {
            StorageConfig storageConfig = container.getConfigService().getStorageConfig();
            storageConfig.setRollingSizeMb(10);
            container.getConfigService().updateStorageConfig(storageConfig);
        }
        container.executeAppUnderTest(GenerateTraces.class);
    }

    private static void writeConfigJson(File dataDir, int uiPort) throws IOException {
        StringBuilder sb = new StringBuilder();
        JsonGenerator jg = jsonFactory.createGenerator(CharStreams.asWriter(sb));
        jg.writeStartObject();
        jg.writeObjectFieldStart("ui");
        jg.writeNumberField("port", uiPort);
        jg.writeEndObject();
        jg.writeEndObject();
        jg.close();
        Files.write(sb, new File(dataDir, "config.json"), Charsets.UTF_8);
    }

    public static class GenerateTraces implements AppUnderTest {
        public void executeApp() throws InterruptedException {
            while (true) {
                Stopwatch stopwatch = new Stopwatch().start();
                while (stopwatch.elapsed(SECONDS) < 30) {
                    // a very short trace that will have an empty merged stack tree
                    new NestableCall(1, 10, 100).execute();
                    // a trace that will have merged stack tree with only a single leaf
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
