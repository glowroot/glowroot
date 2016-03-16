/*
 * Copyright 2011-2016 the original author or authors.
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
package org.glowroot.agent.plugin.servlet;

import java.io.File;
import java.io.IOException;
import java.net.ServerSocket;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import javax.servlet.AsyncContext;
import javax.servlet.annotation.WebServlet;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import com.ning.http.client.AsyncHttpClient;
import org.apache.catalina.Context;
import org.apache.catalina.loader.WebappLoader;
import org.apache.catalina.startup.Tomcat;
import org.apache.naming.resources.VirtualDirContext;
import org.junit.After;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import org.glowroot.agent.it.harness.AppUnderTest;
import org.glowroot.agent.it.harness.Container;
import org.glowroot.agent.it.harness.Containers;
import org.glowroot.agent.it.harness.TransactionMarker;
import org.glowroot.wire.api.model.TraceOuterClass.Trace;

import static org.assertj.core.api.Assertions.assertThat;

public class AsyncServletIT {

    private static Container container;

    @BeforeClass
    public static void setUp() throws Exception {
        // async servlet test relies on executor plugin, which only works under javaagent
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
    public void testAsyncServlet() throws Exception {
        // given
        // when
        Trace trace = container.execute(InvokeAsync.class);
        // then
        Trace.Header header = trace.getHeader();
        assertThat(header.getHeadline()).isEqualTo("/async");
        assertThat(header.getTransactionName()).isEqualTo("/async");
        assertThat(header.getEntryCount()).isEqualTo(2);
        List<Trace.Entry> entries = trace.getEntryList();
        assertThat(entries.get(0).getMessage()).isEqualTo("auxiliary thread");
        assertThat(entries.get(1).getMessage()).isEqualTo("trace marker / TraceEntryMarker");
    }

    @Test
    public void testAsyncServlet2() throws Exception {
        // given
        // when
        Trace trace = container.execute(InvokeAsync2.class);
        // then
        Trace.Header header = trace.getHeader();
        assertThat(header.getHeadline()).isEqualTo("/async2");
        assertThat(header.getTransactionName()).isEqualTo("/async2");
        assertThat(header.getEntryCount()).isEqualTo(2);
        List<Trace.Entry> entries = trace.getEntryList();
        assertThat(entries.get(0).getMessage()).isEqualTo("auxiliary thread");
        assertThat(entries.get(1).getMessage()).isEqualTo("trace marker / TraceEntryMarker");
    }

    public static class InvokeAsync extends InvokeServletInTomcat {
        @Override
        protected void doTest(int port) throws Exception {
            AsyncHttpClient asyncHttpClient = new AsyncHttpClient();
            int statusCode = asyncHttpClient.prepareGet("http://localhost:" + port + "/async")
                    .execute().get().getStatusCode();
            asyncHttpClient.close();
            if (statusCode != 200) {
                throw new IllegalStateException("Unexpected status code: " + statusCode);
            }
        }
    }

    public static class InvokeAsync2 extends InvokeServletInTomcat {
        @Override
        protected void doTest(int port) throws Exception {
            AsyncHttpClient asyncHttpClient = new AsyncHttpClient();
            int statusCode = asyncHttpClient.prepareGet("http://localhost:" + port + "/async2")
                    .execute().get().getStatusCode();
            asyncHttpClient.close();
            if (statusCode != 200) {
                throw new IllegalStateException("Unexpected status code: " + statusCode);
            }
        }
    }

    public abstract static class InvokeServletInTomcat implements AppUnderTest {

        @Override
        public void executeApp() throws Exception {
            int port = getAvailablePort();
            Tomcat tomcat = new Tomcat();
            tomcat.setBaseDir("target/tomcat");
            tomcat.setPort(port);
            Context context =
                    tomcat.addWebapp("", new File("src/test/resources").getAbsolutePath());

            WebappLoader webappLoader =
                    new WebappLoader(InvokeServletInTomcat.class.getClassLoader());
            context.setLoader(webappLoader);

            // this is needed in order for Tomcat to find annotated servlet
            VirtualDirContext resources = new VirtualDirContext();
            resources.setExtraResourcePaths("/WEB-INF/classes=target/test-classes");
            context.setResources(resources);

            tomcat.start();

            doTest(port);

            tomcat.stop();
            tomcat.destroy();
        }

        protected abstract void doTest(int port) throws Exception;

        private static int getAvailablePort() throws IOException {
            ServerSocket serverSocket = new ServerSocket(0);
            int port = serverSocket.getLocalPort();
            serverSocket.close();
            return port;
        }
    }

    @WebServlet(value = "/async", asyncSupported = true)
    @SuppressWarnings("serial")
    public static class AsyncServlet extends HttpServlet {

        private final ExecutorService executor = Executors.newSingleThreadExecutor();

        @Override
        public void destroy() {
            executor.shutdownNow();
        }

        @Override
        protected void doGet(HttpServletRequest request, HttpServletResponse response) {
            final AsyncContext asyncContext = request.startAsync();
            asyncContext.start(new Runnable() {
                @Override
                public void run() {
                    try {
                        Thread.sleep(200);
                        new TraceEntryMarker().transactionMarker();
                        asyncContext.getResponse().getWriter().println("async response");
                        asyncContext.complete();
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                }
            });
        }
    }

    @WebServlet(value = "/async2", asyncSupported = true)
    @SuppressWarnings("serial")
    public static class AsyncServlet2 extends HttpServlet {

        private final ExecutorService executor = Executors.newSingleThreadExecutor();

        @Override
        public void destroy() {
            executor.shutdownNow();
        }

        @Override
        protected void doGet(HttpServletRequest request, HttpServletResponse response) {
            final AsyncContext asyncContext = request.startAsync(request, response);
            asyncContext.start(new Runnable() {
                @Override
                public void run() {
                    try {
                        Thread.sleep(200);
                        new TraceEntryMarker().transactionMarker();
                        asyncContext.getResponse().getWriter().println("async response");
                        asyncContext.complete();
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                }
            });
        }
    }

    private static class TraceEntryMarker implements TransactionMarker {
        @Override
        public void transactionMarker() {}
    }
}
