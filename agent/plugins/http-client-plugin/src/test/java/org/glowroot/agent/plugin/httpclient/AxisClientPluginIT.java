/**
 * Copyright 2018 the original author or authors.
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

import java.net.ServerSocket;
import java.net.URL;
import java.util.Iterator;

import javax.jws.WebMethod;
import javax.jws.WebService;
import javax.jws.soap.SOAPBinding;
import javax.xml.namespace.QName;
import javax.xml.ws.Endpoint;

import org.apache.axis.client.Call;
import org.apache.axis.client.Service;
import org.apache.cxf.jaxws.JaxWsProxyFactoryBean;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import org.glowroot.agent.it.harness.AppUnderTest;
import org.glowroot.agent.it.harness.Container;
import org.glowroot.agent.it.harness.Containers;
import org.glowroot.agent.it.harness.TransactionMarker;
import org.glowroot.wire.api.model.TraceOuterClass.Trace;

import static org.assertj.core.api.Assertions.assertThat;

public class AxisClientPluginIT {

    private static Container container;

    @BeforeAll
    public static void setUp() throws Exception {
        container = Containers.create();
    }

    @AfterAll
    public static void tearDown() throws Exception {
        container.close();
    }

    @AfterEach
    public void afterEachTest() throws Exception {
        container.checkAndReset();
    }

    @Test
    public void shouldCaptureAxisCall() throws Exception {
        // when
        Trace trace = container.execute(ExecuteSoapRequest.class);

        // then
        Iterator<Trace.Entry> i = trace.getEntryList().iterator();

        Trace.Entry entry = i.next();
        assertThat(entry.getDepth()).isEqualTo(0);
        assertThat(entry.getMessage()).matches("http client request:"
                + " POST http://localhost:\\d+/cxf/helloWorld");

        assertThat(i.hasNext()).isFalse();
    }

    public static class ExecuteSoapRequest implements AppUnderTest, TransactionMarker {

        private int port;

        @Override
        public void executeApp() throws Exception {
            port = getAvailablePort();
            Endpoint.publish("http://localhost:" + port + "/cxf/helloWorld", new HelloWorldImpl());
            JaxWsProxyFactoryBean factory = new JaxWsProxyFactoryBean();
            factory.setServiceClass(HelloWorld.class);
            factory.setAddress("http://localhost:" + port + "/cxf/helloWorld");

            transactionMarker();
        }

        @Override
        public void transactionMarker() throws Exception {
            String endpoint = "http://localhost:" + port + "/cxf/helloWorld";

            Service service = new Service();
            Call call = (Call) service.createCall();

            call.setTargetEndpointAddress(new URL(endpoint));
            call.setPortTypeName(
                    new QName("http://httpclient.plugin.agent.glowroot.org/", "HelloWorld"));
            call.setOperationName(
                    new QName("http://httpclient.plugin.agent.glowroot.org/", "hello"));

            call.invoke(new Object[0]);
        }

        private int getAvailablePort() throws Exception {
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
        String hello();
    }

    @WebService(endpointInterface = "org.glowroot.agent.plugin.httpclient.AxisClientPluginIT"
            + "$HelloWorld")
    public static class HelloWorldImpl implements HelloWorld {
        @Override
        public String hello() {
            return "Hello!";
        }
    }
}
