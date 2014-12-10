/*
 * Copyright 2014 the original author or authors.
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

import java.util.List;
import java.util.Set;

import javax.annotation.Nullable;
import javax.management.AttributeNotFoundException;
import javax.management.InstanceNotFoundException;
import javax.management.MalformedObjectNameException;
import javax.management.ObjectName;
import javax.management.openmbean.CompositeData;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;
import com.google.common.collect.Sets;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.glowroot.common.Clock;
import org.glowroot.common.ScheduledRunnable;
import org.glowroot.config.ConfigService;
import org.glowroot.config.MBeanGauge;
import org.glowroot.jvm.LazyPlatformMBeanServer;

class GaugeCollector extends ScheduledRunnable {

    private static final Logger logger = LoggerFactory.getLogger(GaugeCollector.class);

    private final ConfigService configService;
    private final GaugePointRepository gaugePointRepository;
    private final LazyPlatformMBeanServer lazyPlatformMBeanServer;
    private final Clock clock;
    private final long startTimeMillis;

    private final Set<String> pendingLoggedMBeanGauges = Sets.newConcurrentHashSet();
    private final Set<String> loggedMBeanGauges = Sets.newConcurrentHashSet();

    GaugeCollector(ConfigService configService, GaugePointRepository gaugePointRepository,
            LazyPlatformMBeanServer lazyPlatformMBeanServer, Clock clock) {
        this.configService = configService;
        this.gaugePointRepository = gaugePointRepository;
        this.lazyPlatformMBeanServer = lazyPlatformMBeanServer;
        this.clock = clock;
        startTimeMillis = clock.currentTimeMillis();
    }

    @Override
    protected void runInternal() {
        List<GaugePoint> gaugePoints = Lists.newArrayList();
        for (MBeanGauge mbeanGauge : configService.getMBeanGauges()) {
            gaugePoints.addAll(runInternal(mbeanGauge));
        }
        gaugePointRepository.store(gaugePoints);
    }

    private List<GaugePoint> runInternal(MBeanGauge mbeanGauge) {
        ObjectName objectName;
        try {
            objectName = ObjectName.getInstance(mbeanGauge.mbeanObjectName());
        } catch (MalformedObjectNameException e) {
            logger.debug(e.getMessage(), e);
            // using toString() instead of getMessage() in order to capture exception class name
            logFirstTimeMBeanException(mbeanGauge, e.toString());
            return ImmutableList.of();
        }
        long captureTime = clock.currentTimeMillis();
        List<GaugePoint> gaugePoints = Lists.newArrayList();
        for (String mbeanAttributeName : mbeanGauge.mbeanAttributeNames()) {
            Object attributeValue;
            try {
                if (mbeanAttributeName.contains(".")) {
                    String[] path = mbeanAttributeName.split("\\.");
                    attributeValue = lazyPlatformMBeanServer.getAttribute(objectName, path[0]);
                    CompositeData compositeData = ((CompositeData) attributeValue);
                    attributeValue = compositeData.get(path[1]);
                } else {
                    attributeValue =
                            lazyPlatformMBeanServer.getAttribute(objectName, mbeanAttributeName);
                }
            } catch (InstanceNotFoundException e) {
                logger.debug(e.getMessage(), e);
                // other attributes for this mbean will give same error, so log mbean not
                // found and break out of attribute loop
                logFirstTimeMBeanNotFound(mbeanGauge);
                break;
            } catch (AttributeNotFoundException e) {
                logger.debug(e.getMessage(), e);
                logFirstTimeMBeanAttributeNotFound(mbeanGauge, mbeanAttributeName);
                continue;
            } catch (Exception e) {
                logger.debug(e.getMessage(), e);
                // using toString() instead of getMessage() in order to capture exception class name
                logFirstTimeMBeanAttributeError(mbeanGauge, mbeanAttributeName, e.toString());
                continue;
            }
            if (attributeValue instanceof Number) {
                double value = ((Number) attributeValue).doubleValue();
                gaugePoints.add(ImmutableGaugePoint.builder()
                        .gaugeName(mbeanGauge.name() + "/" + mbeanAttributeName)
                        .captureTime(captureTime)
                        .value(value)
                        .build());
            } else {
                logFirstTimeMBeanAttributeError(mbeanGauge, mbeanAttributeName,
                        "MBean attribute value is not a number");
            }
        }
        return gaugePoints;
    }

    // relatively common, so nice message
    private void logFirstTimeMBeanNotFound(MBeanGauge mbeanGauge) {
        int delaySeconds = configService.getAdvancedConfig().mbeanGaugeNotFoundDelaySeconds();
        if (clock.currentTimeMillis() - startTimeMillis < delaySeconds * 1000) {
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
    private void logFirstTimeMBeanAttributeNotFound(MBeanGauge mbeanGauge,
            String mbeanAttributeName) {
        if (loggedMBeanGauges.add(mbeanGauge.version() + "/" + mbeanAttributeName)) {
            logger.warn("mbean attribute {} not found: {}", mbeanAttributeName,
                    mbeanGauge.mbeanObjectName());
        }
    }

    private void logFirstTimeMBeanException(MBeanGauge mbeanGauge, @Nullable String message) {
        if (loggedMBeanGauges.add(mbeanGauge.version())) {
            // using toString() instead of getMessage() in order to capture exception class name
            logger.warn("error accessing mbean {}: {}", mbeanGauge.mbeanObjectName(),
                    message);
        }
    }

    private void logFirstTimeMBeanAttributeError(MBeanGauge mbeanGauge, String mbeanAttributeName,
            @Nullable String message) {
        if (loggedMBeanGauges.add(mbeanGauge.version() + "/" + mbeanAttributeName)) {
            logger.warn("error accessing mbean attribute {} {}: {}",
                    mbeanGauge.mbeanObjectName(), mbeanAttributeName, message);
        }
    }
}
