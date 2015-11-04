/*
 * Copyright 2011-2015 the original author or authors.
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
import java.io.PrintWriter;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import com.google.common.base.StandardSystemProperty;
import com.ning.http.client.AsyncHttpClient;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.server.handler.AbstractHandler;
import org.junit.After;
import org.junit.AfterClass;
import org.junit.Assume;
import org.junit.BeforeClass;
import org.junit.Test;

import org.glowroot.agent.it.harness.AppUnderTest;
import org.glowroot.agent.it.harness.Container;
import org.glowroot.agent.it.harness.Containers;
import org.glowroot.wire.api.model.TraceOuterClass.Trace;

import static org.assertj.core.api.Assertions.assertThat;

public class JettyHandlerTest {

    private static Container container;

    @BeforeClass
    public static void setUp() throws Exception {
        assumeJdk8();
        container = Containers.getSharedContainer();
    }

    @AfterClass
    public static void tearDown() throws Exception {
        // need null check in case assumption is false in setUp()
        if (container != null) {
            container.close();
        }
    }

    @After
    public void afterEachTest() throws Exception {
        container.checkAndReset();
    }

    @Test
    public void testJettyHandler() throws Exception {
        // given
        // when
        Trace trace = container.execute(ExecuteJettyHandler.class);
        // then
        Trace.Header header = trace.getHeader();
        assertThat(header.getHeadline()).isEqualTo("/hello");
        assertThat(header.getTransactionName()).isEqualTo("/hello");
    }

    private static void assumeJdk8() {
        String javaVersion = StandardSystemProperty.JAVA_VERSION.value();
        Assume.assumeFalse(javaVersion.startsWith("1.6") || javaVersion.startsWith("1.7"));
    }

    public static class ExecuteJettyHandler implements AppUnderTest {

        @Override
        public void executeApp() throws Exception {
            Server server = new Server(0);
            server.setHandler(new HelloHandler());
            server.start();
            int port = ((ServerConnector) server.getConnectors()[0]).getLocalPort();
            AsyncHttpClient asyncHttpClient = new AsyncHttpClient();
            asyncHttpClient.prepareGet("http://localhost:" + port + "/hello").execute().get();
            asyncHttpClient.close();
            server.stop();
        }
    }

    public static class HelloHandler extends AbstractHandler {

        @Override
        public void handle(String target, Request baseRequest, HttpServletRequest request,
                HttpServletResponse response) throws IOException, ServletException {
            PrintWriter out = response.getWriter();
            out.println("Hello!");
            out.close();
        }
    }
}
