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
package org.glowroot.collector;

import java.lang.reflect.Method;
import java.util.List;
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
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;
import com.google.common.collect.Sets;
import com.google.common.util.concurrent.ThreadFactoryBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.glowroot.common.Clock;
import org.glowroot.common.Reflections;
import org.glowroot.common.ScheduledRunnable;
import org.glowroot.config.ConfigService;
import org.glowroot.config.GaugeConfig;
import org.glowroot.config.MBeanAttribute;
import org.glowroot.jvm.LazyPlatformMBeanServer;
import org.glowroot.jvm.LazyPlatformMBeanServer.MBeanServerCallback;

import static com.google.common.base.Preconditions.checkNotNull;

class GaugeCollector extends ScheduledRunnable {

    private final Logger logger;

    private final ConfigService configService;
    private final GaugePointRepository gaugePointRepository;
    private final LazyPlatformMBeanServer lazyPlatformMBeanServer;
    private final ScheduledExecutorService scheduledExecutor;
    private final Clock clock;
    private final long startTimeMillis;

    private final Set<String> pendingLoggedMBeanGauges = Sets.newConcurrentHashSet();
    private final Set<String> loggedMBeanGauges = Sets.newConcurrentHashSet();

    // gauges have their own dedicated scheduled executor service to make sure their collection is
    // not hampered by other glowroot threads
    private final ScheduledExecutorService dedicatedScheduledExecutor;

    GaugeCollector(ConfigService configService, GaugePointRepository gaugePointRepository,
            LazyPlatformMBeanServer lazyPlatformMBeanServer,
            ScheduledExecutorService scheduledExecutor, Clock clock, @Nullable Logger logger) {
        this.configService = configService;
        this.gaugePointRepository = gaugePointRepository;
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
        lazyPlatformMBeanServer.possiblyDelayedCall(new MBeanServerCallback() {
            @Override
            public void call(MBeanServer mbeanServer) {
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
        final List<GaugePoint> gaugePoints = Lists.newArrayList();
        for (GaugeConfig gaugeConfig : configService.getGaugeConfigs()) {
            gaugePoints.addAll(runInternal(gaugeConfig));
        }
        try {
            scheduledExecutor.execute(new Runnable() {
                @Override
                public void run() {
                    try {
                        gaugePointRepository.store(gaugePoints);
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
    List<GaugePoint> runInternal(GaugeConfig gaugeConfig) {
        ObjectName objectName;
        try {
            objectName = ObjectName.getInstance(gaugeConfig.mbeanObjectName());
        } catch (MalformedObjectNameException e) {
            logger.debug(e.getMessage(), e);
            // using toString() instead of getMessage() in order to capture exception class name
            logFirstTimeMBeanException(gaugeConfig, e.toString());
            return ImmutableList.of();
        }
        long captureTime = clock.currentTimeMillis();
        List<GaugePoint> gaugePoints = Lists.newArrayList();
        for (MBeanAttribute mbeanAttribute : gaugeConfig.mbeanAttributes()) {
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
                logFirstTimeMBeanNotFound(gaugeConfig);
                break;
            } catch (AttributeNotFoundException e) {
                logger.debug(e.getMessage(), e);
                logFirstTimeMBeanAttributeNotFound(gaugeConfig, mbeanAttributeName);
                continue;
            } catch (Exception e) {
                logger.debug(e.getMessage(), e);
                // using toString() instead of getMessage() in order to capture exception class name
                logFirstTimeMBeanAttributeError(gaugeConfig, mbeanAttributeName, e.toString());
                continue;
            }
            if (attributeValue instanceof Number) {
                double value = ((Number) attributeValue).doubleValue();
                gaugePoints.add(GaugePoint.builder()
                        .gaugeName(gaugeConfig.mbeanObjectName() + ',' + mbeanAttributeName)
                        .captureTime(captureTime)
                        .value(value)
                        .build());
            } else {
                logFirstTimeMBeanAttributeError(gaugeConfig, mbeanAttributeName,
                        "MBean attribute value is not a number");
            }
        }
        return gaugePoints;
    }

    // relatively common, so nice message
    private void logFirstTimeMBeanNotFound(GaugeConfig mbeanGauge) {
        int delaySeconds = configService.getAdvancedConfig().mbeanGaugeNotFoundDelaySeconds();
        if (clock.currentTimeMillis() - startTimeMillis < delaySeconds * 1000L) {
            pendingLoggedMBeanGauges.add(mbeanGauge.version());
        } else if (loggedMBeanGauges.add(mbeanGauge.version())) {
            if (pendingLoggedMBeanGauges.remove(mbeanGauge.version())) {
                logger.warn("mbean not found: {} (waited {} seconds after jvm startup before"
                        + " logging this warning to allow time for mbean registration"
                        + " - this wait time can be changed under Configuration > Advanced)",
                        mbeanGauge.mbeanObjectName(), delaySeconds);
            } else {
                logger.warn("mbean not found: {}", mbeanGauge.mbeanObjectName());
            }
        }
    }

    // relatively common, so nice message
    private void logFirstTimeMBeanAttributeNotFound(GaugeConfig mbeanGauge,
            String mbeanAttributeName) {
        if (loggedMBeanGauges.add(mbeanGauge.version() + "/" + mbeanAttributeName)) {
            logger.warn("mbean attribute {} not found: {}", mbeanAttributeName,
                    mbeanGauge.mbeanObjectName());
        }
    }

    private void logFirstTimeMBeanException(GaugeConfig mbeanGauge, @Nullable String message) {
        if (loggedMBeanGauges.add(mbeanGauge.version())) {
            // using toString() instead of getMessage() in order to capture exception class name
            logger.warn("error accessing mbean {}: {}", mbeanGauge.mbeanObjectName(),
                    message);
        }
    }

    private void logFirstTimeMBeanAttributeError(GaugeConfig mbeanGauge, String mbeanAttributeName,
            @Nullable String message) {
        if (loggedMBeanGauges.add(mbeanGauge.version() + "/" + mbeanAttributeName)) {
            logger.warn("error accessing mbean attribute {} {}: {}",
                    mbeanGauge.mbeanObjectName(), mbeanAttributeName, message);
        }
    }
}
