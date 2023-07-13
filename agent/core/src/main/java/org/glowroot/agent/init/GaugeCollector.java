/*
 * Copyright 2014-2023 the original author or authors.
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
package org.glowroot.agent.init;

import java.lang.instrument.Instrumentation;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import javax.management.AttributeNotFoundException;
import javax.management.InstanceNotFoundException;
import javax.management.MBeanServer;
import javax.management.MalformedObjectNameException;
import javax.management.ObjectName;
import javax.management.openmbean.CompositeData;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Ticker;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.Queues;
import com.google.common.collect.Sets;
import org.checkerframework.checker.nullness.qual.MonotonicNonNull;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.checkerframework.checker.nullness.qual.RequiresNonNull;
import org.immutables.value.Value;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.glowroot.agent.collector.Collector;
import org.glowroot.agent.config.ConfigService;
import org.glowroot.agent.util.JavaVersion;
import org.glowroot.agent.util.LazyPlatformMBeanServer;
import org.glowroot.agent.util.LazyPlatformMBeanServer.InitListener;
import org.glowroot.agent.util.RateLimitedLogger;
import org.glowroot.agent.util.ThreadFactories;
import org.glowroot.agent.weaving.Java9;
import org.glowroot.common.config.GaugeConfig;
import org.glowroot.common.config.GaugeConfig.MBeanAttribute;
import org.glowroot.common.config.ImmutableMBeanAttribute;
import org.glowroot.common.util.Clock;
import org.glowroot.common.util.ScheduledRunnable;
import org.glowroot.common.util.Styles;
import org.glowroot.wire.api.model.CollectorServiceOuterClass.GaugeValueMessage.GaugeValue;

import static java.util.concurrent.TimeUnit.SECONDS;

class GaugeCollector extends ScheduledRunnable {

    // not final for testing purposes
    private static volatile Logger logger = LoggerFactory.getLogger(GaugeCollector.class);

    // back pressure on writing captured data to disk/network
    private static final int PENDING_LIMIT = 60;

    private final ConfigService configService;
    private final Collector collector;
    private final LazyPlatformMBeanServer lazyPlatformMBeanServer;
    private final Clock clock;
    private final Ticker ticker;
    private final long startTimeMillis;

    private final Set<String> pendingLoggedMBeanGauges = Sets.newConcurrentHashSet();
    private final Set<String> loggedMBeanGauges = Sets.newConcurrentHashSet();

    // gauges have their own dedicated executor to make sure their collection is not hampered by
    // other glowroot background work
    private final ScheduledExecutorService collectionExecutor;
    private final ExecutorService flushingExecutor;

    // need to guarantee these are processed in order (at least when running embedded collector
    // due to rollups relying on not seeing old data after newer data has been seen)
    private final BlockingQueue<List<GaugeValue>> pending =
            Queues.newLinkedBlockingQueue(PENDING_LIMIT);

    private final RateLimitedLogger backPressureLogger =
            new RateLimitedLogger(GaugeCollector.class);

    // since gauges have their own dedicated thread, don't need to worry about thread safety of
    // priorRawCounterValues (except can't initialize here outside of the dedicated thread)
    private @MonotonicNonNull Map<String, RawCounterValue> priorRawCounterValues;

    private volatile boolean closed;

    GaugeCollector(ConfigService configService, Collector collector,
            LazyPlatformMBeanServer lazyPlatformMBeanServer,
            final @Nullable Instrumentation instrumentation, Clock clock, Ticker ticker) {
        this.configService = configService;
        this.collector = collector;
        this.lazyPlatformMBeanServer = lazyPlatformMBeanServer;
        this.clock = clock;
        this.ticker = ticker;
        startTimeMillis = clock.currentTimeMillis();
        collectionExecutor = Executors.newSingleThreadScheduledExecutor(
                ThreadFactories.create("Glowroot-Gauge-Collection"));
        flushingExecutor = Executors
                .newSingleThreadExecutor(ThreadFactories.create("Glowroot-Gauge-Flushing"));
        lazyPlatformMBeanServer.addInitListener(new InitListener() {
            @Override
            public void postInit(MBeanServer mbeanServer) {
                try {
                    if (JavaVersion.isGreaterThanOrEqualToJava9() && instrumentation != null) {
                        Java9.grantAccess(instrumentation, "org.glowroot.agent.init.GaugeCollector",
                                "sun.management.ManagementFactoryHelper", true);
                    }
                    Class<?> sunManagementFactoryHelperClass =
                            Class.forName("sun.management.ManagementFactoryHelper");
                    Method registerInternalMBeansMethod = sunManagementFactoryHelperClass
                            .getDeclaredMethod("registerInternalMBeans", MBeanServer.class);
                    registerInternalMBeansMethod.setAccessible(true);
                    registerInternalMBeansMethod.invoke(null, mbeanServer);
                } catch (Throwable t) {
                    logger.debug(t.getMessage(), t);
                }
            }
        });
        flushingExecutor.execute(new GaugeFlushingLoop());
    }

    @Override
    protected void runInternal() throws Exception {
        final List<GaugeValue> gaugeValues = Lists.newArrayList();
        if (priorRawCounterValues == null) {
            // wait to now to initialize priorGaugeValues inside of the dedicated thread
            priorRawCounterValues = Maps.newHashMap();
        }
        List<MBeanServer> mbeanServers = lazyPlatformMBeanServer.findAllMBeanServers();
        for (GaugeConfig gaugeConfig : configService.getGaugeConfigs()) {
            gaugeValues.addAll(collectGaugeValues(gaugeConfig, mbeanServers));
        }
        if (!pending.offer(gaugeValues)) {
            backPressureLogger.warn("not storing a gauge collection because of an excessive backlog"
                    + " of {} gauge collections already waiting to be stored", PENDING_LIMIT);
        }
    }

    void scheduleWithFixedDelay(long period, TimeUnit unit) {
        scheduleWithFixedDelay(collectionExecutor, 0, period, unit);
    }

    void close() throws InterruptedException {
        closed = true;
        collectionExecutor.shutdown();
        if (!collectionExecutor.awaitTermination(10, SECONDS)) {
            throw new IllegalStateException("Could not terminate executor");
        }
        // shutdownNow() is needed here to send interrupt to flushing thread
        flushingExecutor.shutdownNow();
        if (!flushingExecutor.awaitTermination(10, SECONDS)) {
            throw new IllegalStateException("Could not terminate executor");
        }
    }

    @VisibleForTesting
    @RequiresNonNull("priorRawCounterValues")
    List<GaugeValue> collectGaugeValues(GaugeConfig gaugeConfig, List<MBeanServer> mbeanServers)
            throws Exception {
        String mbeanObjectName = gaugeConfig.mbeanObjectName();
        ObjectName objectName;
        try {
            objectName = ObjectName.getInstance(mbeanObjectName);
        } catch (MalformedObjectNameException e) {
            logger.debug(e.getMessage(), e);
            logFirstTimeMBeanException(mbeanObjectName, e);
            return ImmutableList.of();
        }
        if (!objectName.isPattern()) {
            return collectGaugeValues(objectName, gaugeConfig.mbeanAttributes(), mbeanObjectName,
                    mbeanServers);
        }
        Set<ObjectName> matchingObjectNames =
                lazyPlatformMBeanServer.queryNames(objectName, null, mbeanServers);
        if (matchingObjectNames.isEmpty()) {
            logFirstTimeMBeanNotMatchedOrFound(mbeanObjectName);
            return ImmutableList.of();
        }
        // remove from pendingLoggedMBeanGauges so if it is later not found, it will be logged
        // normally and not with "waited ... seconds after jvm startup before logging this" message
        pendingLoggedMBeanGauges.remove(mbeanObjectName);
        List<GaugeValue> gaugeValues = Lists.newArrayList();
        for (ObjectName matchingObjectName : matchingObjectNames) {
            gaugeValues.addAll(collectGaugeValues(matchingObjectName, gaugeConfig.mbeanAttributes(),
                    matchingObjectName.getDomain() + ":"
                            + matchingObjectName.getKeyPropertyListString(),
                    mbeanServers));
        }
        return gaugeValues;
    }

    @RequiresNonNull("priorRawCounterValues")
    private List<GaugeValue> collectGaugeValues(ObjectName objectName,
            List<ImmutableMBeanAttribute> mbeanAttributes, String mbeanObjectName,
            List<MBeanServer> mbeanServers) throws Exception {
        List<GaugeValue> gaugeValues = Lists.newArrayList();
        long captureTime = clock.currentTimeMillis();
        for (MBeanAttribute mbeanAttribute : mbeanAttributes) {
            String mbeanAttributeName = mbeanAttribute.name();
            Object attributeValue;
            try {
                if (mbeanAttributeName.contains(".")) {
                    String[] path = mbeanAttributeName.split("\\.");
                    attributeValue =
                            lazyPlatformMBeanServer.getAttribute(objectName, path[0], mbeanServers);
                    CompositeData compositeData = (CompositeData) attributeValue;
                    if (compositeData == null) {
                        // this is valid, e.g. attribute LastGcInfo on mbean
                        // java.lang:type=GarbageCollector,name=*
                        // prior to first GC, this attribute value is null
                        continue;
                    }
                    attributeValue = compositeData.get(path[1]);
                } else {
                    attributeValue = lazyPlatformMBeanServer.getAttribute(objectName,
                            mbeanAttributeName, mbeanServers);
                }
            } catch (InstanceNotFoundException e) {
                logger.debug(e.getMessage(), e);
                // other attributes for this mbean will give same error, so log mbean not
                // found and break out of attribute loop
                logFirstTimeMBeanNotMatchedOrFound(mbeanObjectName);
                break;
            } catch (AttributeNotFoundException e) {
                logger.debug(e.getMessage(), e);
                logFirstTimeMBeanAttributeNotFound(mbeanObjectName, mbeanAttributeName);
                continue;
            } catch (Exception e) {
                logger.debug(e.getMessage(), e);
                logFirstTimeMBeanAttributeError(mbeanObjectName, mbeanAttributeName, e);
                continue;
            }
            Double value = null;
            if (attributeValue instanceof Number) {
                value = ((Number) attributeValue).doubleValue();
            } else if (attributeValue instanceof String) {
                try {
                    value = Double.parseDouble((String) attributeValue);
                } catch (NumberFormatException e) {
                    logFirstTimeMBeanAttributeError(mbeanObjectName, mbeanAttributeName,
                            "MBean attribute value is not a valid number: \"" + attributeValue
                                    + "\"");
                }
            } else {
                logFirstTimeMBeanAttributeError(mbeanObjectName, mbeanAttributeName,
                        "MBean attribute value is not a number or string");
            }
            if (value != null) {
                StringBuilder gaugeName = new StringBuilder(mbeanObjectName.length() + 1
                        + mbeanAttributeName.length() + "[counter]".length());
                gaugeName.append(mbeanObjectName);
                gaugeName.append(':');
                gaugeName.append(mbeanAttributeName);
                if (mbeanAttribute.counter()) {
                    // "[counter]" suffix is so gauge name (and gauge id) will change if gauge is
                    // switched between counter and non-counter (which will prevent counter and
                    // non-counter values showing up in same chart line)
                    gaugeName.append("[counter]");
                    String gaugeNameStr = gaugeName.toString();
                    RawCounterValue priorRawCounterValue = priorRawCounterValues.get(gaugeNameStr);
                    long captureTick = ticker.read();
                    if (priorRawCounterValue != null) {
                        long intervalNanos = captureTick - priorRawCounterValue.captureTick();
                        // value is the average delta per second
                        double averageDeltaPerSecond =
                                1000000000 * (value - priorRawCounterValue.value()) / intervalNanos;
                        gaugeValues.add(GaugeValue.newBuilder()
                                .setGaugeName(gaugeNameStr)
                                .setCaptureTime(captureTime)
                                .setValue(averageDeltaPerSecond)
                                .setWeight(intervalNanos)
                                .build());
                    }
                    priorRawCounterValues.put(gaugeNameStr,
                            ImmutableRawCounterValue.of(value, captureTick));
                } else {
                    gaugeValues.add(GaugeValue.newBuilder()
                            .setGaugeName(gaugeName.toString())
                            .setCaptureTime(captureTime)
                            .setValue(value)
                            .setWeight(1)
                            .build());
                }
            }
        }
        return gaugeValues;
    }

    // relatively common, so nice message
    private void logFirstTimeMBeanNotMatchedOrFound(String mbeanObjectName) {
        int delaySeconds = configService.getAdvancedConfig().mbeanGaugeNotFoundDelaySeconds();
        if (clock.currentTimeMillis() - startTimeMillis < delaySeconds * 1000L) {
            pendingLoggedMBeanGauges.add(mbeanObjectName);
        } else if (loggedMBeanGauges.add(mbeanObjectName)) {
            String matchedOrFound = mbeanObjectName.contains("*") ? "matched" : "found";
            if (pendingLoggedMBeanGauges.remove(mbeanObjectName)) {
                logger.warn(
                        "mbean not {}: {} (waited {} seconds after jvm startup before logging this"
                                + " warning to allow time for mbean registration - this wait time"
                                + " can be changed under Configuration > Advanced)",
                        matchedOrFound, mbeanObjectName, delaySeconds);
            } else {
                logger.warn("mbean not {}: {}", matchedOrFound, mbeanObjectName);
            }
        }
    }

    private void logFirstTimeMBeanAttributeNotFound(String mbeanObjectName,
            String mbeanAttributeName) {
        if (loggedMBeanGauges.add(mbeanObjectName + ":" + mbeanAttributeName)) {
            // relatively common, so nice message
            logger.warn("mbean attribute {} not found in {}", mbeanAttributeName, mbeanObjectName);
        }
    }

    private void logFirstTimeMBeanException(String mbeanObjectName, Exception e) {
        if (loggedMBeanGauges.add(mbeanObjectName)) {
            logger.warn("error accessing mbean: {}", mbeanObjectName, e);
        }
    }

    private void logFirstTimeMBeanAttributeError(String mbeanObjectName, String mbeanAttributeName,
            String message) {
        if (loggedMBeanGauges.add(mbeanObjectName + ":" + mbeanAttributeName)) {
            logger.warn("error accessing mbean attribute {} {}: {}", mbeanObjectName,
                    mbeanAttributeName, message);
        }
    }

    private void logFirstTimeMBeanAttributeError(String mbeanObjectName, String mbeanAttributeName,
            Exception e) {
        if (loggedMBeanGauges.add(mbeanObjectName + ":" + mbeanAttributeName)) {
            logger.warn("error accessing mbean attribute: {} {}", mbeanObjectName,
                    mbeanAttributeName, e);
        }
    }

    public void setLoggerForTesting(Logger logger) {
        GaugeCollector.logger = logger;
    }

    @Value.Immutable
    @Styles.AllParameters
    interface RawCounterValue {
        double value();
        long captureTick();
    }

    private class GaugeFlushingLoop implements Runnable {
        @Override
        public void run() {
            while (!closed) {
                try {
                    collector.collectGaugeValues(pending.take());
                } catch (InterruptedException e) {
                    // probably shutdown requested (see close method above)
                    logger.debug(e.getMessage(), e);
                } catch (Throwable e) {
                    // log and continue processing
                    logger.error(e.getMessage(), e);
                }
            }
        }
    }
}
