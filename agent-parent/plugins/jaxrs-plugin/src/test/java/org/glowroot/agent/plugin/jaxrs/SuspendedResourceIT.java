/*
 * Copyright 2016 the original author or authors.
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
package org.glowroot.agent.plugin.jaxrs;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.container.AsyncResponse;
import javax.ws.rs.container.Suspended;
import javax.ws.rs.core.Response;

import org.junit.After;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import org.glowroot.agent.it.harness.Container;
import org.glowroot.agent.it.harness.Containers;
import org.glowroot.agent.it.harness.TraceEntryMarker;
import org.glowroot.wire.api.model.TraceOuterClass.Trace;

import static org.assertj.core.api.Assertions.assertThat;

public class SuspendedResourceIT {

    private static Container container;

    @BeforeClass
    public static void setUp() throws Exception {
        // javaagent is required for Executor.execute() weaving
        container = Containers.createJavaagent();
    }

    @AfterClass
    public static void tearDown() throws Exception {
        container.close();
    }

    @After
    public void afterEachTest() throws Exception {
        container.checkAndReset();
    }

    @Test
    public void shouldCaptureSuspendedResponse() throws Exception {
        // when
        Trace trace = container.execute(WithNormalServletMappingCallSuspended.class);
        // then
        assertThat(trace.getHeader().getTransactionName()).isEqualTo("GET /suspended/*");
        assertThat(trace.getHeader().getAsync()).isTrue();
        List<Trace.Entry> entries = trace.getEntryList();
        assertThat(entries).hasSize(4);
        Trace.Entry entry = entries.get(0);
        assertThat(entry.getMessage()).isEqualTo("jaxrs resource:"
                + " org.glowroot.agent.plugin.jaxrs.SuspendedResourceIT$SuspendedResource.log()");

        entry = entries.get(1);
        assertThat(entry.getMessage()).isEqualTo("auxiliary thread");

        entry = entries.get(2);
        assertThat(entry.getMessage()).isEqualTo("trace entry marker / CreateTraceEntry");

        entry = entries.get(3);
        assertThat(entry.getMessage()).isEqualTo("jaxrs async response");
    }

    public static class WithNormalServletMappingCallSuspended extends InvokeJaxrsResourceInTomcat {
        @Override
        public void executeApp() throws Exception {
            executeApp("webapp1", "/suspended/1");
        }
    }

    @Path("suspended")
    public static class SuspendedResource {

        private final ExecutorService executor = Executors.newCachedThreadPool();

        @GET
        @Path("{param}")
        public void log(@PathParam("param") final String msg,
                @Suspended final AsyncResponse asyncResponse) {

            executor.execute(new Runnable() {
                @Override
                public void run() {
                    new CreateTraceEntry().traceEntryMarker();
                    asyncResponse.resume(Response.status(200).entity(msg).build());
                }
            });

            executor.shutdown();

            try {
                Thread.sleep(200);
            } catch (InterruptedException e) {
                // ignore
            }
        }
    }

    private static class CreateTraceEntry implements TraceEntryMarker {

        @Override
        public void traceEntryMarker() {
            try {
                Thread.sleep(10);
            } catch (InterruptedException e) {
            }
        }
    }
}
