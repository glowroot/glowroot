/*
 * Copyright 2013-2014 the original author or authors.
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
package org.glowroot.tests;

import java.io.File;
import java.util.List;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;
import org.junit.After;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import org.glowroot.Containers;
import org.glowroot.container.AppUnderTest;
import org.glowroot.container.Container;
import org.glowroot.container.TempDirs;
import org.glowroot.container.TraceMarker;
import org.glowroot.container.config.PointcutConfig;
import org.glowroot.container.config.PointcutConfig.MethodModifier;
import org.glowroot.container.trace.Span;
import org.glowroot.container.trace.Trace;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * @author Trask Stalnaker
 * @since 0.5
 */
public class PointcutConfigTest {

    protected static Container container;
    private static File dataDir;

    @BeforeClass
    public static void setUp() throws Exception {
        dataDir = TempDirs.createTempDir("glowroot-test-datadir");
        container = Containers.createWithFileDb(dataDir);
        addPointcutConfigForExecute1();
        addPointcutConfigForExecute1MetricOnly();
        addPointcutConfigForExecuteWithReturn();
        addPointcutConfigForExecuteWithArgs();
        // re-start now with pointcut configs
        container.close();
        container = Containers.createWithFileDb(dataDir);
    }

    @AfterClass
    public static void tearDown() throws Exception {
        container.close();
        TempDirs.deleteRecursively(dataDir);
    }

    @After
    public void afterEachTest() throws Exception {
        container.checkAndReset();
    }

    @Test
    public void shouldExecute1() throws Exception {
        // given
        // when
        container.executeAppUnderTest(ShouldExecute1.class);
        // then
        Trace trace = container.getTraceService().getLastTrace();
        List<Span> spans = container.getTraceService().getSpans(trace.getId());
        assertThat(spans).hasSize(2);
        assertThat(trace.getRootTraceMetric().getName()).isEqualTo("mock trace marker");
        assertThat(trace.getRootTraceMetric().getNestedTraceMetricNames())
                .containsOnly("execute one");
        assertThat(trace.getRootTraceMetric().getNestedTraceMetrics().get(0)
                .getNestedTraceMetricNames()).containsOnly("execute one metric only");
        assertThat(spans.get(1).getMessage().getText()).isEqualTo("execute1() => void");
        assertThat(spans.get(1).getStackTrace()).isNotNull();
    }

    @Test
    public void shouldRenderSpanTextWithReturnValue() throws Exception {
        // given
        // when
        container.executeAppUnderTest(ShouldExecuteWithReturn.class);
        // then
        Trace trace = container.getTraceService().getLastTrace();
        List<Span> spans = container.getTraceService().getSpans(trace.getId());
        assertThat(spans).hasSize(2);
        assertThat(trace.getRootTraceMetric().getName()).isEqualTo("mock trace marker");
        assertThat(trace.getRootTraceMetric().getNestedTraceMetricNames())
                .containsOnly("execute with return");
        assertThat(spans.get(1).getMessage().getText()).isEqualTo("executeWithReturn() => xyz");
    }

    @Test
    public void shouldRenderTraceHeadline() throws Exception {
        // given
        // when
        container.executeAppUnderTest(ShouldExecuteWithArgs.class);
        // then
        Trace trace = container.getTraceService().getLastTrace();
        List<Span> spans = container.getTraceService().getSpans(trace.getId());
        assertThat(trace.getHeadline()).isEqualTo("executeWithArgs(): abc, 123");
        assertThat(trace.getTransactionName()).isEqualTo("Misc / executeWithArgs");
        assertThat(spans).hasSize(1);
        assertThat(trace.getRootTraceMetric().getName()).isEqualTo("execute with args");
        assertThat(trace.getRootTraceMetric().getNestedTraceMetrics()).isEmpty();
        assertThat(spans.get(0).getMessage().getText()).isEqualTo("executeWithArgs(): abc, 123");
    }

    protected static void addPointcutConfigForExecute1() throws Exception {
        PointcutConfig config = new PointcutConfig();
        config.setType("org.glowroot.tests.PointcutConfigTest$Misc");
        config.setMethodName("execute1");
        config.setMethodArgTypes(ImmutableList.<String>of());
        config.setMethodReturnType("");
        config.setMethodModifiers(Lists.newArrayList(MethodModifier.PUBLIC));
        config.setTraceMetric("execute one");
        config.setSpanText("execute1() => {{ret}}");
        config.setSpanStackTraceThresholdMillis(0L);
        container.getConfigService().addPointcutConfig(config);
    }

    protected static void addPointcutConfigForExecute1MetricOnly() throws Exception {
        PointcutConfig config = new PointcutConfig();
        config.setType("org.glowroot.tests.PointcutConfigTest$Misc");
        config.setMethodName("execute1");
        config.setMethodArgTypes(ImmutableList.<String>of());
        config.setMethodReturnType("");
        config.setMethodModifiers(Lists.newArrayList(MethodModifier.PUBLIC));
        config.setTraceMetric("execute one metric only");
        container.getConfigService().addPointcutConfig(config);
    }

    protected static void addPointcutConfigForExecuteWithReturn() throws Exception {
        PointcutConfig config = new PointcutConfig();
        config.setType("org.glowroot.tests.PointcutConfigTest$Misc");
        config.setMethodName("executeWithReturn");
        config.setMethodArgTypes(ImmutableList.<String>of());
        config.setMethodReturnType("");
        config.setMethodModifiers(Lists.newArrayList(MethodModifier.PUBLIC));
        config.setTraceMetric("execute with return");
        config.setSpanText("executeWithReturn() => {{ret}}");
        container.getConfigService().addPointcutConfig(config);
    }

    protected static void addPointcutConfigForExecuteWithArgs() throws Exception {
        PointcutConfig config = new PointcutConfig();
        config.setType("org.glowroot.tests.PointcutConfigTest$Misc");
        config.setMethodName("executeWithArgs");
        config.setMethodArgTypes(ImmutableList.of("java.lang.String", "int"));
        config.setMethodReturnType("void");
        config.setMethodModifiers(Lists.newArrayList(MethodModifier.PUBLIC));
        config.setTraceMetric("execute with args");
        config.setSpanText("executeWithArgs(): {{0}}, {{1}}");
        config.setTransactionName("Misc / {{methodName}}");
        container.getConfigService().addPointcutConfig(config);
    }

    public interface Misc {
        public void execute1();
        public CharSequence executeWithReturn();
        public void executeWithArgs(String one, int two);
    }

    public static class BasicMisc implements Misc {
        @Override
        public void execute1() {}
        @Override
        public String executeWithReturn() {
            return "xyz";
        }
        @Override
        public void executeWithArgs(String one, int two) {}
    }

    public static class ShouldExecute1 implements AppUnderTest, TraceMarker {
        @Override
        public void executeApp() {
            traceMarker();
        }
        @Override
        public void traceMarker() {
            new BasicMisc().execute1();
        }
    }

    public static class ShouldExecuteWithReturn implements AppUnderTest, TraceMarker {
        @Override
        public void executeApp() {
            traceMarker();
        }
        @Override
        public void traceMarker() {
            new BasicMisc().executeWithReturn();
        }
    }

    public static class ShouldExecuteWithArgs implements AppUnderTest {
        @Override
        public void executeApp() {
            new BasicMisc().executeWithArgs("abc", 123);
        }
    }
}
