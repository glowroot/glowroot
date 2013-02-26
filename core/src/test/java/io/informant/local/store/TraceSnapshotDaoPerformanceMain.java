/**
 * Copyright 2011-2013 the original author or authors.
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
package io.informant.local.store;

import io.informant.util.Clock;
import io.informant.util.Static;

import java.io.File;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.base.Stopwatch;

/**
 * @author Trask Stalnaker
 * @since 0.5
 */
@Static
public class TraceSnapshotDaoPerformanceMain {

    private static final Logger logger = LoggerFactory
            .getLogger(TraceSnapshotDaoPerformanceMain.class);

    private TraceSnapshotDaoPerformanceMain() {}

    public static void main(String... args) throws Exception {
        TraceSnapshotTestData snapshotTestData = new TraceSnapshotTestData();
        DataSource dataSource = new DataSource();
        RollingFile rollingFile = new RollingFile(new File("informant.rolling.db"), 1000000);
        TraceSnapshotDao snapshotDao = new TraceSnapshotDao(dataSource, rollingFile,
                Clock.systemClock());

        Stopwatch stopwatch = new Stopwatch().start();
        for (int i = 0; i < 1000; i++) {
            snapshotDao.storeSnapshot(snapshotTestData.createSnapshot());
        }
        logger.info("elapsed time: {}", stopwatch.elapsedMillis());
        logger.info("num traces: {}", snapshotDao.count());
    }
}
