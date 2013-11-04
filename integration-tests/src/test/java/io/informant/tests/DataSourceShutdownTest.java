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

import io.informant.container.AppUnderTest;
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
        // given
        // this test is only relevant in external jvm so that the process can be killed and the jvm
        // shutdown hook can be tested
        final JavaagentContainer container = new JavaagentContainer(null, true, false, true);
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
        boolean startedWritingToDb = false;
        while (stopwatch.elapsed(SECONDS) < 5) {
            if (container.getTraceService().getNumStoredSnapshots() > 0) {
                startedWritingToDb = true;
                break;
            }
            Thread.sleep(1);
        }
        container.kill();
        // then
        assertThat(startedWritingToDb).isTrue();
        // check that no error messages were logged during shutdown
        // the problem is that the external jvm is terminated so it can't be queried, so have to
        // resort to screen scraping
        assertThat(container.getUnexpectedConsoleLines()).isEmpty();
        // cleanup
        executorService.shutdown();
    }

    public static class ForceShutdownWhileStoringTraces implements AppUnderTest, TraceMarker {
        public void executeApp() throws InterruptedException {
            ThreadFactory daemonThreadFactory = new ThreadFactoryBuilder().setDaemon(true).build();
            Executors.newSingleThreadExecutor(daemonThreadFactory).execute(new Runnable() {
                public void run() {
                    // generate traces during the shutdown process to test there are no errors
                    // caused by trying to write a trace to the database during/after shutdown
                    //
                    // only generate 100 to ensure backlog is not hit so that warning message does
                    // not occur (see TraceCollectorImpl), as this would fail test since screen
                    // scraping is used (see above)
                    // this seems to be enough, as even just 10 generally causes failure if
                    // DataSource.ShutdownHookThread is not created and db_close_on_exit=false is
                    // removed in order to put back the H2 jvm shutdown hook
                    for (int i = 0; i < 100; i++) {
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
