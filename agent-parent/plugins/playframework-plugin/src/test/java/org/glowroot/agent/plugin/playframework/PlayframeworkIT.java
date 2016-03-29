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
package org.glowroot.agent.plugin.playframework;

import com.google.common.base.StandardSystemProperty;
import com.google.common.collect.ImmutableList;
import com.ning.http.client.AsyncHttpClient;
import org.apache.catalina.Context;
import org.apache.catalina.loader.WebappLoader;
import org.apache.catalina.startup.Tomcat;
import org.apache.naming.resources.VirtualDirContext;
import org.glowroot.agent.it.harness.AppUnderTest;
import org.glowroot.agent.it.harness.Container;
import org.glowroot.agent.it.harness.Containers;
import org.glowroot.agent.it.harness.impl.JavaagentContainer;
import org.glowroot.wire.api.model.TraceOuterClass.Trace;
import org.junit.After;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.net.ServerSocket;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

public class PlayframeworkIT {

    private static Container container;

    @BeforeClass
    public static void setUp() throws Exception {
        String javaVersion = StandardSystemProperty.JAVA_VERSION.value();
        if (Containers.useJavaagent()
                && (javaVersion.startsWith("1.6") || javaVersion.startsWith("1.7"))) {
            // grails loads lots of classes
            container = JavaagentContainer
                    .createWithExtraJvmArgs(ImmutableList.of("-XX:MaxPermSize=128m"));
        } else {
            container = Containers.create();
        }
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
    public void shouldCaptureIndexRoute() throws Exception {
        // given
        // when
        Trace trace = container.execute(GetIndex.class);
        // then
        assertThat(trace.getHeader().getTransactionName()).isEqualTo("Application.index");
        List<Trace.Entry> entries = trace.getEntryList();
        assertThat(entries).hasSize(2);
        Trace.Entry entry = entries.get(0);
        assertThat(entry.getMessage()).isEqualTo("playframework action invoker");
    }

    @Test
    public void shouldCaptureApplicationIndexRoute() throws Exception {
        // given
        // when
        Trace trace = container.execute(GetApplicationIndex.class);
        // then
        assertThat(trace.getHeader().getTransactionName()).isEqualTo("Application.index");
        List<Trace.Entry> entries = trace.getEntryList();
        assertThat(entries).hasSize(2);
        Trace.Entry entry = entries.get(0);
        assertThat(entry.getMessage()).isEqualTo("playframework action invoker");
    }

    @Test
    public void shouldCaptureApplicationCalculateRoute() throws Exception {
        // given
        // when
        Trace trace = container.execute(GetApplicationCalculate.class);
        // then
        assertThat(trace.getHeader().getTransactionName()).isEqualTo("Application.calculate");
        List<Trace.Entry> entries = trace.getEntryList();
        assertThat(entries).hasSize(2);
        Trace.Entry entry = entries.get(0);
        assertThat(entry.getMessage()).isEqualTo("playframework action invoker");
        entry = entries.get(1);
        assertThat(entry.getMessage()).isEqualTo("view Application/calculate.html");
    }

    public static class GetIndex extends RenderInTomcat {

        @Override
        protected void doTest(int port) throws Exception {
            AsyncHttpClient asyncHttpClient = new AsyncHttpClient();
            int statusCode =
                    asyncHttpClient.prepareGet("http://localhost:" + port + "/")
                            .execute().get().getStatusCode();
            asyncHttpClient.close();
            if (statusCode != 200) {
                throw new IllegalStateException("Unexpected status code: " + statusCode);
            }
        }
    }

    public static class GetApplicationIndex extends RenderInTomcat {

        @Override
        protected void doTest(int port) throws Exception {
            AsyncHttpClient asyncHttpClient = new AsyncHttpClient();
            int statusCode =
                    asyncHttpClient.prepareGet("http://localhost:" + port + "/application/index")
                            .execute().get().getStatusCode();
            asyncHttpClient.close();
            if (statusCode != 200) {
                throw new IllegalStateException("Unexpected status code: " + statusCode);
            }
        }
    }

    public static class GetApplicationCalculate extends RenderInTomcat {

        @Override
        protected void doTest(int port) throws Exception {
            AsyncHttpClient asyncHttpClient = new AsyncHttpClient();
            int statusCode =
                    asyncHttpClient.prepareGet("http://localhost:" + port + "/application/calculate")
                            .execute().get().getStatusCode();
            asyncHttpClient.close();
            if (statusCode != 200) {
                throw new IllegalStateException("Unexpected status code: " + statusCode);
            }
        }
    }

    public abstract static class RenderInTomcat implements AppUnderTest {

        @Override
        public void executeApp() throws Exception {
            int port = getAvailablePort();
            Tomcat tomcat = new Tomcat();
            tomcat.setBaseDir("target/tomcat");
            tomcat.setPort(port);
            Context context =
                    tomcat.addWebapp("", new File("src/test/resources").getAbsolutePath());

            WebappLoader webappLoader = new WebappLoader(RenderInTomcat.class.getClassLoader());
            // delegate is needed under LocalContainer since using extra resource paths below
            // which causes TransactionMarkingFilter to be loaded by WebappLoader and not by its
            // parent IsolatedWeavingClassLoader and so it is not woven
            webappLoader.setDelegate(true);
            context.setLoader(webappLoader);

            // this is needed in order for Tomcat to find annotated classes
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
}
