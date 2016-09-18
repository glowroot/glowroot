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

import java.util.Iterator;

import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.core.Response;

import org.junit.After;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import org.glowroot.agent.it.harness.Container;
import org.glowroot.agent.it.harness.Containers;
import org.glowroot.wire.api.model.TraceOuterClass.Trace;

import static org.assertj.core.api.Assertions.assertThat;

public class ResourceIT {

    private static Container container;

    @BeforeClass
    public static void setUp() throws Exception {
        container = Containers.create();
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
    public void shouldCaptureTransactionNameWithNormalServletMapping() throws Exception {
        // when
        Trace trace = container.execute(WithNormalServletMapping.class);

        // then
        assertThat(trace.getHeader().getTransactionName()).isEqualTo("GET /hello/*");

        Iterator<Trace.Entry> i = trace.getEntryList().iterator();

        Trace.Entry entry = i.next();
        assertThat(entry.getDepth()).isEqualTo(0);
        assertThat(entry.getMessage()).isEqualTo("jaxrs resource:"
                + " org.glowroot.agent.plugin.jaxrs.ResourceIT$HelloResource.echo()");

        assertThat(i.hasNext()).isFalse();
    }

    @Test
    public void shouldCaptureTransactionNameWithNormalServletMappingHittingRoot() throws Exception {
        // when
        Trace trace = container.execute(WithNormalServletMappingHittingRoot.class);

        // then
        assertThat(trace.getHeader().getTransactionName()).isEqualTo("GET /");

        Iterator<Trace.Entry> i = trace.getEntryList().iterator();

        Trace.Entry entry = i.next();
        assertThat(entry.getDepth()).isEqualTo(0);
        assertThat(entry.getMessage()).isEqualTo("jaxrs resource:"
                + " org.glowroot.agent.plugin.jaxrs.ResourceIT$RootResource.echo()");

        assertThat(i.hasNext()).isFalse();
    }

    @Test
    public void shouldCaptureTransactionNameWithNestedServletMapping() throws Exception {
        // when
        Trace trace = container.execute(WithNestedServletMapping.class);

        // then
        assertThat(trace.getHeader().getTransactionName()).isEqualTo("GET /rest/hello/*");

        Iterator<Trace.Entry> i = trace.getEntryList().iterator();

        Trace.Entry entry = i.next();
        assertThat(entry.getDepth()).isEqualTo(0);
        assertThat(entry.getMessage()).isEqualTo("jaxrs resource:"
                + " org.glowroot.agent.plugin.jaxrs.ResourceIT$HelloResource.echo()");

        assertThat(i.hasNext()).isFalse();
    }

    @Test
    public void shouldCaptureTransactionNameWithNestedServletMappingHittingRoot() throws Exception {
        // when
        Trace trace = container.execute(WithNestedServletMappingHittingRoot.class);

        // then
        assertThat(trace.getHeader().getTransactionName()).isEqualTo("GET /rest/");

        Iterator<Trace.Entry> i = trace.getEntryList().iterator();

        Trace.Entry entry = i.next();
        assertThat(entry.getDepth()).isEqualTo(0);
        assertThat(entry.getMessage()).isEqualTo("jaxrs resource:"
                + " org.glowroot.agent.plugin.jaxrs.ResourceIT$RootResource.echo()");

        assertThat(i.hasNext()).isFalse();
    }

    @Test
    public void shouldCaptureTransactionNameWithLessNormalServletMapping() throws Exception {
        // when
        Trace trace = container.execute(WithLessNormalServletMapping.class);

        // then
        if (trace.getHeader().getTransactionName().equals("GET /")) {
            // Jersey (2.5 and above) doesn't like this "less than normal" servlet mapping, and ends
            // up
            // mapping everything to RootResource
            assertThat(trace.getHeader().getTransactionName()).isEqualTo("GET /");

            Iterator<Trace.Entry> i = trace.getEntryList().iterator();

            Trace.Entry entry = i.next();
            assertThat(entry.getDepth()).isEqualTo(0);
            assertThat(entry.getMessage()).isEqualTo("jaxrs resource:"
                    + " org.glowroot.agent.plugin.jaxrs.ResourceIT$RootResource.echo()");

            assertThat(i.hasNext()).isFalse();
        } else {
            assertThat(trace.getHeader().getTransactionName()).isEqualTo("GET /hello/*");

            Iterator<Trace.Entry> i = trace.getEntryList().iterator();

            Trace.Entry entry = i.next();
            assertThat(entry.getDepth()).isEqualTo(0);
            assertThat(entry.getMessage()).isEqualTo("jaxrs resource:"
                    + " org.glowroot.agent.plugin.jaxrs.ResourceIT$HelloResource.echo()");

            assertThat(i.hasNext()).isFalse();
        }
    }

    @Test
    public void shouldCaptureTransactionNameWithLessNormalServletMappingHittingRoot()
            throws Exception {
        // when
        Trace trace = container.execute(WithLessNormalServletMappingHittingRoot.class);

        // then
        assertThat(trace.getHeader().getTransactionName()).isEqualTo("GET /");

        Iterator<Trace.Entry> i = trace.getEntryList().iterator();

        Trace.Entry entry = i.next();
        assertThat(entry.getDepth()).isEqualTo(0);
        assertThat(entry.getMessage()).isEqualTo("jaxrs resource:"
                + " org.glowroot.agent.plugin.jaxrs.ResourceIT$RootResource.echo()");

        assertThat(i.hasNext()).isFalse();
    }

    @Test
    public void shouldCaptureAltTransactionName() throws Exception {
        // given
        container.getConfigService().setPluginProperty("jaxrs", "useAltTransactionNaming", true);

        // when
        Trace trace = container.execute(WithNormalServletMapping.class);

        // then
        assertThat(trace.getHeader().getTransactionName()).isEqualTo("HelloResource#echo");

        Iterator<Trace.Entry> i = trace.getEntryList().iterator();

        Trace.Entry entry = i.next();
        assertThat(entry.getDepth()).isEqualTo(0);
        assertThat(entry.getMessage()).isEqualTo("jaxrs resource:"
                + " org.glowroot.agent.plugin.jaxrs.ResourceIT$HelloResource.echo()");

        assertThat(i.hasNext()).isFalse();
    }

    public static class WithNormalServletMapping extends InvokeJaxrsResourceInTomcat {
        @Override
        public void executeApp() throws Exception {
            executeApp("webapp1", "/hello/1");
        }
    }

    public static class WithNormalServletMappingHittingRoot extends InvokeJaxrsResourceInTomcat {
        @Override
        public void executeApp() throws Exception {
            executeApp("webapp1", "/");
        }
    }

    public static class WithNestedServletMapping extends InvokeJaxrsResourceInTomcat {
        @Override
        public void executeApp() throws Exception {
            executeApp("webapp2", "/rest/hello/1");
        }
    }

    public static class WithNestedServletMappingHittingRoot extends InvokeJaxrsResourceInTomcat {
        @Override
        public void executeApp() throws Exception {
            executeApp("webapp2", "/rest/");
        }
    }

    public static class WithLessNormalServletMapping extends InvokeJaxrsResourceInTomcat {
        @Override
        public void executeApp() throws Exception {
            executeApp("webapp3", "/hello/1");
        }
    }

    public static class WithLessNormalServletMappingHittingRoot
            extends InvokeJaxrsResourceInTomcat {
        @Override
        public void executeApp() throws Exception {
            executeApp("webapp3", "/");
        }
    }

    @Path("hello")
    public static class HelloResource {
        @GET
        @Path("{param}")
        public Response echo(@PathParam("param") String msg) {
            return Response.status(200).entity(msg).build();
        }
    }

    @Path("/")
    public static class RootResource {
        @GET
        public Response echo() {
            return Response.status(200).build();
        }
    }
}
