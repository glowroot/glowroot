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
package org.glowroot.agent.plugin.netty;

import com.ning.http.client.AsyncHttpClient;
import com.ning.http.client.Response;
import org.glowroot.agent.it.harness.AppUnderTest;
import org.glowroot.agent.it.harness.Container;
import org.glowroot.agent.it.harness.Containers;
import org.glowroot.wire.api.model.TraceOuterClass.Trace;
import org.junit.After;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import java.io.IOException;
import java.net.ServerSocket;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

public class NettyChannelIT {

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
    public void shouldCaptureTransactionNameWithNormalServletMapping() throws Exception {
        // given
        // when
        Trace trace = container.execute(WithNormalServletMapping.class);
        // then
        assertThat(trace.getHeader().getTransactionName()).isEqualTo("/hello/echo/*");
        List<Trace.Entry> entries = trace.getEntryList();
        assertThat(entries).hasSize(1);
        Trace.Entry entry = entries.get(0);
        assertThat(entry.getMessage()).isEqualTo("spring controller:"
                + " org.glowroot.agent.plugin.spring.ControllerIT$TestController.echo()");
    }


    public static class WithNormalServletMapping extends InvokeSpringControllerInTomcat {
        @Override
        public void executeApp() throws Exception {
            executeApp("/hello/echo/5");
        }
    }


    private static abstract class InvokeSpringControllerInTomcat implements AppUnderTest {

        public void executeApp(String url) throws Exception {
            int port = getAvailablePort();

            new EchoApplication(port);

            AsyncHttpClient asyncHttpClient = new AsyncHttpClient();
            Response r = asyncHttpClient.preparePost("http://localhost:" + port + url).execute().get();
            System.out.println("Got response!" + r.getResponseBody());
            asyncHttpClient.close();

            Thread.sleep(1000);
        }

        private static int getAvailablePort() throws IOException {
            ServerSocket serverSocket = new ServerSocket(0);
            int port = serverSocket.getLocalPort();
            serverSocket.close();
            return port;
        }
    }

}
