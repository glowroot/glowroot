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

import io.informant.container.AppUnderTest;
import io.informant.container.Container;
import io.informant.container.config.CoarseProfilingConfig;
import io.informant.container.config.FineProfilingConfig;
import io.informant.container.config.GeneralConfig;
import io.informant.container.local.LocalContainer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * @author Trask Stalnaker
 * @since 0.5
 */
public class UiTestingMain {

    private static final int UI_PORT = 4000;

    private static final Logger logger = LoggerFactory.getLogger(UiTestingMain.class);

    private UiTestingMain() {}

    public static void main(String... args) throws Exception {
        Container container = LocalContainer.createWithFileDb(UI_PORT);
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
        logger.info("view ui at http://localhost:" + UI_PORT);
        container.executeAppUnderTest(GenerateTraces.class);
    }

    public static class GenerateTraces implements AppUnderTest {
        public void executeApp() throws InterruptedException {
            while (true) {
                // one very short trace that will have an empty merged stack tree
                new NestableCall(1, 10, 100).execute();
                new NestableCall(new NestableCall(10, 50, 5000), 20, 50, 5000).execute();
                new NestableCall(new NestableCall(5000, 50, 5000), 100, 50, 5000).execute();
                new NestableCall(new NestableCall(5, 50, 5000), 5, 50, 5000).execute();
                new NestableCall(new NestableCall(10, 50, 5000), 10, 50, 5000).execute();
                new NestableCall(new NestableCall(20, 50, 5000), 5, 50, 5000).execute();
                Thread.sleep(1000);
            }
        }
    }
}
