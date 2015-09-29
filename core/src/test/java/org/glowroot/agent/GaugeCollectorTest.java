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
package org.glowroot.agent;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.List;

import javax.management.AttributeNotFoundException;
import javax.management.InstanceNotFoundException;
import javax.management.ObjectName;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.slf4j.Logger;

import org.glowroot.agent.config.ConfigService;
import org.glowroot.agent.util.LazyPlatformMBeanServer;
import org.glowroot.collector.spi.Collector;
import org.glowroot.collector.spi.model.GaugeValueOuterClass.GaugeValue;
import org.glowroot.common.config.AdvancedConfig;
import org.glowroot.common.config.GaugeConfig;
import org.glowroot.common.config.ImmutableAdvancedConfig;
import org.glowroot.common.config.ImmutableGaugeConfig;
import org.glowroot.common.config.ImmutableMBeanAttribute;
import org.glowroot.common.util.Clock;

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
    public void beforeEachTest() throws Exception {
        ConfigService configService = mock(ConfigService.class);
        AdvancedConfig advancedConfig =
                ImmutableAdvancedConfig.builder().mbeanGaugeNotFoundDelaySeconds(60).build();
        when(configService.getAdvancedConfig()).thenReturn(advancedConfig);

        Collector collector = mock(Collector.class);
        lazyPlatformMBeanServer = mock(LazyPlatformMBeanServer.class);
        clock = mock(Clock.class);
        logger = mock(Logger.class);
        setLogger(GaugeCollector.class, logger);
        gaugeCollector =
                new GaugeCollector(configService, collector, lazyPlatformMBeanServer, clock);
    }

    @After
    public void afterEachTest() {
        verifyNoMoreInteractions(logger);
    }

    @Test
    public void shouldHandleInvalidMBeanObjectName() throws InterruptedException {
        // given
        GaugeConfig gaugeConfig = ImmutableGaugeConfig.builder()
                .mbeanObjectName("invalid mbean object name")
                .build();
        // when
        List<GaugeValue> gaugeValues = gaugeCollector.collectGaugeValues(gaugeConfig);
        // then
        assertThat(gaugeValues).isEmpty();
        verify(logger).debug(anyString(), any(Exception.class));
        verify(logger).warn(eq("error accessing mbean {}: {}"), eq("invalid mbean object name"),
                startsWith(""));
    }

    @Test
    @SuppressWarnings("unchecked")
    public void shouldHandleMBeanInstanceNotFoundBeforeLoggingDelay() throws Exception {
        // given
        GaugeConfig gaugeConfig = ImmutableGaugeConfig.builder()
                .mbeanObjectName("xyz:aaa=bbb")
                .addMbeanAttributes(ImmutableMBeanAttribute.of("ccc", false))
                .addMbeanAttributes(ImmutableMBeanAttribute.of("ddd", false))
                .build();
        when(clock.currentTimeMillis()).thenReturn(59999L);
        when(lazyPlatformMBeanServer.getAttribute(any(ObjectName.class), anyString()))
                .thenThrow(InstanceNotFoundException.class);
        // when
        List<GaugeValue> gaugeValues = gaugeCollector.collectGaugeValues(gaugeConfig);
        // then
        assertThat(gaugeValues).isEmpty();
        verify(logger).debug(anyString(), any(Exception.class));
    }

    @Test
    @SuppressWarnings("unchecked")
    public void shouldHandleMBeanInstanceNotFoundAfterLoggingDelay() throws Exception {
        // given
        GaugeConfig gaugeConfig = ImmutableGaugeConfig.builder()
                .mbeanObjectName("xyz:aaa=bbb")
                .addMbeanAttributes(ImmutableMBeanAttribute.of("ccc", false))
                .addMbeanAttributes(ImmutableMBeanAttribute.of("ddd", false))
                .build();
        when(clock.currentTimeMillis()).thenReturn(60000L);
        when(lazyPlatformMBeanServer.getAttribute(any(ObjectName.class), anyString()))
                .thenThrow(InstanceNotFoundException.class);
        // when
        List<GaugeValue> gaugeValues = gaugeCollector.collectGaugeValues(gaugeConfig);
        // then
        assertThat(gaugeValues).isEmpty();
        verify(logger).debug(anyString(), any(Exception.class));
        verify(logger).warn("mbean not {}: {}", "found", "xyz:aaa=bbb");
    }

    @Test
    @SuppressWarnings("unchecked")
    public void shouldHandleMBeanInstanceNotFoundBeforeAndAfterLoggingDelay() throws Exception {
        // given
        GaugeConfig gaugeConfig = ImmutableGaugeConfig.builder()
                .mbeanObjectName("xyz:aaa=bbb")
                .addMbeanAttributes(ImmutableMBeanAttribute.of("ccc", false))
                .addMbeanAttributes(ImmutableMBeanAttribute.of("ddd", false))
                .build();
        when(clock.currentTimeMillis()).thenReturn(0L).thenReturn(30000L).thenReturn(60000L);
        when(lazyPlatformMBeanServer.getAttribute(any(ObjectName.class), anyString()))
                .thenThrow(InstanceNotFoundException.class);
        // when
        gaugeCollector.collectGaugeValues(gaugeConfig);
        gaugeCollector.collectGaugeValues(gaugeConfig);
        gaugeCollector.collectGaugeValues(gaugeConfig);
        gaugeCollector.collectGaugeValues(gaugeConfig);
        gaugeCollector.collectGaugeValues(gaugeConfig);
        // then
        verify(logger, times(5)).debug(anyString(), any(Exception.class));
        verify(logger).warn("mbean not {}: {} (waited {} seconds after jvm startup before"
                + " logging this warning to allow time for mbean registration"
                + " - this wait time can be changed under Configuration > Advanced)",
                "found", "xyz:aaa=bbb", 60);
    }

    @Test
    @SuppressWarnings("unchecked")
    public void shouldHandleMBeanAttributeNotFound() throws Exception {
        // given
        GaugeConfig gaugeConfig = ImmutableGaugeConfig.builder()
                .mbeanObjectName("xyz:aaa=bbb")
                .addMbeanAttributes(ImmutableMBeanAttribute.of("ccc", false))
                .addMbeanAttributes(ImmutableMBeanAttribute.of("ddd", false))
                .build();
        when(lazyPlatformMBeanServer.getAttribute(any(ObjectName.class), anyString()))
                .thenThrow(AttributeNotFoundException.class);
        // when
        gaugeCollector.collectGaugeValues(gaugeConfig);
        gaugeCollector.collectGaugeValues(gaugeConfig);
        gaugeCollector.collectGaugeValues(gaugeConfig);
        gaugeCollector.collectGaugeValues(gaugeConfig);
        gaugeCollector.collectGaugeValues(gaugeConfig);
        // then
        verify(logger, times(10)).debug(anyString(), any(Exception.class));
        verify(logger).warn("mbean attribute {} not found: {}", "ccc", "xyz:aaa=bbb");
        verify(logger).warn("mbean attribute {} not found: {}", "ddd", "xyz:aaa=bbb");
    }

    @Test
    public void shouldHandleMBeanAttributeOtherException() throws Exception {
        // given
        GaugeConfig gaugeConfig = ImmutableGaugeConfig.builder()
                .mbeanObjectName("xyz:aaa=bbb")
                .addMbeanAttributes(ImmutableMBeanAttribute.of("ccc", false))
                .addMbeanAttributes(ImmutableMBeanAttribute.of("ddd", false))
                .build();
        when(lazyPlatformMBeanServer.getAttribute(any(ObjectName.class), anyString()))
                .thenThrow(new RuntimeException("A msg"));
        // when
        gaugeCollector.collectGaugeValues(gaugeConfig);
        gaugeCollector.collectGaugeValues(gaugeConfig);
        gaugeCollector.collectGaugeValues(gaugeConfig);
        gaugeCollector.collectGaugeValues(gaugeConfig);
        gaugeCollector.collectGaugeValues(gaugeConfig);
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
                .mbeanObjectName("xyz:aaa=bbb")
                .addMbeanAttributes(ImmutableMBeanAttribute.of("ccc", false))
                .addMbeanAttributes(ImmutableMBeanAttribute.of("ddd", false))
                .build();
        when(lazyPlatformMBeanServer.getAttribute(any(ObjectName.class), anyString()))
                .thenReturn("not a number");
        // when
        gaugeCollector.collectGaugeValues(gaugeConfig);
        gaugeCollector.collectGaugeValues(gaugeConfig);
        gaugeCollector.collectGaugeValues(gaugeConfig);
        gaugeCollector.collectGaugeValues(gaugeConfig);
        gaugeCollector.collectGaugeValues(gaugeConfig);
        // then
        verify(logger).warn("error accessing mbean attribute {} {}: {}", "xyz:aaa=bbb", "ccc",
                "MBean attribute value is not a valid number: \"not a number\"");
        verify(logger).warn("error accessing mbean attribute {} {}: {}", "xyz:aaa=bbb", "ddd",
                "MBean attribute value is not a valid number: \"not a number\"");
    }

    @Test
    public void shouldHandleMBeanAttributeNotANumberOrString() throws Exception {
        // given
        GaugeConfig gaugeConfig = ImmutableGaugeConfig.builder()
                .mbeanObjectName("xyz:aaa=bbb")
                .addMbeanAttributes(ImmutableMBeanAttribute.of("ccc", false))
                .addMbeanAttributes(ImmutableMBeanAttribute.of("ddd", false))
                .build();
        when(lazyPlatformMBeanServer.getAttribute(any(ObjectName.class), anyString()))
                .thenReturn(new Object());
        // when
        gaugeCollector.collectGaugeValues(gaugeConfig);
        gaugeCollector.collectGaugeValues(gaugeConfig);
        gaugeCollector.collectGaugeValues(gaugeConfig);
        gaugeCollector.collectGaugeValues(gaugeConfig);
        gaugeCollector.collectGaugeValues(gaugeConfig);
        // then
        verify(logger).warn("error accessing mbean attribute {} {}: {}", "xyz:aaa=bbb", "ccc",
                "MBean attribute value is not a number or string");
        verify(logger).warn("error accessing mbean attribute {} {}: {}", "xyz:aaa=bbb", "ddd",
                "MBean attribute value is not a number or string");
    }

    private static void setLogger(Class<?> clazz, Logger logger) throws Exception {
        Field loggerField = clazz.getDeclaredField("logger");
        loggerField.setAccessible(true);
        Field modifiersField = Field.class.getDeclaredField("modifiers");
        modifiersField.setAccessible(true);
        modifiersField.setInt(loggerField, loggerField.getModifiers() & ~Modifier.FINAL);
        loggerField.set(null, logger);
    }
}
