/*
 * Copyright 2026 the original author or authors.
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
package org.glowroot.central.storage.postgres;

import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import javax.sql.DataSource;

import org.glowroot.central.util.MoreExecutors2;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Periodic batched purge of {@code expires_at} rows for long-running Postgres Central.
 */
public final class PostgresPurgeService implements AutoCloseable {

    private static final Logger logger = LoggerFactory.getLogger(PostgresPurgeService.class);

    /** First run after a short delay so boot is not blocked; then hourly. */
    static final long INITIAL_DELAY_MINUTES = 5;
    static final long PERIOD_MINUTES = 60;

    private final DataSource dataSource;
    private final ScheduledExecutorService scheduler;
    private final int batchSize;

    public PostgresPurgeService(DataSource dataSource) {
        this(dataSource, PostgresPurge.DEFAULT_BATCH_SIZE);
    }

    PostgresPurgeService(DataSource dataSource, int batchSize) {
        this.dataSource = dataSource;
        this.batchSize = batchSize;
        scheduler = MoreExecutors2.newSingleThreadScheduledExecutor("Postgres-Purge");
        scheduler.scheduleWithFixedDelay(this::safePurge, INITIAL_DELAY_MINUTES, PERIOD_MINUTES,
                TimeUnit.MINUTES);
    }

    private void safePurge() {
        try {
            int deleted = PostgresPurge.deleteExpiredBatched(dataSource, batchSize);
            if (deleted > 0) {
                logger.info("purged {} expired postgres row(s)", deleted);
            } else {
                logger.debug("postgres purge found no expired rows");
            }
        } catch (Throwable t) {
            logger.error("postgres purge failed: {}", t.getMessage(), t);
        }
    }

    @Override
    public void close() {
        scheduler.shutdown();
        try {
            if (!scheduler.awaitTermination(10, TimeUnit.SECONDS)) {
                scheduler.shutdownNow();
            }
        } catch (InterruptedException e) {
            scheduler.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}
