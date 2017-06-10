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
package org.glowroot.agent.plugin.struts;

import java.io.File;
import java.net.ServerSocket;
import java.util.Iterator;

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
import org.glowroot.wire.api.model.TraceOuterClass.Trace;

import static org.assertj.core.api.Assertions.assertThat;

public class StrutsTwoIT {

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
    public void shouldCaptureAction() throws Exception {
        // when
        Trace trace = container.execute(ExecuteActionInTomcat.class, "Web");

        // then
        assertThat(trace.getHeader().getTransactionName()).isEqualTo("HelloAction#helloAction");

        Iterator<Trace.Entry> i = trace.getEntryList().iterator();

        Trace.Entry entry = i.next();
        assertThat(entry.getDepth()).isEqualTo(0);
        assertThat(entry.getMessage()).isEqualTo("struts action:"
                + " org.glowroot.agent.plugin.struts.StrutsTwoIT$HelloAction.helloAction()");

        assertThat(i.hasNext()).isFalse();
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
            String subdir;
            try {
                Class.forName("org.apache.struts2.dispatcher.filter.StrutsPrepareAndExecuteFilter");
                subdir = "struts2.5";
            } catch (ClassNotFoundException e) {
                subdir = "struts2";
            }
            Context context = tomcat.addWebapp("",
                    new File("src/test/resources/" + subdir).getAbsolutePath());

            WebappLoader webappLoader =
                    new WebappLoader(ExecuteActionInTomcat.class.getClassLoader());
            context.setLoader(webappLoader);

            tomcat.start();

            AsyncHttpClient asyncHttpClient = new AsyncHttpClient();
            int statusCode =
                    asyncHttpClient.prepareGet("http://localhost:" + port + "/hello.action")
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
}
