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
package io.informant.test;

import static org.fest.assertions.api.Assertions.assertThat;
import io.informant.core.util.DaemonExecutors;
import io.informant.testkit.AppUnderTest;
import io.informant.testkit.InformantContainer;
import io.informant.testkit.TraceMarker;

import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import checkers.nullness.quals.Nullable;

/**
 * @author Trask Stalnaker
 * @since 0.5
 */
// this is a test of DataSource's jvm shutdown hook, since prior to replacing H2's jvm shutdown
// hook, the H2 jdbc connection could get closed while there were still traces being written to it,
// and exceptions would get thrown/logged
public class DataSourceShutdownTest {

    private static final Logger logger = LoggerFactory.getLogger(DataSourceShutdownTest.class);

    @Test
    public void shouldShutdown() throws Exception {
        if (!InformantContainer.isExternalJvm()) {
            // this test is only relevant under javaagent
            // (tests are run under javaagent during mvn integration-test but not during mvn test)
            // not using org.junit.Assume which reports the test as ignored, since ignored tests
            // seem like something that needs to be revisited and 'un-ignored'
            return;
        }
        // given
        final InformantContainer container = InformantContainer.create(0, true);
        container.getInformant().setStoreThresholdMillis(0);
        // when
        ExecutorService executorService = Executors.newSingleThreadExecutor();
        executorService.submit(new Callable<Void>() {
            @Nullable
            public Void call() throws Exception {
                container.executeAppUnderTest(ForceShutdownWhileStoringTraces.class);
                return null;
            }
        });
        while (container.getInformant().getNumStoredTraceSnapshots()
                + container.getInformant().getNumPendingCompleteTraces() < 10) {
            Thread.sleep(1);
        }
        container.killExternalJvm();
        // then
        // check that no error messages were logged, problem is (1) the external jvm is terminated
        // so can't query it and (2) any error or warning messages due to database shutdown wouldn't
        // be stored in the database log_message table, so have to resort to screen scraping
        assertThat(container.getNumConsoleBytes()).isEqualTo(0);
        // cleanup
        executorService.shutdown();
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
