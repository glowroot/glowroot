/**
 * Copyright 2019 the original author or authors.
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
package org.glowroot.agent.plugin.camel;

import java.util.Iterator;
import java.util.List;
import java.util.concurrent.CountDownLatch;

import org.apache.camel.CamelContext;
import org.apache.camel.Exchange;
import org.apache.camel.Processor;
import org.apache.camel.builder.RouteBuilder;
import org.apache.camel.impl.DefaultCamelContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import org.glowroot.agent.it.harness.AppUnderTest;
import org.glowroot.agent.it.harness.Container;
import org.glowroot.agent.it.harness.Containers;
import org.glowroot.agent.it.harness.TraceEntryMarker;
import org.glowroot.wire.api.model.TraceOuterClass.Trace;

import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;

public class CamelPluginIT {

    private static Container container;

    @BeforeAll
    public static void setUp() throws Exception {
        container = Containers.create();
    }

    @AfterAll
    public static void tearDown() throws Exception {
        container.close();
    }

    @AfterEach
    public void afterEachTest() throws Exception {
        container.checkAndReset();
    }

    @Test
    public void shouldRoute() throws Exception {
        // when
        Trace trace = container.execute(Route.class);

        // then
        List<Trace.Timer> nestedTimers =
                trace.getHeader().getMainThreadRootTimer().getChildTimerList();
        assertThat(nestedTimers).hasSize(1);
        assertThat(nestedTimers.get(0).getName()).isEqualTo("mock trace entry marker");

        Iterator<Trace.Entry> i = trace.getEntryList().iterator();

        Trace.Entry entry = i.next();
        assertThat(entry.getDepth()).isEqualTo(0);
        assertThat(entry.getMessage()).isEqualTo("trace entry marker / CreateTraceEntry");

        entry = i.next();
        assertThat(entry.getDepth()).isEqualTo(0);
        assertThat(entry.getMessage()).isEqualTo("trace entry marker / CreateTraceEntry");

        assertThat(i.hasNext()).isFalse();
    }

    public static class Route implements AppUnderTest {

        private static CountDownLatch latch;

        @Override
        public void executeApp() throws Exception {

            latch = new CountDownLatch(1);

            CamelContext context = new DefaultCamelContext();
            context.addRoutes(new RouteBuilder() {
                @Override
                public void configure() {
                    from("file:src/test/data?noop=true")
                            .process(new Processor() {
                                @Override
                                public void process(Exchange exchange) throws Exception {
                                    new CreateTraceEntry().traceEntryMarker();
                                    String body = exchange.getIn().getBody(String.class);
                                    exchange.getIn().setBody(body + ".");
                                }
                            })
                            .process(new Processor() {
                                @Override
                                public void process(Exchange exchange) throws Exception {
                                    new CreateTraceEntry().traceEntryMarker();
                                    String body = exchange.getIn().getBody(String.class);
                                    exchange.getIn().setBody(body + ".");
                                }
                            })
                            .to("file://target/test");
                    from("file://target/test").bean(new SomeBean());
                }
            });
            context.start();
            if (!latch.await(10, SECONDS)) {
                throw new Exception("Message never received");
            }
            context.stop();
        }

        public static class SomeBean {
            public void someMethod(@SuppressWarnings("unused") String body) {
                latch.countDown();
            }
        }
    }

    private static class CreateTraceEntry implements TraceEntryMarker {

        @Override
        public void traceEntryMarker() {
            try {
                MILLISECONDS.sleep(100);
            } catch (InterruptedException e) {
            }
        }
    }
}
