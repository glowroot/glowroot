/*
 * Copyright 2026 the original author or authors.
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

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Iterator;

import javax.xml.transform.Result;
import javax.xml.transform.Source;
import javax.xml.transform.stream.StreamResult;
import javax.xml.transform.stream.StreamSource;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.ws.WebServiceMessage;
import org.springframework.ws.WebServiceMessageFactory;
import org.springframework.ws.client.core.WebServiceTemplate;
import org.springframework.ws.transport.WebServiceConnection;
import org.springframework.ws.transport.WebServiceMessageSender;

import org.glowroot.agent.it.harness.AppUnderTest;
import org.glowroot.agent.it.harness.Container;
import org.glowroot.agent.it.harness.Containers;
import org.glowroot.agent.it.harness.TransactionMarker;
import org.glowroot.wire.api.model.TraceOuterClass.Trace;

import static org.assertj.core.api.Assertions.assertThat;

public class WebServiceTemplatePluginIT {

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
    public void shouldCaptureWebServiceTemplateCall() throws Exception {
        // when
        Trace trace = container.execute(ExecuteWebServiceCall.class);

        // then
        Iterator<Trace.Entry> i = trace.getEntryList().iterator();

        Trace.Entry entry = i.next();
        assertThat(entry.getDepth()).isEqualTo(0);
        assertThat(entry.getMessage())
                .isEqualTo("http client request: POST http://example.test/soap");

        assertThat(i.hasNext()).isFalse();
    }

    public static class ExecuteWebServiceCall implements AppUnderTest, TransactionMarker {
        @Override
        public void executeApp() throws Exception {
            transactionMarker();
        }

        @Override
        public void transactionMarker() throws Exception {
            // Avoid SAAJ/MessageFactory init on modern JDKs — stub factory + sender (#812).
            WebServiceTemplate template = new WebServiceTemplate(new StubMessageFactory());
            template.setMessageSender(new StubMessageSender());
            template.sendSourceAndReceiveToResult("http://example.test/soap",
                    new StreamSource(new ByteArrayInputStream("<request/>".getBytes("UTF-8"))),
                    new StreamResult(new ByteArrayOutputStream()));
        }
    }

    private static class StubMessageFactory implements WebServiceMessageFactory {
        @Override
        public WebServiceMessage createWebServiceMessage() {
            return new StubMessage();
        }
        @Override
        public WebServiceMessage createWebServiceMessage(InputStream inputStream)
                throws IOException {
            return new StubMessage();
        }
    }

    private static class StubMessage implements WebServiceMessage {
        @Override
        public Source getPayloadSource() {
            return new StreamSource(new ByteArrayInputStream("<response/>".getBytes()));
        }
        @Override
        public Result getPayloadResult() {
            return new StreamResult(new ByteArrayOutputStream());
        }
        @Override
        public void writeTo(OutputStream outputStream) throws IOException {}
    }

    private static class StubMessageSender implements WebServiceMessageSender {
        @Override
        public WebServiceConnection createConnection(URI uri) {
            return new StubConnection(uri);
        }
        @Override
        public boolean supports(URI uri) {
            return true;
        }
    }

    private static class StubConnection implements WebServiceConnection {
        private final URI uri;
        StubConnection(URI uri) {
            this.uri = uri;
        }
        @Override
        public void send(WebServiceMessage message) throws IOException {}
        @Override
        public WebServiceMessage receive(WebServiceMessageFactory messageFactory)
                throws IOException {
            return messageFactory.createWebServiceMessage();
        }
        @Override
        public URI getUri() throws URISyntaxException {
            return uri;
        }
        @Override
        public boolean hasError() {
            return false;
        }
        @Override
        public String getErrorMessage() {
            return null;
        }
        @Override
        public void close() {}
    }
}
