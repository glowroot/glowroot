/*
 * Copyright 2015 the original author or authors.
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

import javax.management.AttributeNotFoundException;
import javax.management.InstanceNotFoundException;
import javax.management.ObjectName;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.slf4j.Logger;

import org.glowroot.common.Clock;
import org.glowroot.config.AdvancedConfig;
import org.glowroot.config.ConfigService;
import org.glowroot.config.GaugeConfig;
import org.glowroot.config.ImmutableGaugeConfig;
import org.glowroot.jvm.LazyPlatformMBeanServer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Matchers.any;
import static org.mockito.Matchers.anyString;
import static org.mockito.Matchers.eq;
import static org.mockito.Matchers.startsWith;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

public class GaugeCollectorTest {

    private GaugeCollector gaugeCollector;
    private LazyPlatformMBeanServer lazyPlatformMBeanServer;
    private Clock clock;
    private Logger logger;

    @Before
    public void beforeEachTest() {
        ConfigService configService = mock(ConfigService.class);
        AdvancedConfig advancedConfig = mock(AdvancedConfig.class);
        when(configService.getAdvancedConfig()).thenReturn(advancedConfig);
        when(advancedConfig.mbeanGaugeNotFoundDelaySeconds()).thenReturn(60);

        GaugePointRepository gaugePointRepository = mock(GaugePointRepository.class);
        lazyPlatformMBeanServer = mock(LazyPlatformMBeanServer.class);
        clock = mock(Clock.class);
        logger = mock(Logger.class);
        gaugeCollector = new GaugeCollector(configService, gaugePointRepository,
                lazyPlatformMBeanServer, clock, logger);
    }

    @After
    public void afterEachTest() {
        verifyNoMoreInteractions(logger);
    }

    @Test
    public void shouldHandleInvalidMBeanObjectName() {
        // given
        GaugeConfig gaugeConfigs = ImmutableGaugeConfig.builder()
                .name("abc")
                .mbeanObjectName("invalid mbean object name")
                .build();
        // when
        List<GaugePoint> gaugePoints = gaugeCollector.runInternal(gaugeConfigs);
        // then
        assertThat(gaugePoints).isEmpty();
        verify(logger).debug(anyString(), any(Exception.class));
        verify(logger).warn(eq("error accessing mbean {}: {}"), eq("invalid mbean object name"),
                startsWith(""));
    }

    @Test
    @SuppressWarnings("unchecked")
    public void shouldHandleMBeanInstanceNotFoundBeforeLoggingDelay() throws Exception {
        // given
        GaugeConfig gaugeConfigs = ImmutableGaugeConfig.builder()
                .name("abc")
                .mbeanObjectName("xyz:aaa=bbb")
                .addMbeanAttributeNames("ccc")
                .addMbeanAttributeNames("ddd")
                .build();
        when(clock.currentTimeMillis()).thenReturn(59999L);
        when(lazyPlatformMBeanServer.getAttribute(any(ObjectName.class), anyString()))
                .thenThrow(InstanceNotFoundException.class);
        // when
        List<GaugePoint> gaugePoints = gaugeCollector.runInternal(gaugeConfigs);
        // then
        assertThat(gaugePoints).isEmpty();
        verify(logger).debug(anyString(), any(Exception.class));
    }

    @Test
    @SuppressWarnings("unchecked")
    public void shouldHandleMBeanInstanceNotFoundAfterLoggingDelay() throws Exception {
        // given
        GaugeConfig gaugeConfig = ImmutableGaugeConfig.builder()
                .name("abc")
                .mbeanObjectName("xyz:aaa=bbb")
                .addMbeanAttributeNames("ccc")
                .addMbeanAttributeNames("ddd")
                .build();
        when(clock.currentTimeMillis()).thenReturn(60000L);
        when(lazyPlatformMBeanServer.getAttribute(any(ObjectName.class), anyString()))
                .thenThrow(InstanceNotFoundException.class);
        // when
        List<GaugePoint> gaugePoints = gaugeCollector.runInternal(gaugeConfig);
        // then
        assertThat(gaugePoints).isEmpty();
        verify(logger).debug(anyString(), any(Exception.class));
        verify(logger).warn("mbean not found: {}", "xyz:aaa=bbb");
    }

    @Test
    @SuppressWarnings("unchecked")
    public void shouldHandleMBeanInstanceNotFoundBeforeAndAfterLoggingDelay() throws Exception {
        // given
        GaugeConfig gaugeConfig = ImmutableGaugeConfig.builder()
                .name("abc")
                .mbeanObjectName("xyz:aaa=bbb")
                .addMbeanAttributeNames("ccc")
                .addMbeanAttributeNames("ddd")
                .build();
        when(clock.currentTimeMillis()).thenReturn(0L).thenReturn(30000L).thenReturn(60000L);
        when(lazyPlatformMBeanServer.getAttribute(any(ObjectName.class), anyString()))
                .thenThrow(InstanceNotFoundException.class);
        // when
        gaugeCollector.runInternal(gaugeConfig);
        gaugeCollector.runInternal(gaugeConfig);
        gaugeCollector.runInternal(gaugeConfig);
        gaugeCollector.runInternal(gaugeConfig);
        gaugeCollector.runInternal(gaugeConfig);
        // then
        verify(logger, times(5)).debug(anyString(), any(Exception.class));
        verify(logger).warn("mbean not found: {} (waited {} seconds after jvm startup before"
                + " logging this warning to allow time for mbean registration"
                + " - this wait time can be changed under Configuration > Advanced)",
                "xyz:aaa=bbb", 60);
    }

    @Test
    @SuppressWarnings("unchecked")
    public void shouldHandleMBeanAttributeNotFound() throws Exception {
        // given
        GaugeConfig gaugeConfig = ImmutableGaugeConfig.builder()
                .name("abc")
                .mbeanObjectName("xyz:aaa=bbb")
                .addMbeanAttributeNames("ccc")
                .addMbeanAttributeNames("ddd")
                .build();
        when(lazyPlatformMBeanServer.getAttribute(any(ObjectName.class), anyString()))
                .thenThrow(AttributeNotFoundException.class);
        // when
        gaugeCollector.runInternal(gaugeConfig);
        gaugeCollector.runInternal(gaugeConfig);
        gaugeCollector.runInternal(gaugeConfig);
        gaugeCollector.runInternal(gaugeConfig);
        gaugeCollector.runInternal(gaugeConfig);
        // then
        verify(logger, times(10)).debug(anyString(), any(Exception.class));
        verify(logger).warn("mbean attribute {} not found: {}", "ccc", "xyz:aaa=bbb");
        verify(logger).warn("mbean attribute {} not found: {}", "ddd", "xyz:aaa=bbb");
    }

    @Test
    public void shouldHandleMBeanAttributeOtherException() throws Exception {
        // given
        GaugeConfig gaugeConfig = ImmutableGaugeConfig.builder()
                .name("abc")
                .mbeanObjectName("xyz:aaa=bbb")
                .addMbeanAttributeNames("ccc")
                .addMbeanAttributeNames("ddd")
                .build();
        when(lazyPlatformMBeanServer.getAttribute(any(ObjectName.class), anyString()))
                .thenThrow(new RuntimeException("A msg"));
        // when
        gaugeCollector.runInternal(gaugeConfig);
        gaugeCollector.runInternal(gaugeConfig);
        gaugeCollector.runInternal(gaugeConfig);
        gaugeCollector.runInternal(gaugeConfig);
        gaugeCollector.runInternal(gaugeConfig);
        // then
        verify(logger, times(10)).debug(anyString(), any(Exception.class));
        verify(logger).warn("error accessing mbean attribute {} {}: {}", "xyz:aaa=bbb", "ccc",
                "java.lang.RuntimeException: A msg");
        verify(logger).warn("error accessing mbean attribute {} {}: {}", "xyz:aaa=bbb", "ddd",
                "java.lang.RuntimeException: A msg");
    }

    @Test
    public void shouldHandleMBeanAttributeNotANumber() throws Exception {
        // given
        GaugeConfig gaugeConfig = ImmutableGaugeConfig.builder()
                .name("abc")
                .mbeanObjectName("xyz:aaa=bbb")
                .addMbeanAttributeNames("ccc")
                .addMbeanAttributeNames("ddd")
                .build();
        when(lazyPlatformMBeanServer.getAttribute(any(ObjectName.class), anyString()))
                .thenReturn("not a number");
        // when
        gaugeCollector.runInternal(gaugeConfig);
        gaugeCollector.runInternal(gaugeConfig);
        gaugeCollector.runInternal(gaugeConfig);
        gaugeCollector.runInternal(gaugeConfig);
        gaugeCollector.runInternal(gaugeConfig);
        // then
        verify(logger).warn("error accessing mbean attribute {} {}: {}", "xyz:aaa=bbb", "ccc",
                "MBean attribute value is not a number");
        verify(logger).warn("error accessing mbean attribute {} {}: {}", "xyz:aaa=bbb", "ddd",
                "MBean attribute value is not a number");
    }
}
