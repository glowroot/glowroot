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
package org.glowroot.agent.plugin.grails;

import java.io.File;
import java.net.ServerSocket;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;

import com.google.common.base.StandardSystemProperty;
import com.google.common.collect.Lists;
import com.ning.http.client.AsyncHttpClient;
import grails.artefact.Artefact;
import grails.boot.config.GrailsAutoConfiguration;
import org.apache.catalina.Context;
import org.apache.catalina.loader.WebappLoader;
import org.apache.catalina.startup.Tomcat;
import org.apache.catalina.webresources.DirResourceSet;
import org.apache.catalina.webresources.StandardRoot;
import org.apache.catalina.webresources.TomcatURLStreamHandlerFactory;
import org.grails.boot.context.web.GrailsAppServletInitializer;
import org.grails.boot.internal.EnableAutoConfiguration;
import org.junit.jupiter.api.*;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.web.WebApplicationInitializer;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

import org.glowroot.agent.it.harness.AppUnderTest;
import org.glowroot.agent.it.harness.Container;
import org.glowroot.agent.it.harness.Containers;
import org.glowroot.wire.api.model.TraceOuterClass.Trace;

import static org.assertj.core.api.Assertions.assertThat;

public class GrailsIT {

    private static Container container;

    @BeforeAll
    public static void setUp() throws Exception {
        Assumptions.assumeFalse(StandardSystemProperty.JAVA_VERSION.value().startsWith("17"));
        Assumptions.assumeFalse(StandardSystemProperty.JAVA_VERSION.value().startsWith("21"));
        container = Containers.create();
    }

    @AfterAll
    public static void tearDown() throws Exception {
        if (container != null) {
            container.close();
        }
    }

    @AfterEach
    public void afterEachTest() throws Exception {
        container.checkAndReset();
    }

    @Test
    public void shouldCaptureNonDefaultAction() throws Exception {
        // when
        Trace trace = container.execute(GetHelloAbc.class, "Web");

        // then
        assertThat(trace.getHeader().getTransactionName()).isEqualTo("Hello#abc");

        Iterator<Trace.Entry> i = trace.getEntryList().iterator();

        Trace.Entry entry = i.next();
        assertThat(entry.getDepth()).isEqualTo(0);
        assertThat(entry.getMessage()).isEqualTo(
                "grails controller: org.glowroot.agent.plugin.grails.HelloController.abc()");

        assertThat(i.hasNext()).isFalse();
    }

    @Test
    public void shouldCaptureDefaultAction() throws Exception {
        // when
        Trace trace = container.execute(GetHello.class, "Web");

        // then
        assertThat(trace.getHeader().getTransactionName()).isEqualTo("Hello#index");

        Iterator<Trace.Entry> i = trace.getEntryList().iterator();

        Trace.Entry entry = i.next();
        assertThat(entry.getDepth()).isEqualTo(0);
        assertThat(entry.getMessage()).isEqualTo(
                "grails controller: org.glowroot.agent.plugin.grails.HelloController.index()");

        assertThat(i.hasNext()).isFalse();
    }

    public static class ApplicationLoader extends GrailsAppServletInitializer
            implements WebApplicationInitializer {

        @Override
        protected SpringApplicationBuilder configure(SpringApplicationBuilder application) {
            application.sources(Application.class);
            return application;
        }
    }

    @Artefact("Application")
    @EnableWebMvc
    @EnableAutoConfiguration
    public static class Application extends GrailsAutoConfiguration {

        @Override
        public Collection<String> packageNames() {
            return Lists.newArrayList("org.glowroot.agent.plugin.grails");
        }

        @Override
        @SuppressWarnings("rawtypes")
        public Collection<Class> classes() {
            Collection<Class> classes = super.classes();
            List<Class> classesInCorrectClassLoader = Lists.newArrayList();
            for (Class clazz : classes) {
                try {
                    classesInCorrectClassLoader.add(Class.forName(clazz.getName(), false,
                            Application.class.getClassLoader()));
                } catch (ClassNotFoundException e) {
                    throw new IllegalStateException(e);
                }
            }
            return classesInCorrectClassLoader;
        }
    }

    public static class GetHelloAbc extends RenderInTomcat {

        @Override
        protected void doTest(int port) throws Exception {
            AsyncHttpClient asyncHttpClient = new AsyncHttpClient();
            int statusCode =
                    asyncHttpClient.prepareGet("http://localhost:" + port + "/hello/abc/xyz")
                            .execute().get().getStatusCode();
            asyncHttpClient.close();
            if (statusCode != 200) {
                throw new IllegalStateException("Unexpected status code: " + statusCode);
            }
        }
    }

    public static class GetHello extends RenderInTomcat {

        @Override
        protected void doTest(int port) throws Exception {
            AsyncHttpClient asyncHttpClient = new AsyncHttpClient();
            int statusCode = asyncHttpClient.prepareGet("http://localhost:" + port + "/hello")
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
            // otherwise tests can fail with "factory already defined"
            TomcatURLStreamHandlerFactory.disable();

            int port = getAvailablePort();
            Tomcat tomcat = new Tomcat();
            tomcat.setBaseDir("target/tomcat");
            tomcat.setPort(port);
            Context context =
                    tomcat.addWebapp("", new File("src/test/resources").getAbsolutePath());

            WebappLoader webappLoader = new WebappLoader(RenderInTomcat.class.getClassLoader());
            context.setLoader(webappLoader);

            // this is needed in order for Tomcat to find annotated classes
            StandardRoot resources = new StandardRoot(context);
            resources.addPreResources(new DirResourceSet(resources, "/WEB-INF/classes", "target/test-classes", "/"));
            context.setResources(resources);

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
