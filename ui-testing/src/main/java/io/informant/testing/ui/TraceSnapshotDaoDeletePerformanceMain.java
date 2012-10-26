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
package io.informant.testing.ui;

import io.informant.testkit.AppUnderTest;
import io.informant.testkit.Config.CoarseProfilingConfig;
import io.informant.testkit.InformantContainer;

import java.io.File;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.base.Stopwatch;

/**
 * @author Trask Stalnaker
 * @since 0.5
 */
public final class TraceSnapshotDaoDeletePerformanceMain {

    private static final Logger logger = LoggerFactory
            .getLogger(TraceSnapshotDaoDeletePerformanceMain.class);

    public static void main(String... args) throws Exception {
        InformantContainer container = InformantContainer.create();
        // set thresholds low so there will be lots of data to view
        container.getInformant().setStoreThresholdMillis(0);
        CoarseProfilingConfig profilingConfig = container.getInformant().getCoarseProfilingConfig();
        profilingConfig.setInitialDelayMillis(100);
        profilingConfig.setIntervalMillis(10);
        container.getInformant().updateCoarseProfilingConfig(profilingConfig);
        container.executeAppUnderTest(GenerateTraces.class);
        int pendingWrites = container.getInformant().getNumPendingTraceWrites();
        while (pendingWrites > 0) {
            logger.info("pending trace writes: {}", pendingWrites);
            Thread.sleep(1000);
            pendingWrites = container.getInformant().getNumPendingTraceWrites();
        }
        File dbFile = new File("informant.h2.db");
        long dbSize = dbFile.length();
        logger.info("informant.h2.db: {} bytes", dbSize);
        Stopwatch stopwatch = new Stopwatch().start();
        container.getInformant().cleanUpAfterEachTest();
        logger.info("all traces deleted in: {} millis", stopwatch.elapsedMillis());
        logger.info("informant.h2.db: {} bytes", dbFile.length());
        container.close();
        logger.info("informant.h2.db: {} bytes", dbFile.length());
    }

    private static class GenerateTraces implements AppUnderTest {
        public void executeApp() throws InterruptedException {
            File rollingFile = new File("informant.rolling.db");
            while (rollingFile.length() < 100 * 1024 * 1024) {
                new NestableCall(new NestableCall(10, 2, 5000), 20, 2, 5000).execute();
                logger.info("rolling file: {} mb", rollingFile.length() / (1024 * 1024));
            }
        }
    }

    private TraceSnapshotDaoDeletePerformanceMain() {}
}
