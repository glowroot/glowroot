/**
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
package org.glowroot.agent.plugin.httpclient;

import java.io.IOException;
import java.net.ServerSocket;
import java.util.List;

import javax.jws.WebMethod;
import javax.jws.WebParam;
import javax.jws.WebService;
import javax.jws.soap.SOAPBinding;
import javax.xml.ws.Endpoint;

import org.apache.cxf.jaxws.JaxWsProxyFactoryBean;
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

public class CxfClientPluginIT {

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
    public void shouldCaptureHttpGet() throws Exception {
        Trace trace = container.execute(ExecuteSoapRequest.class);
        List<Trace.Entry> entries = trace.getEntryList();
        assertThat(entries).hasSize(1);
        assertThat(entries.get(0).getMessage()).matches(
                "cxf client soap request: http://localhost:\\d+/cxf/helloWorld\\?wsdl hello");
    }

    public static class ExecuteSoapRequest implements AppUnderTest, TransactionMarker {

        private HelloWorld client;

        @Override
        public void executeApp() throws Exception {
            int port = getAvailablePort();
            Endpoint.publish("http://localhost:" + port + "/cxf/helloWorld", new HelloWorldImpl());
            JaxWsProxyFactoryBean factory = new JaxWsProxyFactoryBean();
            factory.setServiceClass(HelloWorld.class);
            factory.setAddress("http://localhost:" + port + "/cxf/helloWorld");
            client = (HelloWorld) factory.create();

            transactionMarker();
        }

        @Override
        public void transactionMarker() throws Exception {
            client.hello("world");
        }

        private int getAvailablePort() throws IOException {
            ServerSocket serverSocket = new ServerSocket(0);
            int port = serverSocket.getLocalPort();
            serverSocket.close();
            return port;
        }
    }

    @WebService
    @SOAPBinding(style = SOAPBinding.Style.RPC)
    public interface HelloWorld {
        @WebMethod
        String hello(@WebParam(name = "text") String text);
    }

    @WebService(
            endpointInterface = "org.glowroot.agent.plugin.httpclient.CxfClientPluginIT$HelloWorld")
    public static class HelloWorldImpl implements HelloWorld {
        @Override
        public String hello(String text) {
            return "Hello " + text;
        }
    }
}
