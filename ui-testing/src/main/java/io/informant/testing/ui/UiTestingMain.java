/**
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

import io.informant.testkit.AppUnderTest;
import io.informant.testkit.CoarseProfilingConfig;
import io.informant.testkit.FineProfilingConfig;
import io.informant.testkit.GeneralConfig;
import io.informant.testkit.InformantContainer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * @author Trask Stalnaker
 * @since 0.5
 */
public class UiTestingMain {

    private static final int UI_PORT = 4000;
    // this is off by default because it is annoying to have these messages in the console
    private static final boolean TEST_LOG_MESSAGES = false;

    private static final Logger logger = LoggerFactory.getLogger(UiTestingMain.class);

    private UiTestingMain() {}

    public static void main(String... args) throws Exception {
        InformantContainer container = InformantContainer.create(UI_PORT, false);
        // set thresholds low so there will be lots of data to view
        GeneralConfig generalConfig = container.getInformant().getGeneralConfig();
        generalConfig.setStoreThresholdMillis(0);
        container.getInformant().updateGeneralConfig(generalConfig);
        CoarseProfilingConfig coarseProfilingConfig = container.getInformant()
                .getCoarseProfilingConfig();
        coarseProfilingConfig.setInitialDelayMillis(500);
        coarseProfilingConfig.setIntervalMillis(500);
        coarseProfilingConfig.setTotalSeconds(2);
        container.getInformant().updateCoarseProfilingConfig(coarseProfilingConfig);
        FineProfilingConfig fineProfilingConfig = container.getInformant().getFineProfilingConfig();
        fineProfilingConfig.setTracePercentage(50);
        fineProfilingConfig.setIntervalMillis(10);
        fineProfilingConfig.setTotalSeconds(1);
        container.getInformant().updateFineProfilingConfig(fineProfilingConfig);
        logger.info("view ui at http://localhost:" + UI_PORT);
        container.executeAppUnderTest(GenerateTraces.class);
    }

    public static class GenerateTraces implements AppUnderTest {
        private static final io.informant.api.Logger logger =
                io.informant.api.LoggerFactory.getLogger(GenerateTraces.class);
        public void executeApp() throws InterruptedException {
            int count = 0;
            Exception cause1 = new NullPointerException("Cause 1");
            Exception cause2 = new IllegalStateException("Cause 2", cause1);
            Exception cause3 = new IllegalArgumentException("Cause 3", cause2);
            while (true) {
                // one very short trace that will have an empty merged stack tree
                new NestableCall(1, 10, 100).execute();
                new NestableCall(new NestableCall(10, 50, 5000), 20, 50, 5000).execute();
                new NestableCall(new NestableCall(5000, 50, 5000), 100, 50, 5000).execute();
                new NestableCall(new NestableCall(5, 50, 5000), 5, 50, 5000).execute();
                new NestableCall(new NestableCall(10, 50, 5000), 10, 50, 5000).execute();
                new NestableCall(new NestableCall(20, 50, 5000), 5, 50, 5000).execute();
                if (TEST_LOG_MESSAGES) {
                    if (count++ % 2 == 0) {
                        logger.warn("everything is actually ok");
                    } else {
                        logger.warn("everything is actually ok", new IllegalStateException(
                                "just testing", cause3));
                    }
                }
                Thread.sleep(1000);
            }
        }
    }
}
