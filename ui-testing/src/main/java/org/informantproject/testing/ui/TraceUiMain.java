/**
 * Copyright 2012 the original author or authors.
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
package org.informantproject.testing.ui;

import org.informantproject.testkit.AppUnderTest;
import org.informantproject.testkit.Configuration.CoreProperties;
import org.informantproject.testkit.InformantContainer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * @author Trask Stalnaker
 * @since 0.5
 */
public class TraceUiMain {

    private static final int UI_PORT = 4000;

    private static final Logger logger = LoggerFactory.getLogger(TraceUiMain.class);

    public static void main(String... args) throws Exception {
        InformantContainer container = InformantContainer.create(UI_PORT);
        // set thresholds low so there will be lots of data to view
        CoreProperties coreProperties = container.getInformant().getCoreProperties();
        coreProperties.setThresholdMillis(0);
        coreProperties.setStackTraceInitialDelayMillis(100);
        coreProperties.setStackTracePeriodMillis(10);
        coreProperties.setSpanStackTraceThresholdMillis(100);
        container.getInformant().updateCoreProperties(coreProperties);
        logger.info("view trace ui at localhost:" + UI_PORT + "/traces.html");
        container.executeAppUnderTest(GenerateTraces.class);
    }

    public static class GenerateTraces implements AppUnderTest {
        public void executeApp() throws InterruptedException {
            while (true) {
                // one very short trace that will have an empty merged stack tree
                new NestableCall(1, 10, 100).execute();
                new NestableCall(new NestableCall(10, 50, 5000), 20, 50, 5000).execute();
                new NestableCall(new NestableCall(50, 50, 5000), 100, 50, 5000).execute();
                new NestableCall(new NestableCall(5, 50, 5000), 5, 50, 5000).execute();
                new NestableCall(new NestableCall(10, 50, 5000), 10, 50, 5000).execute();
                new NestableCall(new NestableCall(20, 50, 5000), 5, 50, 5000).execute();
                Thread.sleep(10000);
            }
        }
    }
}
