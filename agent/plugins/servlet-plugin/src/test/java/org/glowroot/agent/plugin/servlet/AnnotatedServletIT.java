/*
 * Copyright 2011-2017 the original author or authors.
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

import java.io.IOException;

import javax.servlet.ServletException;
import javax.servlet.annotation.WebServlet;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import com.ning.http.client.AsyncHttpClient;
import org.junit.After;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import org.glowroot.agent.it.harness.Container;
import org.glowroot.agent.it.harness.Containers;
import org.glowroot.wire.api.model.TraceOuterClass.Trace;

import static org.assertj.core.api.Assertions.assertThat;

public class AnnotatedServletIT {

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
    public void testServlet() throws Exception {
        // when
        Trace trace = container.execute(InvokeServlet.class, "Web");

        // then
        Trace.Header header = trace.getHeader();
        assertThat(header.getHeadline()).isEqualTo("/hello/5");
        // TODO the transaction name should ideally be /hello/*, but taking safe route for now
        // because servlet could be mapped to another path via web.xml, in future would be good to
        // get use actual servlet mapping, probably need to instrument tomcat/other web containers
        // to capture this
        assertThat(header.getTransactionName()).isEqualTo("/hello/5");
    }

    @Test
    public void testServletWithContextPath() throws Exception {
        // when
        Trace trace = container.execute(InvokeServletWithContextPath.class, "Web");

        // then
        Trace.Header header = trace.getHeader();
        assertThat(header.getHeadline()).isEqualTo("/zzz/hello/5");
        // TODO the transaction name should ideally be /hello/*, but taking safe route for now
        // because servlet could be mapped to another path via web.xml, in future would be good to
        // get use actual servlet mapping, probably need to instrument tomcat/other web containers
        // to capture this
        assertThat(header.getTransactionName()).isEqualTo("/zzz/hello/5");
    }

    public static class InvokeServlet extends InvokeServletInTomcat {

        public InvokeServlet() {
            super("");
        }

        @Override
        protected void doTest(int port) throws Exception {
            AsyncHttpClient asyncHttpClient = new AsyncHttpClient();
            int statusCode = asyncHttpClient.prepareGet("http://localhost:" + port + "/hello/5")
                    .execute().get().getStatusCode();
            asyncHttpClient.close();
            if (statusCode != 200) {
                throw new IllegalStateException("Unexpected status code: " + statusCode);
            }
        }
    }

    public static class InvokeServletWithContextPath extends InvokeServletInTomcat {

        public InvokeServletWithContextPath() {
            super("/zzz");
        }

        @Override
        protected void doTest(int port) throws Exception {
            AsyncHttpClient asyncHttpClient = new AsyncHttpClient();
            int statusCode = asyncHttpClient.prepareGet("http://localhost:" + port + "/zzz/hello/5")
                    .execute().get().getStatusCode();
            asyncHttpClient.close();
            if (statusCode != 200) {
                throw new IllegalStateException("Unexpected status code: " + statusCode);
            }
        }
    }

    @WebServlet(value = "/hello/*", loadOnStartup = 0)
    @SuppressWarnings("serial")
    public static class AnnotatedServlet extends HttpServlet {
        @Override
        protected void doGet(HttpServletRequest request, HttpServletResponse response)
                throws ServletException, IOException {
            response.getWriter().print("hello");
        }
    }
}
