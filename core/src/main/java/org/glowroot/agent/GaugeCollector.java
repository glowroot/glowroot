/*
 * Copyright 2014-2015 the original author or authors.
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
package org.glowroot.agent;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

import javax.annotation.Nullable;
import javax.management.AttributeNotFoundException;
import javax.management.InstanceNotFoundException;
import javax.management.MBeanServer;
import javax.management.MalformedObjectNameException;
import javax.management.ObjectName;
import javax.management.openmbean.CompositeData;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;
import com.google.common.util.concurrent.ThreadFactoryBuilder;
import org.checkerframework.checker.nullness.qual.MonotonicNonNull;
import org.checkerframework.checker.nullness.qual.RequiresNonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.glowroot.agent.config.ConfigService;
import org.glowroot.agent.util.LazyPlatformMBeanServer;
import org.glowroot.agent.util.LazyPlatformMBeanServer.InitListener;
import org.glowroot.collector.spi.Collector;
import org.glowroot.collector.spi.model.GaugeValueOuterClass.GaugeValue;
import org.glowroot.common.config.GaugeConfig;
import org.glowroot.common.config.GaugeConfig.MBeanAttribute;
import org.glowroot.common.util.Clock;
import org.glowroot.common.util.PatternObjectNameQueryExp;
import org.glowroot.common.util.Reflections;
import org.glowroot.common.util.ScheduledRunnable;

import static com.google.common.base.Preconditions.checkNotNull;

class GaugeCollector extends ScheduledRunnable {

    private final Logger logger;

    private final ConfigService configService;
    private final Collector collector;
    private final LazyPlatformMBeanServer lazyPlatformMBeanServer;
    private final ScheduledExecutorService scheduledExecutor;
    private final Clock clock;
    private final long startTimeMillis;

    private final Set<String> pendingLoggedMBeanGauges = Sets.newConcurrentHashSet();
    private final Set<String> loggedMBeanGauges = Sets.newConcurrentHashSet();

    // gauges have their own dedicated scheduled executor service to make sure their collection is
    // not hampered by other glowroot threads
    private final ScheduledExecutorService dedicatedScheduledExecutor;

    // since gauges have their own dedicated thread, don't need to worry about thread safety of
    // priorRawCounterValues (except can't initialize here outside of the dedicated thread)
    private @MonotonicNonNull Map<String, GaugeValue> priorRawCounterValues;

    GaugeCollector(ConfigService configService, Collector collector,
            LazyPlatformMBeanServer lazyPlatformMBeanServer,
            ScheduledExecutorService scheduledExecutor, Clock clock, @Nullable Logger logger) {
        this.configService = configService;
        this.collector = collector;
        this.lazyPlatformMBeanServer = lazyPlatformMBeanServer;
        this.scheduledExecutor = scheduledExecutor;
        this.clock = clock;
        startTimeMillis = clock.currentTimeMillis();
        if (logger == null) {
            this.logger = LoggerFactory.getLogger(GaugeCollector.class);
        } else {
            this.logger = logger;
        }
        ThreadFactory threadFactory = new ThreadFactoryBuilder()
                .setDaemon(true)
                .setNameFormat("Glowroot-Gauge-Collector-%d")
                .build();
        dedicatedScheduledExecutor = Executors.newScheduledThreadPool(1, threadFactory);
        lazyPlatformMBeanServer.addInitListener(new InitListener() {
            @Override
            public void postInit(MBeanServer mbeanServer) {
                try {
                    Class<?> sunManagementFactoryHelperClass =
                            Class.forName("sun.management.ManagementFactoryHelper");
                    Method registerInternalMBeansMethod =
                            Reflections.getDeclaredMethod(sunManagementFactoryHelperClass,
                                    "registerInternalMBeans", MBeanServer.class);
                    registerInternalMBeansMethod.invoke(null, mbeanServer);
                } catch (Exception e) {
                    // checkNotNull is just to satisfy checker framework
                    checkNotNull(GaugeCollector.this.logger).debug(e.getMessage(), e);
                }
            }
        });
    }

    @Override
    protected void runInternal() throws Exception {
        final Map<String, GaugeValue> gaugeValues = Maps.newHashMap();
        if (priorRawCounterValues == null) {
            // wait to now to initialize priorGaugeValues inside of the dedicated thread
            priorRawCounterValues = Maps.newHashMap();
        }
        for (GaugeConfig gaugeConfig : configService.getGaugeConfigs()) {
            gaugeValues.putAll(collectGaugeValues(gaugeConfig));
        }
        try {
            scheduledExecutor.execute(new Runnable() {
                @Override
                public void run() {
                    try {
                        collector.collectGaugeValues(gaugeValues);
                    } catch (Throwable t) {
                        // log and terminate successfully
                        logger.error(t.getMessage(), t);
                    }
                }
            });
        } catch (RejectedExecutionException e) {
            if (scheduledExecutor.isShutdown()) {
                // ignore possible exception during shutdown
                logger.debug(e.getMessage(), e);
                return;
            }
            throw e;
        }
    }

    void scheduleAtFixedRate(long initialDelay, long period, TimeUnit unit) {
        scheduleAtFixedRate(dedicatedScheduledExecutor, initialDelay, period, unit);
    }

    void close() {
        dedicatedScheduledExecutor.shutdownNow();
    }

    @VisibleForTesting
    @RequiresNonNull("priorRawCounterValues")
    Map<String, GaugeValue> collectGaugeValues(GaugeConfig gaugeConfig)
            throws InterruptedException {
        String mbeanObjectName = gaugeConfig.mbeanObjectName();
        if (!mbeanObjectName.contains("*")) {
            ObjectName objectName;
            try {
                objectName = ObjectName.getInstance(mbeanObjectName);
            } catch (MalformedObjectNameException e) {
                logger.debug(e.getMessage(), e);
                // using toString() instead of getMessage() in order to capture exception class name
                logFirstTimeMBeanException(mbeanObjectName, e.toString());
                return ImmutableMap.of();
            }
            return collectGaugeValues(objectName, gaugeConfig.mbeanAttributes(), mbeanObjectName);
        }
        Set<ObjectName> objectNames = lazyPlatformMBeanServer.queryNames(null,
                new PatternObjectNameQueryExp(mbeanObjectName));
        if (objectNames.isEmpty()) {
            logFirstTimeMBeanNotMatchedOrFound(mbeanObjectName);
            return ImmutableMap.of();
        }
        Map<String, GaugeValue> gaugeValues = Maps.newHashMap();
        for (ObjectName objectName : objectNames) {
            gaugeValues.putAll(collectGaugeValues(objectName, gaugeConfig.mbeanAttributes(),
                    objectName.getDomain() + ":" + objectName.getKeyPropertyListString()));
        }
        return gaugeValues;
    }

    @RequiresNonNull("priorRawCounterValues")
    private Map<String, GaugeValue> collectGaugeValues(ObjectName objectName,
            List<MBeanAttribute> mbeanAttributes, String mbeanObjectName) {
        long captureTime = clock.currentTimeMillis();
        Map<String, GaugeValue> gaugeValues = Maps.newHashMap();
        for (MBeanAttribute mbeanAttribute : mbeanAttributes) {
            String mbeanAttributeName = mbeanAttribute.name();
            Object attributeValue;
            try {
                if (mbeanAttributeName.contains("/")) {
                    String[] path = mbeanAttributeName.split("\\/");
                    attributeValue = lazyPlatformMBeanServer.getAttribute(objectName, path[0]);
                    CompositeData compositeData = (CompositeData) attributeValue;
                    attributeValue = compositeData.get(path[1]);
                } else {
                    attributeValue =
                            lazyPlatformMBeanServer.getAttribute(objectName, mbeanAttributeName);
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
                // using toString() instead of getMessage() in order to capture exception class name
                logFirstTimeMBeanAttributeError(mbeanObjectName, mbeanAttributeName, e.toString());
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
                String gaugeName = mbeanObjectName + ',' + mbeanAttributeName;
                if (mbeanAttribute.counter()) {
                    GaugeValue priorRawCounterValue = priorRawCounterValues.get(gaugeName);
                    if (priorRawCounterValue != null) {
                        double valuePerSecond = 1000 * (value - priorRawCounterValue.getValue())
                                / (captureTime - priorRawCounterValue.getCaptureTime());
                        gaugeValues.put(gaugeName, GaugeValue.newBuilder()
                                .setCaptureTime(captureTime)
                                .setValue(valuePerSecond)
                                .build());
                    }
                    priorRawCounterValues.put(gaugeName, GaugeValue.newBuilder()
                            .setCaptureTime(captureTime)
                            .setValue(value)
                            .build());
                } else {
                    gaugeValues.put(gaugeName, GaugeValue.newBuilder()
                            .setCaptureTime(captureTime)
                            .setValue(value)
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
                logger.warn("mbean not {}: {} (waited {} seconds after jvm startup before"
                        + " logging this warning to allow time for mbean registration"
                        + " - this wait time can be changed under Configuration > Advanced)",
                        matchedOrFound, mbeanObjectName, delaySeconds);
            } else {
                logger.warn("mbean not {}: {}", matchedOrFound, mbeanObjectName);
            }
        }
    }

    // relatively common, so nice message
    private void logFirstTimeMBeanAttributeNotFound(String mbeanObjectName,
            String mbeanAttributeName) {
        if (loggedMBeanGauges.add(mbeanObjectName + "/" + mbeanAttributeName)) {
            logger.warn("mbean attribute {} not found: {}", mbeanAttributeName, mbeanObjectName);
        }
    }

    private void logFirstTimeMBeanException(String mbeanObjectName, @Nullable String message) {
        if (loggedMBeanGauges.add(mbeanObjectName)) {
            // using toString() instead of getMessage() in order to capture exception class name
            logger.warn("error accessing mbean {}: {}", mbeanObjectName, message);
        }
    }

    private void logFirstTimeMBeanAttributeError(String mbeanObjectName, String mbeanAttributeName,
            @Nullable String message) {
        if (loggedMBeanGauges.add(mbeanObjectName + "/" + mbeanAttributeName)) {
            logger.warn("error accessing mbean attribute {} {}: {}", mbeanObjectName,
                    mbeanAttributeName, message);
        }
    }
}
