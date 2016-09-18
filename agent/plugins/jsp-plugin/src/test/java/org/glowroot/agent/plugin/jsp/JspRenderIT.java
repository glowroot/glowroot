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
package org.glowroot.agent.plugin.jsp;

import java.io.File;
import java.io.IOException;
import java.net.ServerSocket;
import java.util.Iterator;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import com.ning.http.client.AsyncHttpClient;
import org.apache.catalina.Context;
import org.apache.catalina.loader.WebappLoader;
import org.apache.catalina.startup.Tomcat;
import org.apache.jasper.servlet.JspServlet;
import org.apache.jsp.WEB_002dINF.jsp.index_jsp;
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

public class JspRenderIT {

    private static Container container;

    @BeforeClass
    public static void setUp() throws Exception {
        // must use javaagent container because org.apache.jasper.servlet.JasperLoader does not
        // delegate to parent (e.g. IsolatedWeavingClassLoader) when loading jsp page classes
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
    public void shouldCaptureJspRendering() throws Exception {
        // when
        Trace trace = container.execute(RenderJsp.class);

        // then
        Iterator<Trace.Entry> i = trace.getEntryList().iterator();

        Trace.Entry entry = i.next();
        assertThat(entry.getDepth()).isEqualTo(0);
        assertThat(entry.getMessage()).isEqualTo("jsp render: /WEB-INF/jsp/index.jsp");

        assertThat(i.hasNext()).isFalse();
    }

    @Test
    public void shouldCaptureJspRenderingInTomcat() throws Exception {
        // when
        Trace trace = container.execute(RenderJspInTomcat.class);

        // then
        Iterator<Trace.Entry> i = trace.getEntryList().iterator();

        Trace.Entry entry = i.next();
        assertThat(entry.getDepth()).isEqualTo(0);
        assertThat(entry.getMessage()).isEqualTo("jsp render: /WEB-INF/jsp/index.jsp");

        assertThat(i.hasNext()).isFalse();
    }

    public static class RenderJsp implements AppUnderTest, TransactionMarker {

        @Override
        public void executeApp() throws Exception {
            transactionMarker();
        }

        @Override
        public void transactionMarker() throws Exception {
            new index_jsp()._jspService(null, null);
        }
    }

    public static class RenderJspInTomcat implements AppUnderTest {

        @Override
        public void executeApp() throws Exception {
            int port = getAvailablePort();
            Tomcat tomcat = new Tomcat();
            tomcat.setBaseDir("target/tomcat");
            tomcat.setPort(port);
            Context context =
                    tomcat.addContext("", new File("src/test/resources").getAbsolutePath());

            WebappLoader webappLoader = new WebappLoader(RenderJspInTomcat.class.getClassLoader());
            context.setLoader(webappLoader);

            Tomcat.addServlet(context, "hello", new ForwardingServlet());
            context.addServletMapping("/hello", "hello");
            Tomcat.addServlet(context, "jsp", new JspServlet());
            context.addServletMapping("*.jsp", "jsp");

            tomcat.start();
            AsyncHttpClient asyncHttpClient = new AsyncHttpClient();
            int statusCode = asyncHttpClient.prepareGet("http://localhost:" + port + "/hello")
                    .execute().get().getStatusCode();
            asyncHttpClient.close();
            if (statusCode != 200) {
                throw new IllegalStateException("Unexpected status code: " + statusCode);
            }
            tomcat.stop();
            tomcat.destroy();
        }

        private static int getAvailablePort() throws Exception {
            ServerSocket serverSocket = new ServerSocket(0);
            int port = serverSocket.getLocalPort();
            serverSocket.close();
            return port;
        }
    }

    @SuppressWarnings("serial")
    private static class ForwardingServlet extends HttpServlet {
        @Override
        protected void service(final HttpServletRequest req, final HttpServletResponse resp)
                throws ServletException, IOException {
            TransactionMarker transactionMarker = new TransactionMarker() {
                @Override
                public void transactionMarker() throws Exception {
                    req.getRequestDispatcher("/WEB-INF/jsp/index.jsp").forward(req, resp);
                }
            };
            try {
                transactionMarker.transactionMarker();
            } catch (Exception e) {
                throw new ServletException(e);
            }
        }
    }
}
