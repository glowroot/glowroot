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
package org.informantproject.testing.ui;

import java.io.File;

import org.informantproject.testkit.AppUnderTest;
import org.informantproject.testkit.Configuration.CoreConfiguration;
import org.informantproject.testkit.InformantContainer;

import com.google.common.base.Stopwatch;

/**
 * @author Trask Stalnaker
 * @since 0.5
 */
public class TraceDaoDeletePerformanceMain {

    public static void main(String... args) throws Exception {
        InformantContainer container = InformantContainer.newInstance();
        // set thresholds low so there will be lots of data to view
        CoreConfiguration coreConfiguration = container.getInformant().getCoreConfiguration();
        coreConfiguration.setThresholdMillis(0);
        coreConfiguration.setStackTraceInitialDelayMillis(100);
        coreConfiguration.setStackTracePeriodMillis(10);
        container.getInformant().updateCoreConfiguration(coreConfiguration);
        container.executeAppUnderTest(GenerateTraces.class);
        int pendingWrites = container.getInformant().getNumPendingTraceWrites();
        while (pendingWrites > 0) {
            System.out.println("pending trace writes: " + pendingWrites);
            Thread.sleep(1000);
            pendingWrites = container.getInformant().getNumPendingTraceWrites();
        }
        File dbFile = new File("informant.h2.db");
        long dbSize = dbFile.length();
        System.out.format("informant.h2.db: %,d bytes%n", dbSize);
        Stopwatch stopwatch = new Stopwatch().start();
        container.getInformant().deleteAllTraces();
        System.out.format("all traces deleted in: %,d millis%n", stopwatch.elapsedMillis());
        System.out.format("informant.h2.db: %,d bytes%n", dbFile.length());
        container.close();
        System.out.format("informant.h2.db: %,d bytes%n", dbFile.length());
    }

    public static class GenerateTraces implements AppUnderTest {
        public void executeApp() throws InterruptedException {
            File rollingFile = new File("informant.rolling.db");
            while (rollingFile.length() < 100 * 1024 * 1024) {
                new NestableCall(new NestableCall(10, 2, 5000), 20, 2, 5000).execute();
                System.out.println(rollingFile.length() / (1024 * 1024));
            }
        }
    }
}
