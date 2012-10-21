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
package io.informant.test;

import io.informant.core.util.DaemonExecutors;
import io.informant.testkit.AppUnderTest;
import io.informant.testkit.InformantContainer;
import io.informant.testkit.TraceMarker;

import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * @author Trask Stalnaker
 * @since 0.5
 */
public class DataSourceShutdownTest {

    private static final Logger logger = LoggerFactory.getLogger(DataSourceShutdownTest.class);

    @Test
    public void shouldShutdown() throws Exception {
        if (!Boolean.valueOf(System.getProperty("externalJvmAppContainer"))) {
            // this test is only relevant under javaagent
            // (tests are run under javaagent during mvn integration-test but not during mvn test)
            // not using org.junit.Assume which reports the test as ignored, since ignored tests
            // seem like something that needs to be revisited and 'un-ignored'
            return;
        }
        // given
        InformantContainer container = InformantContainer.create(0, false);
        container.getInformant().setPersistenceThresholdMillis(0);
        // when
        container.executeAppUnderTest(ForceShutdownWhileStoringTraces.class);
        container.killExternalJvm();
        // then
        // TODO check that no error messages were logged
        // problem is (1) the external jvm is terminated so can't query it and (2) any error or
        // warning messages due to database shutdown wouldn't be stored in the database log_message
        // table, so would have to find another way to check if any error or warning messages were
        // logged
    }

    public static class ForceShutdownWhileStoringTraces implements AppUnderTest, TraceMarker {

        public void executeApp() throws InterruptedException {
            DaemonExecutors.newSingleThreadExecutor("GenerateTraces").execute(new Runnable() {
                public void run() {
                    // generate traces during the shutdown process to test there are no error caused
                    // by trying to write a trace to the database during/after shutdown
                    while (true) {
                        traceMarker();
                    }
                }
            });
            // System.exit() in a thread, so executeApp() can return normally
            DaemonExecutors.newSingleThreadExecutor("Shutdown").execute(new Runnable() {
                public void run() {
                    System.exit(0);
                }
            });
        }

        public void traceMarker() {
            try {
                Thread.sleep(1);
            } catch (InterruptedException e) {
                logger.warn(e.getMessage(), e);
            }
        }
    }
}
