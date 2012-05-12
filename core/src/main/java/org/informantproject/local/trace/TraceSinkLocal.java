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
package org.informantproject.local.trace;

import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.informantproject.api.Message;
import org.informantproject.api.Optional;
import org.informantproject.core.configuration.ConfigurationService;
import org.informantproject.core.configuration.ImmutableCoreConfiguration;
import org.informantproject.core.trace.Span;
import org.informantproject.core.trace.Trace;
import org.informantproject.core.trace.TraceSink;
import org.informantproject.core.util.DaemonExecutors;
import org.informantproject.core.util.OptionalJsonSerializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.base.Ticker;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.inject.Inject;
import com.google.inject.Singleton;

/**
 * Implementation of TraceSink for local storage in embedded H2 database. Some day there may be
 * another implementation for remote storage (e.g. central monitoring system).
 * 
 * @author Trask Stalnaker
 * @since 0.5
 */
@Singleton
public class TraceSinkLocal implements TraceSink {

    private static final Logger logger = LoggerFactory.getLogger(TraceSinkLocal.class);

    private final ExecutorService executorService = DaemonExecutors
            .newSingleThreadExecutor("Informant-StackCollector");

    private final ConfigurationService configurationService;
    private final TraceDao traceDao;
    private final TraceCommonJsonService traceCommonJsonService;
    private final Ticker ticker;

    private final AtomicInteger queueLength = new AtomicInteger(0);

    @Inject
    public TraceSinkLocal(ConfigurationService configurationService, TraceDao traceDao,
            TraceCommonJsonService traceCommonJsonService, Ticker ticker) {

        this.configurationService = configurationService;
        this.traceDao = traceDao;
        this.traceCommonJsonService = traceCommonJsonService;
        this.ticker = ticker;
    }

    public void onCompletedTrace(final Trace trace) {
        ImmutableCoreConfiguration configuration = configurationService.getCoreConfiguration();
        int thresholdMillis = configuration.getThresholdMillis();
        boolean thresholdDisabled =
                (thresholdMillis == ImmutableCoreConfiguration.THRESHOLD_DISABLED);
        long durationInNanoseconds = trace.getRootSpan().getDuration();
        // if the completed trace exceeded the given threshold then it is sent to the sink. the
        // completed trace is also checked in case it was previously sent to the sink and marked as
        // stuck, and the threshold was disabled or increased in the meantime, in which case the
        // full completed trace needs to be (re-)sent to the sink
        if ((!thresholdDisabled && durationInNanoseconds >= TimeUnit.MILLISECONDS
                .toNanos(thresholdMillis)) || trace.isStuck()) {

            queueLength.incrementAndGet();
            executorService.execute(new Runnable() {
                public void run() {
                    try {
                        traceDao.storeTrace(buildStoredTrace(trace));
                    } catch (IOException e) {
                        logger.error(e.getMessage(), e);
                    }
                    queueLength.decrementAndGet();
                }
            });
        }
    }

    public void onStuckTrace(Trace trace) {
        try {
            traceDao.storeTrace(buildStoredTrace(trace));
        } catch (IOException e) {
            logger.error(e.getMessage(), e);
        }
    }

    public int getQueueLength() {
        return queueLength.get();
    }

    public StoredTrace buildStoredTrace(Trace trace) throws IOException {
        long captureTick = ticker.read();
        StoredTrace storedTrace = new StoredTrace();
        storedTrace.setId(trace.getId());
        storedTrace.setStartAt(trace.getStartDate().getTime());
        storedTrace.setStuck(trace.isStuck() && !trace.isCompleted());
        // timings for traces that are still active are normalized to the capture tick in order to
        // *attempt* to present a picture of the trace at that exact tick
        // (without using synchronization to block updates to the trace while it is being read)
        long endTick = trace.getEndTick();
        if (endTick != 0 && endTick <= captureTick) {
            storedTrace.setDuration(trace.getDuration());
            storedTrace.setCompleted(true);
        } else {
            storedTrace.setDuration(captureTick - trace.getStartTick());
            storedTrace.setCompleted(false);
        }
        Span rootSpan = trace.getRootSpan().getSpans().iterator().next();
        Message message = rootSpan.getMessageSupplier().get();
        storedTrace.setDescription(message.getText());
        Optional<String> username = trace.getUsername().get();
        if (username.isPresent()) {
            storedTrace.setUsername(username.get());
        }
        // OptionalJsonSerializer is needed for serializing trace attributes and span context maps
        Gson gson = new GsonBuilder().registerTypeHierarchyAdapter(Optional.class,
                new OptionalJsonSerializer()).create();
        storedTrace.setAttributes(gson.toJson(trace.getAttributes()));
        storedTrace.setMetrics(TraceCommonJsonService.getMetricsJson(trace, gson));
        storedTrace.setSpans(traceCommonJsonService.getSpansByteStream(trace, captureTick, gson));
        storedTrace.setMergedStackTree(TraceCommonJsonService.getMergedStackTree(trace));
        return storedTrace;
    }

    public void shutdown() {
        logger.debug("shutdown()");
        executorService.shutdownNow();
    }
}
