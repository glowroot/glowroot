/*
 * Copyright 2016-2017 the original author or authors.
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

import org.glowroot.agent.it.harness.AppUnderTest;
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
    public void shouldCaptureTransactionNameWithSimpleServletMapping() throws Exception {
        shouldCaptureTransactionNameWithSimpleServletMapping("", WithSimpleServletMapping.class);
    }

    @Test
    public void shouldCaptureTransactionNameWithSimpleServletMappingWithContextPath()
            throws Exception {
        shouldCaptureTransactionNameWithSimpleServletMapping("/zzz",
                WithSimpleServletMappingWithContextPath.class);
    }

    @Test
    public void shouldCaptureTransactionNameWithNormalServletMapping() throws Exception {
        shouldCaptureTransactionNameWithNormalServletMapping("", WithNormalServletMapping.class);
    }

    @Test
    public void shouldCaptureTransactionNameWithNormalServletMappingWithContextPath()
            throws Exception {
        shouldCaptureTransactionNameWithNormalServletMapping("/zzz",
                WithNormalServletMappingWithContextPath.class);
    }

    @Test
    public void shouldCaptureTransactionNameWithNormalServletMappingHittingRoot() throws Exception {
        shouldCaptureTransactionNameWithNormalServletMappingHittingRoot("",
                WithNormalServletMappingHittingRoot.class);
    }

    @Test
    public void shouldCaptureTransactionNameWithNormalServletMappingHittingRootWithContextPath()
            throws Exception {
        shouldCaptureTransactionNameWithNormalServletMappingHittingRoot("/zzz",
                WithNormalServletMappingHittingRootWithContextPath.class);
    }

    @Test
    public void shouldCaptureTransactionNameWithNestedServletMapping() throws Exception {
        shouldCaptureTransactionNameWithNestedServletMapping("", WithNestedServletMapping.class);
    }

    @Test
    public void shouldCaptureTransactionNameWithNestedServletMappingWithContextPath()
            throws Exception {
        shouldCaptureTransactionNameWithNestedServletMapping("/zzz",
                WithNestedServletMappingWithContextPath.class);
    }

    @Test
    public void shouldCaptureTransactionNameWithNestedServletMappingHittingRoot() throws Exception {
        shouldCaptureTransactionNameWithNestedServletMappingHittingRoot("",
                WithNestedServletMappingHittingRoot.class);
    }

    @Test
    public void shouldCaptureTransactionNameWithNestedServletMappingHittingRootWithContextPath()
            throws Exception {
        shouldCaptureTransactionNameWithNestedServletMappingHittingRoot("/zzz",
                WithNestedServletMappingHittingRootWithContextPath.class);
    }

    @Test
    public void shouldCaptureTransactionNameWithLessNormalServletMapping() throws Exception {
        shouldCaptureTransactionNameWithLessNormalServletMapping("",
                WithLessNormalServletMapping.class);
    }

    @Test
    public void shouldCaptureTransactionNameWithLessNormalServletMappingWithContextPath()
            throws Exception {
        shouldCaptureTransactionNameWithLessNormalServletMapping("/zzz",
                WithLessNormalServletMappingWithContextPath.class);
    }

    @Test
    public void shouldCaptureTransactionNameWithLessNormalServletMappingHittingRoot()
            throws Exception {
        shouldCaptureTransactionNameWithLessNormalServletMappingHittingRoot("",
                WithLessNormalServletMappingHittingRoot.class);
    }

    @Test
    public void shouldCaptureTransactionNameWithLessNormalServletMappingHittingRootWithContextPath()
            throws Exception {
        shouldCaptureTransactionNameWithLessNormalServletMappingHittingRoot("/zzz",
                WithLessNormalServletMappingHittingRootWithContextPath.class);
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

    private void shouldCaptureTransactionNameWithSimpleServletMapping(String contextPath,
            Class<? extends AppUnderTest> appUnderTestClass) throws Exception {
        // when
        Trace trace = container.execute(appUnderTestClass, "Web");

        // then
        assertThat(trace.getHeader().getTransactionName())
                .isEqualTo("GET " + contextPath + "/simple");

        Iterator<Trace.Entry> i = trace.getEntryList().iterator();

        Trace.Entry entry = i.next();
        assertThat(entry.getDepth()).isEqualTo(0);
        assertThat(entry.getMessage()).isEqualTo("jaxrs resource:"
                + " org.glowroot.agent.plugin.jaxrs.ResourceIT$SimpleResource.echo()");

        assertThat(i.hasNext()).isFalse();
    }

    private void shouldCaptureTransactionNameWithNormalServletMapping(String contextPath,
            Class<? extends AppUnderTest> appUnderTestClass) throws Exception {
        // when
        Trace trace = container.execute(appUnderTestClass, "Web");

        // then
        assertThat(trace.getHeader().getTransactionName())
                .isEqualTo("GET " + contextPath + "/hello/*");

        Iterator<Trace.Entry> i = trace.getEntryList().iterator();

        Trace.Entry entry = i.next();
        assertThat(entry.getDepth()).isEqualTo(0);
        assertThat(entry.getMessage()).isEqualTo("jaxrs resource:"
                + " org.glowroot.agent.plugin.jaxrs.ResourceIT$HelloResource.echo()");

        assertThat(i.hasNext()).isFalse();
    }

    private void shouldCaptureTransactionNameWithNormalServletMappingHittingRoot(String contextPath,
            Class<? extends AppUnderTest> appUnderTestClass) throws Exception {
        // when
        Trace trace = container.execute(appUnderTestClass, "Web");

        // then
        assertThat(trace.getHeader().getTransactionName()).isEqualTo("GET " + contextPath + "/");

        Iterator<Trace.Entry> i = trace.getEntryList().iterator();

        Trace.Entry entry = i.next();
        assertThat(entry.getDepth()).isEqualTo(0);
        assertThat(entry.getMessage()).isEqualTo("jaxrs resource:"
                + " org.glowroot.agent.plugin.jaxrs.ResourceIT$RootResource.echo()");

        assertThat(i.hasNext()).isFalse();
    }

    private void shouldCaptureTransactionNameWithNestedServletMapping(String contextPath,
            Class<? extends AppUnderTest> appUnderTestClass) throws Exception {
        // when
        Trace trace = container.execute(appUnderTestClass, "Web");

        // then
        assertThat(trace.getHeader().getTransactionName())
                .isEqualTo("GET " + contextPath + "/rest/hello/*");

        Iterator<Trace.Entry> i = trace.getEntryList().iterator();

        Trace.Entry entry = i.next();
        assertThat(entry.getDepth()).isEqualTo(0);
        assertThat(entry.getMessage()).isEqualTo("jaxrs resource:"
                + " org.glowroot.agent.plugin.jaxrs.ResourceIT$HelloResource.echo()");

        assertThat(i.hasNext()).isFalse();
    }

    private void shouldCaptureTransactionNameWithNestedServletMappingHittingRoot(String contextPath,
            Class<? extends AppUnderTest> appUnderTestClass) throws Exception {
        // when
        Trace trace = container.execute(appUnderTestClass, "Web");

        // then
        assertThat(trace.getHeader().getTransactionName())
                .isEqualTo("GET " + contextPath + "/rest/");

        Iterator<Trace.Entry> i = trace.getEntryList().iterator();

        Trace.Entry entry = i.next();
        assertThat(entry.getDepth()).isEqualTo(0);
        assertThat(entry.getMessage()).isEqualTo("jaxrs resource:"
                + " org.glowroot.agent.plugin.jaxrs.ResourceIT$RootResource.echo()");

        assertThat(i.hasNext()).isFalse();
    }

    private void shouldCaptureTransactionNameWithLessNormalServletMapping(String contextPath,
            Class<? extends AppUnderTest> appUnderTestClass) throws Exception {
        // when
        Trace trace = container.execute(appUnderTestClass, "Web");

        // then
        if (trace.getHeader().getTransactionName().equals("GET " + contextPath + "/")) {
            // Jersey (2.5 and above) doesn't like this "less than normal" servlet mapping, and ends
            // up mapping everything to RootResource
            assertThat(trace.getHeader().getTransactionName())
                    .isEqualTo("GET " + contextPath + "/");

            Iterator<Trace.Entry> i = trace.getEntryList().iterator();

            Trace.Entry entry = i.next();
            assertThat(entry.getDepth()).isEqualTo(0);
            assertThat(entry.getMessage()).isEqualTo("jaxrs resource:"
                    + " org.glowroot.agent.plugin.jaxrs.ResourceIT$RootResource.echo()");

            assertThat(i.hasNext()).isFalse();
        } else {
            assertThat(trace.getHeader().getTransactionName())
                    .isEqualTo("GET " + contextPath + "/hello/*");

            Iterator<Trace.Entry> i = trace.getEntryList().iterator();

            Trace.Entry entry = i.next();
            assertThat(entry.getDepth()).isEqualTo(0);
            assertThat(entry.getMessage()).isEqualTo("jaxrs resource:"
                    + " org.glowroot.agent.plugin.jaxrs.ResourceIT$HelloResource.echo()");

            assertThat(i.hasNext()).isFalse();
        }
    }

    private void shouldCaptureTransactionNameWithLessNormalServletMappingHittingRoot(
            String contextPath, Class<? extends AppUnderTest> appUnderTestClass) throws Exception {
        // when
        Trace trace = container.execute(appUnderTestClass, "Web");

        // then
        assertThat(trace.getHeader().getTransactionName()).isEqualTo("GET " + contextPath + "/");

        Iterator<Trace.Entry> i = trace.getEntryList().iterator();

        Trace.Entry entry = i.next();
        assertThat(entry.getDepth()).isEqualTo(0);
        assertThat(entry.getMessage()).isEqualTo("jaxrs resource:"
                + " org.glowroot.agent.plugin.jaxrs.ResourceIT$RootResource.echo()");

        assertThat(i.hasNext()).isFalse();
    }

    public static class WithSimpleServletMapping extends InvokeJaxrsResourceInTomcat {
        @Override
        public void executeApp() throws Exception {
            executeApp("webapp1", "", "/simple");
        }
    }

    public static class WithSimpleServletMappingWithContextPath
            extends InvokeJaxrsResourceInTomcat {
        @Override
        public void executeApp() throws Exception {
            executeApp("webapp1", "/zzz", "/simple");
        }
    }

    public static class WithNormalServletMapping extends InvokeJaxrsResourceInTomcat {
        @Override
        public void executeApp() throws Exception {
            executeApp("webapp1", "", "/hello/1");
        }
    }

    public static class WithNormalServletMappingWithContextPath
            extends InvokeJaxrsResourceInTomcat {
        @Override
        public void executeApp() throws Exception {
            executeApp("webapp1", "/zzz", "/hello/1");
        }
    }

    public static class WithNormalServletMappingHittingRoot extends InvokeJaxrsResourceInTomcat {
        @Override
        public void executeApp() throws Exception {
            executeApp("webapp1", "", "/");
        }
    }

    public static class WithNormalServletMappingHittingRootWithContextPath
            extends InvokeJaxrsResourceInTomcat {
        @Override
        public void executeApp() throws Exception {
            executeApp("webapp1", "/zzz", "/");
        }
    }

    public static class WithNestedServletMapping extends InvokeJaxrsResourceInTomcat {
        @Override
        public void executeApp() throws Exception {
            executeApp("webapp2", "", "/rest/hello/1");
        }
    }

    public static class WithNestedServletMappingWithContextPath
            extends InvokeJaxrsResourceInTomcat {
        @Override
        public void executeApp() throws Exception {
            executeApp("webapp2", "/zzz", "/rest/hello/1");
        }
    }

    public static class WithNestedServletMappingHittingRoot extends InvokeJaxrsResourceInTomcat {
        @Override
        public void executeApp() throws Exception {
            executeApp("webapp2", "", "/rest/");
        }
    }

    public static class WithNestedServletMappingHittingRootWithContextPath
            extends InvokeJaxrsResourceInTomcat {
        @Override
        public void executeApp() throws Exception {
            executeApp("webapp2", "/zzz", "/rest/");
        }
    }

    public static class WithLessNormalServletMapping extends InvokeJaxrsResourceInTomcat {
        @Override
        public void executeApp() throws Exception {
            executeApp("webapp3", "", "/hello/1");
        }
    }

    public static class WithLessNormalServletMappingWithContextPath
            extends InvokeJaxrsResourceInTomcat {
        @Override
        public void executeApp() throws Exception {
            executeApp("webapp3", "/zzz", "/hello/1");
        }
    }

    public static class WithLessNormalServletMappingHittingRoot
            extends InvokeJaxrsResourceInTomcat {
        @Override
        public void executeApp() throws Exception {
            executeApp("webapp3", "", "/");
        }
    }

    public static class WithLessNormalServletMappingHittingRootWithContextPath
            extends InvokeJaxrsResourceInTomcat {
        @Override
        public void executeApp() throws Exception {
            executeApp("webapp3", "/zzz", "/");
        }
    }

    @Path("simple")
    public static class SimpleResource {
        @GET
        public Response echo() {
            return Response.status(200).entity("hi").build();
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
