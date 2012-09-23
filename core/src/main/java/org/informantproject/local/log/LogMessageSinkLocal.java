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
package org.informantproject.local.log;

import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import javax.annotation.Nullable;

import org.informantproject.api.CapturedException;
import org.informantproject.api.Logger;
import org.informantproject.api.LoggerFactory;
import org.informantproject.core.log.Level;
import org.informantproject.core.log.LogMessageSink;
import org.informantproject.core.util.Clock;
import org.informantproject.core.util.DaemonExecutors;
import org.informantproject.local.trace.TraceSnapshotService;

import com.google.common.base.Ticker;
import com.google.inject.Inject;
import com.google.inject.Singleton;

/**
 * Implementation of LogMessageSink for local storage in embedded H2 database. Some day there may be
 * another implementation for remote storage (e.g. central monitoring system).
 * 
 * @author Trask Stalnaker
 * @since 0.5
 */
@Singleton
public class LogMessageSinkLocal implements LogMessageSink {

    private static final Logger logger = LoggerFactory.getLogger(LogMessageSinkLocal.class);

    private static final int PENDING_LIMIT = 100;

    private final ExecutorService executorService = DaemonExecutors
            .newSingleThreadExecutor("Informant-LogMessageSink");

    private final LogMessageDao logMessageDao;
    private final Clock clock;
    private final Ticker ticker;
    private final AtomicInteger pendingCount = new AtomicInteger();

    private volatile long lastWarningTick;
    private volatile int countSinceLastWarning;

    private final ThreadLocal<Boolean> inStoreLogMessage = new ThreadLocal<Boolean>() {
        @Override
        protected Boolean initialValue() {
            return false;
        }
    };

    @Inject
    LogMessageSinkLocal(LogMessageDao logMessageDao, Clock clock, Ticker ticker) {
        this.logMessageDao = logMessageDao;
        this.clock = clock;
        this.ticker = ticker;
        lastWarningTick = ticker.read() - TimeUnit.SECONDS.toNanos(60);
    }

    public void onLogMessage(Level level, String message, @Nullable Throwable t) {
        if (inStoreLogMessage.get()) {
            // some type of logging occurred inside LogMessageDao.storeLogMessage() which has the
            // potential for going infinite loop, so better to just return, message has already been
            // sent to slf4j logger so it is not lost
            return;
        }
        if (pendingCount.incrementAndGet() > PENDING_LIMIT) {
            logPendingLimitWarning();
        }
        logMessageAsync(level, message, t);
    }

    // synchronized to prevent two threads from logging the warning at the same time (one should
    // log it and the other should increment the count for the next log)
    private synchronized void logPendingLimitWarning() {
        long tick = ticker.read();
        if (TimeUnit.NANOSECONDS.toSeconds(tick - lastWarningTick) >= 60) {
            inStoreLogMessage.set(true);
            try {
                String message = "not storing a log message in the local h2 database because of an"
                        + " excessive backlog of " + PENDING_LIMIT + " log messages already"
                        + " waiting to be stored (this warning will appear at most once a minute,"
                        + " there were " + countSinceLastWarning + " additional log messages not"
                        + " stored in the local h2 database since the last warning)";
                // inStoreLogMessage is true, so that onLogMessage() which is called from
                // logger.warn(), will short-circuit not execute anything
                logger.warn(message);
                // but it is still good to get this warning in the persisted log
                logMessageAsync(Level.WARN, message, null);
            } finally {
                inStoreLogMessage.set(false);
            }
            lastWarningTick = tick;
            countSinceLastWarning = 0;
        } else {
            countSinceLastWarning++;
        }
    }
    private void logMessageAsync(final Level level, final String message, final Throwable t) {
        executorService.execute(new Runnable() {
            public void run() {
                inStoreLogMessage.set(true);
                try {
                    String exceptionJson = null;
                    if (t != null) {
                        try {
                            exceptionJson = TraceSnapshotService.getExceptionJson(CapturedException
                                    .from(t));
                        } catch (IOException e) {
                            logger.error(e.getMessage(), e);
                        }
                    }
                    logMessageDao.storeLogMessage(LogMessage.from(clock.currentTimeMillis(), level,
                            message, exceptionJson));
                } finally {
                    inStoreLogMessage.set(false);
                    pendingCount.decrementAndGet();
                }
            }
        });
    }

    public void close() {
        logger.debug("close()");
        executorService.shutdownNow();
    }
}
