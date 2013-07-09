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
package io.informant.tests;

import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;

import checkers.nullness.quals.Nullable;
import com.google.common.base.Stopwatch;
import com.google.common.util.concurrent.ThreadFactoryBuilder;
import org.junit.Test;

import io.informant.Containers;
import io.informant.container.AppUnderTest;
import io.informant.container.Container;
import io.informant.container.TraceMarker;
import io.informant.container.javaagent.JavaagentContainer;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.fest.assertions.api.Assertions.assertThat;

/**
 * @author Trask Stalnaker
 * @since 0.5
 */
// this is a test of DataSource's jvm shutdown hook, since prior to replacing H2's jvm shutdown
// hook, the H2 jdbc connection could get closed while there were still traces being written to it,
// and exceptions would get thrown/logged
public class DataSourceShutdownTest {

    @Test
    public void shouldShutdown() throws Exception {
        if (!Containers.isJavaagent()) {
            // this test is only relevant under javaagent
            // (tests are run under javaagent during mvn integration-test but not during mvn test)
            // not using org.junit.Assume which reports the test as ignored, since ignored tests
            // seem like something that needs to be revisited and 'un-ignored'
            return;
        }
        // given
        final JavaagentContainer container = JavaagentContainer.createWithFileDb();
        container.getConfigService().setStoreThresholdMillis(0);
        // when
        ExecutorService executorService = Executors.newSingleThreadExecutor();
        executorService.submit(new Callable<Void>() {
            @Nullable
            public Void call() throws Exception {
                container.executeAppUnderTest(ForceShutdownWhileStoringTraces.class);
                return null;
            }
        });
        Stopwatch stopwatch = new Stopwatch().start();
        boolean foundEnoughTraces = false;
        while (stopwatch.elapsed(SECONDS) < 5) {
            if (getNumCompletedTraces(container) > 10) {
                foundEnoughTraces = true;
                break;
            }
            Thread.sleep(1);
        }
        container.kill();
        // then
        assertThat(foundEnoughTraces).isTrue();
        // check that no error messages were logged, problem is (1) the external jvm is terminated
        // so can't query it and (2) any error or warning messages due to database shutdown wouldn't
        // be stored in the database log_message table, so have to resort to screen scraping
        assertThat(container.getNumConsoleBytes()).isEqualTo(0);

        // 17:00:03.898 [pool-4-thread-1] WARN i.i.collector.TraceCollectorImpl - not storing a
        // trace because of an excessive backlog of 100 traces already waiting to be stored (this
        // warning will appear at most once a minute, there were 0 additional traces not stored
        // since the last warning)

        // cleanup
        executorService.shutdown();
    }

    private long getNumCompletedTraces(Container container) throws Exception {
        return container.getTraceService().getNumStoredSnapshots()
                + container.getTraceService().getNumPendingCompleteTraces();
    }

    public static class ForceShutdownWhileStoringTraces implements AppUnderTest, TraceMarker {
        public void executeApp() throws InterruptedException {
            ThreadFactory daemonThreadFactory = new ThreadFactoryBuilder().setDaemon(true).build();
            Executors.newSingleThreadExecutor(daemonThreadFactory).execute(new Runnable() {
                public void run() {
                    // generate traces during the shutdown process to test there are no
                    // error caused
                    // by trying to write a trace to the database during/after shutdown
                    while (true) {
                        try {
                            traceMarker();
                        } catch (InterruptedException e) {
                        }
                    }
                }
            });
        }
        public void traceMarker() throws InterruptedException {
            Thread.sleep(1);
        }
    }
}
