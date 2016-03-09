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

import java.io.File;
import java.io.IOException;
import java.net.ServerSocket;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;


import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.container.AsyncResponse;
import javax.ws.rs.container.Suspended;
import javax.ws.rs.core.Response;

import com.ning.http.client.AsyncHttpClient;
import org.apache.catalina.Context;
import org.apache.catalina.loader.WebappLoader;
import org.apache.catalina.startup.Tomcat;
import org.glowroot.agent.it.harness.TransactionMarker;
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
    public void shouldCaptureTransactionNameWithNormalServletMapping() throws Exception {
        // given
        // when
        Trace trace = container.execute(WithNormalServletMapping.class);
        // then
        assertThat(trace.getHeader().getTransactionName()).isEqualTo("GET /hello/*");
        List<Trace.Entry> entries = trace.getEntryList();
        assertThat(entries).hasSize(1);
        Trace.Entry entry = entries.get(0);
        assertThat(entry.getMessage()).isEqualTo("jaxrs resource:"
                + " org.glowroot.agent.plugin.jaxrs.ResourceIT$HelloResource.echo()");
    }

    @Test
    public void shouldCaptureTransactionNameWithNormalServletMappingHittingRoot() throws Exception {
        // given
        // when
        Trace trace = container.execute(WithNormalServletMappingHittingRoot.class);
        // then
        assertThat(trace.getHeader().getTransactionName()).isEqualTo("GET /");
        List<Trace.Entry> entries = trace.getEntryList();
        assertThat(entries).hasSize(1);
        Trace.Entry entry = entries.get(0);
        assertThat(entry.getMessage()).isEqualTo("jaxrs resource:"
                + " org.glowroot.agent.plugin.jaxrs.ResourceIT$RootResource.echo()");
    }

    @Test
    public void shouldCaptureTransactionNameWithNestedServletMapping() throws Exception {
        // given
        // when
        Trace trace = container.execute(WithNestedServletMapping.class);
        // then
        assertThat(trace.getHeader().getTransactionName()).isEqualTo("GET /rest/hello/*");
        List<Trace.Entry> entries = trace.getEntryList();
        assertThat(entries).hasSize(1);
        Trace.Entry entry = entries.get(0);
        assertThat(entry.getMessage()).isEqualTo("jaxrs resource:"
                + " org.glowroot.agent.plugin.jaxrs.ResourceIT$HelloResource.echo()");
    }

    @Test
    public void shouldCaptureTransactionNameWithNestedServletMappingHittingRoot() throws Exception {
        // given
        // when
        Trace trace = container.execute(WithNestedServletMappingHittingRoot.class);
        // then
        assertThat(trace.getHeader().getTransactionName()).isEqualTo("GET /rest/");
        List<Trace.Entry> entries = trace.getEntryList();
        assertThat(entries).hasSize(1);
        Trace.Entry entry = entries.get(0);
        assertThat(entry.getMessage()).isEqualTo("jaxrs resource:"
                + " org.glowroot.agent.plugin.jaxrs.ResourceIT$RootResource.echo()");
    }

    @Test
    public void shouldCaptureTransactionNameWithLessNormalServletMapping() throws Exception {
        // given
        // when
        Trace trace = container.execute(WithLessNormalServletMapping.class);
        // then
        // JAX-RS (at least Jersey implementation) doesn't like this "less than normal" servlet
        // mapping, and ends up mapping everything to RootResource
        assertThat(trace.getHeader().getTransactionName()).isEqualTo("GET /");
        List<Trace.Entry> entries = trace.getEntryList();
        assertThat(entries).hasSize(1);
        Trace.Entry entry = entries.get(0);
        assertThat(entry.getMessage()).isEqualTo("jaxrs resource:"
                + " org.glowroot.agent.plugin.jaxrs.ResourceIT$RootResource.echo()");
    }

    @Test
    public void shouldCaptureTransactionNameWithLessNormalServletMappingHittingRoot()
            throws Exception {
        // given
        // when
        Trace trace = container.execute(WithLessNormalServletMappingHittingRoot.class);
        // then
        assertThat(trace.getHeader().getTransactionName()).isEqualTo("GET /");
        List<Trace.Entry> entries = trace.getEntryList();
        assertThat(entries).hasSize(1);
        Trace.Entry entry = entries.get(0);
        assertThat(entry.getMessage()).isEqualTo("jaxrs resource:"
                + " org.glowroot.agent.plugin.jaxrs.ResourceIT$RootResource.echo()");
    }

    @Test
    public void shouldCaptureAltTransactionName() throws Exception {
        // given
        container.getConfigService().setPluginProperty("jaxrs", "useAltTransactionNaming", true);
        // when
        Trace trace = container.execute(WithNormalServletMapping.class);
        // then
        assertThat(trace.getHeader().getTransactionName()).isEqualTo("HelloResource#echo");
        List<Trace.Entry> entries = trace.getEntryList();
        assertThat(entries).hasSize(1);
        Trace.Entry entry = entries.get(0);
        assertThat(entry.getMessage()).isEqualTo("jaxrs resource:"
                + " org.glowroot.agent.plugin.jaxrs.ResourceIT$HelloResource.echo()");
    }

    @Test
    public void shouldTransformTransactionToAsyncTransaction() throws Exception {
        // when
        Trace trace = container.execute(WithNormalServletMappingCallSuspended.class);
        // then
        assertThat(trace.getHeader().getTransactionName()).isEqualTo("GET /suspended/*");
        List<Trace.Entry> entries = trace.getEntryList();
        assertThat(entries).hasSize(3);
        Trace.Entry entry = entries.get(0);
        assertThat(entry.getMessage()).isEqualTo("jaxrs resource:"
                + " org.glowroot.agent.plugin.jaxrs.ResourceIT$SuspendedResource.log()");

        entry = entries.get(1);
        assertThat(entry.getMessage()).isEqualTo("auxiliary thread");

        entry = entries.get(2);
        assertThat(entry.getMessage()).isEqualTo("jaxrs async response");
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

    public static class WithNormalServletMappingCallSuspended extends InvokeJaxrsResourceInTomcat {

        @Override
        public void transactionMarker() throws Exception {
            executeApp("webapp4", "/suspended/1");
        }

        @Override
        public void executeApp() throws Exception {
            executeApp("webapp4", "/suspended/1");
        }
    }

    private static abstract class InvokeJaxrsResourceInTomcat implements AppUnderTest, TransactionMarker {

        @Override
        public void transactionMarker() throws Exception {

        }

        public void executeApp(String webapp, String url) throws Exception {
            int port = getAvailablePort();
            Tomcat tomcat = new Tomcat();
            tomcat.setBaseDir("target/tomcat");
            tomcat.setPort(port);
            Context context = tomcat.addWebapp("",
                    new File("src/test/resources/" + webapp).getAbsolutePath());

            WebappLoader webappLoader =
                    new WebappLoader(InvokeJaxrsResourceInTomcat.class.getClassLoader());
            context.setLoader(webappLoader);

            tomcat.start();
            AsyncHttpClient asyncHttpClient = new AsyncHttpClient();
            asyncHttpClient.prepareGet("http://localhost:" + port + url).execute().get();
            asyncHttpClient.close();

            tomcat.stop();
            tomcat.destroy();
        }

        private static int getAvailablePort() throws IOException {
            ServerSocket serverSocket = new ServerSocket(0);
            int port = serverSocket.getLocalPort();
            serverSocket.close();
            return port;
        }
    }

    @Path("hello")
    public static class HelloResource {
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

    @Path("suspended")
    public static class SuspendedResource {

        private final ExecutorService executor = Executors.newCachedThreadPool();

        @GET
        @Path("{param}")
        public void log(@PathParam("param") final String msg, @Suspended final AsyncResponse asyncResponse) {

            executor.execute(new Runnable() {
                @Override
                public void run() {
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
}
