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

import javax.servlet.annotation.WebServlet;
import javax.servlet.http.HttpServlet;

import com.ning.http.client.AsyncHttpClient;
import org.apache.catalina.Context;
import org.apache.catalina.deploy.FilterDef;
import org.apache.catalina.deploy.FilterMap;
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
        // given
        // when
        Trace trace = container.execute(InvokeServletInTomcat.class);
        // then
        Trace.Header header = trace.getHeader();
        assertThat(header.getHeadline()).isEqualTo("/hello/5");
        // TODO the transaction name should ideally be /hello/*, but taking safe route for now
        // because servlet could be mapped to another path via web.xml, in future would be good to
        // get use actual servlet mapping, probably need to instrument tomcat/other web containers
        // to capture this
        assertThat(header.getTransactionName()).isEqualTo("/hello/5");
    }

    public static class InvokeServletInTomcat implements AppUnderTest {

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

            FilterDef filterDef = new FilterDef();
            filterDef.setFilterName("transaction-marker");
            filterDef.setFilter(new TransactionMarkingFilter());
            context.addFilterDef(filterDef);

            FilterMap filterMap = new FilterMap();
            filterMap.setFilterName("transaction-marker");
            filterMap.addURLPattern("/*");
            context.addFilterMap(filterMap);

            tomcat.start();
            AsyncHttpClient asyncHttpClient = new AsyncHttpClient();
            asyncHttpClient.prepareGet("http://localhost:" + port + "/hello/5").execute().get();
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

    @WebServlet("/hello/*")
    @SuppressWarnings("serial")
    public static class AnnotatedServlet extends HttpServlet {}
}
