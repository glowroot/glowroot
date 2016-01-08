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
import java.util.List;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import com.ning.http.client.AsyncHttpClient;
import org.apache.catalina.Context;
import org.apache.catalina.startup.Tomcat;
import org.apache.jasper.servlet.JspServlet;
import org.apache.jsp.WEB_002dINF.jsps.home_jsp;
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

public class HttpJspPageIT {

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
        // given
        // when
        Trace trace = container.execute(RenderJsp.class);
        // then
        List<Trace.Entry> entries = trace.getEntryList();
        assertThat(entries).hasSize(1);
        Trace.Entry entry = entries.get(0);
        assertThat(entry.getMessage()).isEqualTo("jsp render: /WEB-INF/jsps/home.jsp");
    }

    @Test
    public void shouldCaptureJspRenderingInTomcat() throws Exception {
        // given
        // when
        Trace trace = container.execute(RenderJspInTomcat.class);
        // then
        List<Trace.Entry> entries = trace.getEntryList();
        assertThat(entries).hasSize(1);
        Trace.Entry entry = entries.get(0);
        assertThat(entry.getMessage()).isEqualTo("jsp render: /WEB-INF/jsps/home.jsp");
    }

    public static class RenderJsp implements AppUnderTest, TransactionMarker {
        @Override
        public void executeApp() throws Exception {
            transactionMarker();
        }

        @Override
        public void transactionMarker() throws Exception {
            new home_jsp()._jspService(null, null);
        }
    }

    private static void deleteRecursively(File file) throws IOException {
        if (!file.exists()) {
            throw new IOException("Could not find file to delete: " + file.getCanonicalPath());
        } else if (file.isDirectory()) {
            File[] files = file.listFiles();
            if (files == null) {
                // strangely, listFiles() returns null if an I/O error occurs
                throw new IOException();
            }
            for (File f : files) {
                deleteRecursively(f);
            }
            if (!file.delete()) {
                throw new IOException("Could not delete directory: " + file.getCanonicalPath());
            }
        } else if (!file.delete()) {
            throw new IOException("Could not delete file: " + file.getCanonicalPath());
        }
    }

    public static class RenderJspInTomcat implements AppUnderTest {

        private Context context;
        private Tomcat tomcat;

        @Override
        public void executeApp() throws Exception {

            // clean tomcat directory in order to delete previously compiled jsp page
            File tomcatDir = new File("tomcat.8081");
            if (tomcatDir.exists()) {
                deleteRecursively(tomcatDir);
            }

            tomcat = new Tomcat();
            tomcat.setPort(8081);
            context = tomcat.addContext("", new File("src/test/resources").getAbsolutePath());

            Tomcat.addServlet(context, "hello", new ForwardingServlet());
            context.addServletMapping("/hello", "hello");
            Tomcat.addServlet(context, "jsp", new JspServlet());
            context.addServletMapping("*.jsp", "jsp");

            tomcat.start();
            AsyncHttpClient asyncHttpClient = new AsyncHttpClient();
            asyncHttpClient.prepareGet("http://localhost:8081/hello").execute().get();
            asyncHttpClient.close();
            tomcat.stop();
            tomcat.destroy();
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
                    req.getRequestDispatcher("/WEB-INF/jsps/home.jsp").forward(req, resp);
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
