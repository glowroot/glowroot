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
package org.glowroot.agent.plugin.struts;

import java.io.File;
import java.io.IOException;
import java.net.ServerSocket;
import java.util.List;

import javax.servlet.Filter;
import javax.servlet.FilterChain;
import javax.servlet.FilterConfig;
import javax.servlet.ServletException;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;

import com.ning.http.client.AsyncHttpClient;
import org.apache.catalina.Context;
import org.apache.catalina.loader.WebappLoader;
import org.apache.catalina.startup.Tomcat;
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

public class StrutsTwoIT {

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
    public void shouldCaptureAction() throws Exception {
        // given
        // when
        Trace trace = container.execute(ExecuteActionInTomcat.class);
        // then
        assertThat(trace.getHeader().getTransactionName()).isEqualTo("HelloAction#helloAction");
        List<Trace.Entry> entries = trace.getEntryList();
        assertThat(entries).hasSize(1);
        Trace.Entry entry = entries.get(0);
        assertThat(entry.getMessage()).isEqualTo("struts action:"
                + " org.glowroot.agent.plugin.struts.StrutsTwoIT$HelloAction.helloAction()");
    }

    public static class HelloAction {
        public void helloAction() {}
    }

    public static class ExecuteActionInTomcat implements AppUnderTest {

        @Override
        public void executeApp() throws Exception {
            int port = getAvailablePort();
            Tomcat tomcat = new Tomcat();
            tomcat.setBaseDir("target/tomcat");
            tomcat.setPort(port);
            Context context =
                    tomcat.addWebapp("", new File("src/test/resources/struts2").getAbsolutePath());

            WebappLoader webappLoader =
                    new WebappLoader(ExecuteActionInTomcat.class.getClassLoader());
            context.setLoader(webappLoader);

            tomcat.start();

            AsyncHttpClient asyncHttpClient = new AsyncHttpClient();
            asyncHttpClient.prepareGet("http://localhost:" + port + "/hello.action").execute()
                    .get();
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

    public static class TransactionMarkingForwardingFilter implements Filter, TransactionMarker {

        private ThreadLocal<ServletRequest> request = new ThreadLocal<ServletRequest>();
        private ThreadLocal<ServletResponse> response = new ThreadLocal<ServletResponse>();
        private ThreadLocal<FilterChain> chain = new ThreadLocal<FilterChain>();

        @Override
        public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
                throws IOException, ServletException {
            this.request.set(request);
            this.response.set(response);
            this.chain.set(chain);
            try {
                transactionMarker();
            } finally {
                this.request.remove();
                this.response.remove();
                this.chain.remove();
            }
        }

        @Override
        public void transactionMarker() throws ServletException, IOException {
            chain.get().doFilter(request.get(), response.get());
        }

        @Override
        public void init(FilterConfig filterConfig) {}

        @Override
        public void destroy() {}
    }
}
