/*
 * Copyright 2015-2017 the original author or authors.
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

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.List;

import javax.management.AttributeNotFoundException;
import javax.management.InstanceNotFoundException;
import javax.management.MalformedObjectNameException;
import javax.management.ObjectName;

import com.google.common.base.Ticker;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.slf4j.Logger;

import org.glowroot.agent.collector.Collector;
import org.glowroot.agent.config.AdvancedConfig;
import org.glowroot.agent.config.ConfigService;
import org.glowroot.agent.config.GaugeConfig;
import org.glowroot.agent.config.ImmutableAdvancedConfig;
import org.glowroot.agent.config.ImmutableGaugeConfig;
import org.glowroot.agent.config.ImmutableMBeanAttribute;
import org.glowroot.agent.util.LazyPlatformMBeanServer;
import org.glowroot.common.util.Clock;
import org.glowroot.wire.api.model.CollectorServiceOuterClass.GaugeValue;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

public class GaugeCollectorTest {

    private GaugeCollector gaugeCollector;
    private LazyPlatformMBeanServer lazyPlatformMBeanServer;
    private Clock clock;
    private Ticker ticker;
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
        ticker = mock(Ticker.class);
        logger = mock(Logger.class);
        setLogger(GaugeCollector.class, logger);
        gaugeCollector = new GaugeCollector(configService, collector, lazyPlatformMBeanServer,
                clock, ticker);
    }

    @After
    public void afterEachTest() {
        verifyNoMoreInteractions(logger);
    }

    @Test
    public void shouldCaptureNonCounterGauge() throws Exception {
        // given
        GaugeConfig gaugeConfig = ImmutableGaugeConfig.builder()
                .mbeanObjectName("test:aaa=bbb")
                .addMbeanAttributes(ImmutableMBeanAttribute.of("ccc", false))
                .build();
        when(lazyPlatformMBeanServer.getAttribute(any(ObjectName.class), anyString()))
                .thenReturn(555);

        // when
        List<GaugeValue> gaugeValues = gaugeCollector.collectGaugeValues(gaugeConfig);

        // then
        assertThat(gaugeValues).hasSize(1);
        assertThat(gaugeValues.get(0).getValue()).isEqualTo(555);
        assertThat(gaugeValues.get(0).getWeight()).isEqualTo(1);
    }

    @Test
    public void shouldNotCaptureCounterGauge() throws Exception {
        // given
        GaugeConfig gaugeConfig = ImmutableGaugeConfig.builder()
                .mbeanObjectName("test:aaa=bbb")
                .addMbeanAttributes(ImmutableMBeanAttribute.of("ccc", true))
                .build();
        when(lazyPlatformMBeanServer.getAttribute(any(ObjectName.class), anyString()))
                .thenReturn(555);

        // need to execute run() once in order to initialize internal priorRawCounterValues map
        gaugeCollector.run();

        // when
        List<GaugeValue> gaugeValues = gaugeCollector.collectGaugeValues(gaugeConfig);

        // then
        assertThat(gaugeValues).isEmpty();
    }

    @Test
    public void shouldCaptureCounterGauge() throws Exception {
        // given
        GaugeConfig gaugeConfig = ImmutableGaugeConfig.builder()
                .mbeanObjectName("test:aaa=bbb")
                .addMbeanAttributes(ImmutableMBeanAttribute.of("ccc", true))
                .build();
        when(lazyPlatformMBeanServer.getAttribute(any(ObjectName.class), anyString()))
                .thenReturn(555, 565);
        when(ticker.read()).thenReturn(SECONDS.toNanos(1), SECONDS.toNanos(3));

        // need to execute run() once in order to initialize internal priorRawCounterValues map
        gaugeCollector.run();

        // when
        gaugeCollector.collectGaugeValues(gaugeConfig);
        List<GaugeValue> gaugeValues = gaugeCollector.collectGaugeValues(gaugeConfig);

        // then
        assertThat(gaugeValues).hasSize(1);
        assertThat(gaugeValues.get(0).getValue()).isEqualTo(5); // 5 "units" per second
        assertThat(gaugeValues.get(0).getWeight()).isEqualTo(SECONDS.toNanos(2));
    }

    @Test
    public void shouldHandleInvalidMBeanObjectName() throws Exception {
        // given
        GaugeConfig gaugeConfig = ImmutableGaugeConfig.builder()
                .mbeanObjectName("invalid mbean object name")
                .build();

        // when
        List<GaugeValue> gaugeValues = gaugeCollector.collectGaugeValues(gaugeConfig);

        // then
        assertThat(gaugeValues).isEmpty();
        verify(logger).debug(anyString(), any(Exception.class));
        verify(logger).warn(eq("error accessing mbean: {}"), eq("invalid mbean object name"),
                any(MalformedObjectNameException.class));
    }

    @Test
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
        verify(logger).debug(nullable(String.class), any(Exception.class));
    }

    @Test
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
        verify(logger).debug(nullable(String.class), any(Exception.class));
        verify(logger).warn("mbean not {}: {}", "found", "xyz:aaa=bbb");
    }

    @Test
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
        verify(logger, times(5)).debug(nullable(String.class), any(Exception.class));
        verify(logger)
                .warn("mbean not {}: {} (waited {} seconds after jvm startup before logging this"
                        + " warning to allow time for mbean registration - this wait time can be"
                        + " changed under Configuration > Advanced)", "found", "xyz:aaa=bbb", 60);
    }

    @Test
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
        verify(logger, times(10)).debug(nullable(String.class), any(Exception.class));
        verify(logger).warn("mbean attribute {} not found in {}", "ccc", "xyz:aaa=bbb");
        verify(logger).warn("mbean attribute {} not found in {}", "ddd", "xyz:aaa=bbb");
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
        verify(logger).warn(eq("error accessing mbean attribute: {} {}"), eq("xyz:aaa=bbb"),
                eq("ccc"), any(RuntimeException.class));
        verify(logger).warn(eq("error accessing mbean attribute: {} {}"), eq("xyz:aaa=bbb"),
                eq("ddd"), any(RuntimeException.class));
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
