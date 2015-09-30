/*
 * Copyright 2011-2015 the original author or authors.
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
package org.glowroot.sandbox.ui;

import java.io.File;

import com.google.common.base.Stopwatch;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.glowroot.agent.harness.AppUnderTest;
import org.glowroot.agent.harness.Container;
import org.glowroot.agent.harness.Containers;

import static java.util.concurrent.TimeUnit.MILLISECONDS;

public class TraceDaoDeletePerformanceMain {

    private static final Logger logger =
            LoggerFactory.getLogger(TraceDaoDeletePerformanceMain.class);

    private TraceDaoDeletePerformanceMain() {}

    public static void main(String... args) throws Exception {
        Container container = Containers.createWithFileDb(new File("target"));
        container.executeAppUnderTest(GenerateTraces.class);
        int pendingWrites = container.getAdminService().getNumPendingCompleteTransactions();
        while (pendingWrites > 0) {
            logger.info("pending trace writes: {}", pendingWrites);
            Thread.sleep(1000);
            pendingWrites = container.getAdminService().getNumPendingCompleteTransactions();
        }
        File dbFile = new File("target/data/data.h2.db");
        long dbSize = dbFile.length();
        logger.info("data.h2.db: {} bytes", dbSize);
        Stopwatch stopwatch = Stopwatch.createStarted();
        container.getAdminService().deleteAllData();
        logger.info("all traces deleted in: {} millis", stopwatch.elapsed(MILLISECONDS));
        logger.info("data.h2.db: {} bytes", dbFile.length());
        container.close();
        logger.info("data.h2.db: {} bytes", dbFile.length());
    }

    public static class GenerateTraces implements AppUnderTest {
        @Override
        public void executeApp() throws InterruptedException {
            File dbFile = new File("target/data/data.h2.db");
            long lastSizeMb = 0;
            while (dbFile.length() < 100 * 1024 * 1024) {
                new NestableCall(new NestableCall(10, 2, 5000), 20, 2, 5000).execute();
                long sizeMb = dbFile.length() / (1024 * 1024);
                if (sizeMb > lastSizeMb) {
                    logger.info("data.h2.db: {} mb", sizeMb);
                    lastSizeMb = sizeMb;
                }
            }
        }
    }
}
