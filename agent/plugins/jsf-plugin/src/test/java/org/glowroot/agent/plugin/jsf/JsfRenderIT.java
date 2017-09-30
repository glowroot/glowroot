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
package org.glowroot.agent.plugin.jsf;

import java.io.File;
import java.net.ServerSocket;
import java.util.Iterator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.ning.http.client.AsyncHttpClient;
import com.ning.http.client.Response;
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

public class JsfRenderIT {

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
    public void shouldCaptureJsfRendering() throws Exception {
        // when
        Trace trace = container.execute(GetHello.class, "Web");

        // then
        Iterator<Trace.Entry> i = trace.getEntryList().iterator();

        Trace.Entry entry = i.next();
        assertThat(entry.getDepth()).isEqualTo(0);
        assertThat(entry.getMessage()).isEqualTo("jsf render: /hello.xhtml");

        assertThat(i.hasNext()).isFalse();
    }

    @Test
    public void shouldCaptureJsfAction() throws Exception {
        // when
        Trace trace = container.execute(PostHello.class, "Web");

        // then
        Iterator<Trace.Entry> i = trace.getEntryList().iterator();

        Trace.Entry entry = i.next();
        assertThat(entry.getDepth()).isEqualTo(0);
        assertThat(entry.getMessage())
                .isEqualTo("jsf apply request: /hello.xhtml");

        entry = i.next();
        assertThat(entry.getDepth()).isEqualTo(0);
        assertThat(entry.getMessage()).isEqualTo("jsf invoke: #{helloBean.hello}");

        entry = i.next();
        assertThat(entry.getDepth()).isEqualTo(0);
        assertThat(entry.getMessage()).isEqualTo("jsf render: /hello.xhtml");

        assertThat(i.hasNext()).isFalse();
    }

    public static class HelloBean {

        public String getMessage() {
            return "Hello World!";
        }

        public void hello() {}
    }

    public static class GetHello extends RenderJsfInTomcat {

        @Override
        protected void doTest(int port) throws Exception {
            AsyncHttpClient asyncHttpClient = new AsyncHttpClient();
            int statusCode = asyncHttpClient.prepareGet("http://localhost:" + port + "/hello.xhtml")
                    .execute().get().getStatusCode();
            asyncHttpClient.close();
            if (statusCode != 200) {
                throw new IllegalStateException("Unexpected status code: " + statusCode);
            }
        }
    }

    public static class PostHello extends RenderJsfInTomcat {

        @Override
        protected void doTest(int port) throws Exception {
            AsyncHttpClient asyncHttpClient = new AsyncHttpClient();
            Response response = asyncHttpClient
                    .prepareGet("http://localhost:" + port + "/hello.xhtml").execute().get();
            int statusCode = response.getStatusCode();
            if (statusCode != 200) {
                asyncHttpClient.close();
                throw new IllegalStateException("Unexpected status code: " + statusCode);
            }
            String body = response.getResponseBody();
            Matcher matcher =
                    Pattern.compile("action=\"/hello.xhtml;jsessionid=([0-9A-F]+)\"").matcher(body);
            matcher.find();
            String jsessionId = matcher.group(1);
            StringBuilder sb = new StringBuilder();
            matcher = Pattern
                    .compile("<input type=\"hidden\" name=\"([^\"]+)\" value=\"([^\"]+)\" />")
                    .matcher(body);
            matcher.find();
            sb.append(matcher.group(1));
            sb.append("=");
            sb.append(matcher.group(2));
            matcher = Pattern
                    .compile("<input type=\"submit\" name=\"([^\"]+)\" value=\"([^\"]+)\" />")
                    .matcher(body);
            matcher.find();
            sb.append("&");
            sb.append(matcher.group(1));
            sb.append("=");
            sb.append(matcher.group(2));
            matcher = Pattern.compile("name=\"([^\"]+)\" id=\"[^\"]+\" value=\"([^\"]+)\"")
                    .matcher(body);
            matcher.find();
            sb.append("&");
            sb.append(matcher.group(1));
            sb.append("=");
            sb.append(matcher.group(2));
            String postBody = sb.toString().replace(":", "%3A");
            response = asyncHttpClient
                    .preparePost(
                            "http://localhost:" + port + "/hello.xhtml;jsessionid=" + jsessionId)
                    .setHeader("Content-Type", "application/x-www-form-urlencoded")
                    .setBody(postBody)
                    .execute()
                    .get();
            statusCode = response.getStatusCode();
            asyncHttpClient.close();
            if (statusCode != 200) {
                throw new IllegalStateException("Unexpected status code: " + statusCode);
            }
            // sleep a bit to make sure the "last trace" is not the first http request from above
            Thread.sleep(200);
        }
    }

    public abstract static class RenderJsfInTomcat implements AppUnderTest {

        @Override
        public void executeApp() throws Exception {
            int port = getAvailablePort();
            Tomcat tomcat = new Tomcat();
            tomcat.setBaseDir("target/tomcat");
            tomcat.setPort(port);
            Context context =
                    tomcat.addWebapp("", new File("src/test/resources").getAbsolutePath());

            WebappLoader webappLoader = new WebappLoader(RenderJsfInTomcat.class.getClassLoader());
            context.setLoader(webappLoader);

            tomcat.start();

            doTest(port);

            tomcat.stop();
            tomcat.destroy();
        }

        protected abstract void doTest(int port) throws Exception;

        private static int getAvailablePort() throws Exception {
            ServerSocket serverSocket = new ServerSocket(0);
            int port = serverSocket.getLocalPort();
            serverSocket.close();
            return port;
        }
    }
}
