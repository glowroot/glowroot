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
package org.informantproject.test;

import static org.hamcrest.CoreMatchers.is;
import static org.junit.Assert.assertThat;

import java.util.List;
import java.util.Map;
import java.util.Random;

import org.informantproject.test.api.LevelOne;
import org.informantproject.testkit.AppUnderTest;
import org.informantproject.testkit.Configuration.CoreConfiguration;
import org.informantproject.testkit.InformantContainer;
import org.informantproject.testkit.Trace;
import org.informantproject.testkit.Trace.Span;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import com.google.common.collect.ImmutableMap;

/**
 * @author Trask Stalnaker
 * @since 0.5
 */
public class BasicTest {

    private static final Random random = new Random();
    private static InformantContainer container;

    @BeforeClass
    public static void setUp() throws Exception {
        container = InformantContainer.newInstance();
    }

    @AfterClass
    public static void tearDown() throws Exception {
        container.close();
    }

    @Test
    public void shouldUpdateAndReadBackConfiguration() throws Exception {
        // given
        CoreConfiguration randomCoreConfiguration = makeRandomCoreConfiguration();
        container.getInformant().updateCoreConfiguration(randomCoreConfiguration);
        // when
        CoreConfiguration coreConfiguration = container.getInformant().getCoreConfiguration();
        // then
        assertThat(coreConfiguration, is(randomCoreConfiguration));
    }

    @Test
    public void shouldReadTraces() throws Exception {
        // given
        container.getInformant().setThresholdMillis(0);
        // when
        container.executeAppUnderTest(ShouldGenerateTraceWithNestedSpans.class);
        // then
        List<Trace> traces = container.getInformant().getAllTraces();
        assertThat(traces.size(), is(1));
        assertThat(traces.get(0).getDescription(), is("Level One"));
        assertThat(traces.get(0).getContextMap(), is(mapOf("arg1", "a", "arg2", "b",
                "nested1", mapOf("nestedkey11", "a", "nestedkey12", "b",
                        "subnested1", mapOf("subnestedkey1", "a", "subnestedkey2", "b")),
                "nested2", mapOf("nestedkey21", "a", "nestedkey22", "b"))));
        assertThat(traces.get(0).getSpans().size(), is(3));
        Span span1 = traces.get(0).getSpans().get(0);
        assertThat(span1.getDescription(), is("Level One"));
        Span span2 = traces.get(0).getSpans().get(1);
        assertThat(span2.getDescription(), is("Level Two"));
        assertThat(span2.getContextMap(), is(mapOf("arg1", "ax", "arg2", "bx")));
        Span span3 = traces.get(0).getSpans().get(2);
        assertThat(span3.getDescription(), is("Level Three"));
        assertThat(span3.getContextMap(), is(mapOf("arg1", "axy", "arg2", "bxy")));
    }

    private static CoreConfiguration makeRandomCoreConfiguration() {
        CoreConfiguration randomCoreConfiguration = new CoreConfiguration();
        randomCoreConfiguration.setEnabled(random.nextBoolean());
        randomCoreConfiguration.setThresholdMillis(1000 + random.nextInt(60000));
        randomCoreConfiguration.setStuckThresholdMillis(1000 + random.nextInt(60000));
        randomCoreConfiguration.setStackTraceInitialDelayMillis(1000 + random.nextInt(60000));
        randomCoreConfiguration.setStackTracePeriodMillis(1000 + random.nextInt(60000));
        randomCoreConfiguration.setMaxSpansPerTrace(1000 + random.nextInt(10000));
        randomCoreConfiguration.setRollingSizeMb(100 + random.nextInt(10));
        randomCoreConfiguration.setWarnOnSpanOutsideTrace(random.nextBoolean());
        randomCoreConfiguration.setMetricPeriodMillis(1000 + random.nextInt(60000));
        return randomCoreConfiguration;
    }

    private static Map<String, Object> mapOf(String k1, Object v1, String k2, Object v2) {
        return ImmutableMap.of(k1, v1, k2, v2);
    }

    private static Map<String, Object> mapOf(String k1, Object v1, String k2, Object v2, String k3,
            Object v3) {

        return ImmutableMap.of(k1, v1, k2, v2, k3, v3);
    }

    private static Map<String, Object> mapOf(String k1, Object v1, String k2, Object v2, String k3,
            Object v3, String k4, Object v4) {

        return ImmutableMap.of(k1, v1, k2, v2, k3, v3, k4, v4);
    }

    public static class ShouldGenerateTraceWithNestedSpans implements AppUnderTest {
        public void executeApp() throws InterruptedException {
            new LevelOne().call("a", "b");
        }
    }
}
