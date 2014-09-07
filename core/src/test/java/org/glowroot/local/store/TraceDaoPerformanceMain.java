/*
 * Copyright 2011-2014 the original author or authors.
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
package org.glowroot.local.store;

import java.io.File;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

import com.google.common.base.Stopwatch;
import com.google.common.io.CharSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.glowroot.collector.Trace;
import org.glowroot.common.Ticker;
import org.glowroot.markers.Static;

import static java.util.concurrent.TimeUnit.MILLISECONDS;

/**
 * @author Trask Stalnaker
 * @since 0.5
 */
@Static
public class TraceDaoPerformanceMain {

    private static final Logger logger = LoggerFactory
            .getLogger(TraceDaoPerformanceMain.class);

    private TraceDaoPerformanceMain() {}

    public static void main(String... args) throws Exception {
        DataSource dataSource = new DataSource();
        ScheduledExecutorService scheduledExecutor = Executors.newSingleThreadScheduledExecutor();
        CappedDatabase cappedDatabase = new CappedDatabase(new File("glowroot.capped.db"), 1000000,
                scheduledExecutor, Ticker.systemTicker());
        TraceDao traceDao = new TraceDao(dataSource, cappedDatabase);

        Stopwatch stopwatch = Stopwatch.createStarted();
        for (int i = 0; i < 1000; i++) {
            Trace trace = TraceTestData.createTrace();
            CharSource entries = TraceTestData.createEntries();
            traceDao.store(trace, entries, null, null);
        }
        logger.info("elapsed time: {}", stopwatch.elapsed(MILLISECONDS));
        logger.info("num traces: {}", traceDao.count());
    }
}
