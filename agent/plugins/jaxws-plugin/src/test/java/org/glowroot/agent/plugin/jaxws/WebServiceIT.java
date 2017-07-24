/*
 * Copyright 2017 the original author or authors.
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
package org.glowroot.agent.plugin.jaxws;

import java.util.Iterator;

import javax.jws.WebMethod;
import javax.jws.WebParam;
import javax.jws.WebService;

import org.junit.After;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import org.glowroot.agent.it.harness.AppUnderTest;
import org.glowroot.agent.it.harness.Container;
import org.glowroot.agent.it.harness.Containers;
import org.glowroot.wire.api.model.TraceOuterClass.Trace;

import static org.assertj.core.api.Assertions.assertThat;

public class WebServiceIT {

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
        shouldCaptureTransactionNameWithNormalServletMapping("", WithNormalServletMapping.class);
    }

    @Test
    public void shouldCaptureTransactionNameWithNormalServletMappingWithContextPath()
            throws Exception {
        shouldCaptureTransactionNameWithNormalServletMapping("/zzz",
                WithNormalServletMappingWithContextPath.class);
    }

    @Test
    public void shouldCaptureTransactionNameWithNormalServletMappingHittingRoot() throws Exception {
        shouldCaptureTransactionNameWithNormalServletMappingHittingRoot("",
                WithNormalServletMappingHittingRoot.class);
    }

    @Test
    public void shouldCaptureTransactionNameWithNormalServletMappingHittingRootWithContextPath()
            throws Exception {
        shouldCaptureTransactionNameWithNormalServletMappingHittingRoot("/zzz",
                WithNormalServletMappingHittingRootWithContextPath.class);
    }

    @Test
    public void shouldCaptureTransactionNameWithNestedServletMapping() throws Exception {
        shouldCaptureTransactionNameWithNestedServletMapping("", WithNestedServletMapping.class);
    }

    @Test
    public void shouldCaptureTransactionNameWithNestedServletMappingWithContextPath()
            throws Exception {
        shouldCaptureTransactionNameWithNestedServletMapping("/zzz",
                WithNestedServletMappingWithContextPath.class);
    }

    @Test
    public void shouldCaptureTransactionNameWithNestedServletMappingHittingRoot() throws Exception {
        shouldCaptureTransactionNameWithNestedServletMappingHittingRoot("",
                WithNestedServletMappingHittingRoot.class);
    }

    @Test
    public void shouldCaptureTransactionNameWithNestedServletMappingHittingRootWithContextPath()
            throws Exception {
        shouldCaptureTransactionNameWithNestedServletMappingHittingRoot("/zzz",
                WithNestedServletMappingHittingRootWithContextPath.class);
    }

    @Test
    public void shouldCaptureTransactionNameWithLessNormalServletMapping() throws Exception {
        shouldCaptureTransactionNameWithLessNormalServletMapping("",
                WithLessNormalServletMapping.class);
    }

    @Test
    public void shouldCaptureTransactionNameWithLessNormalServletMappingWithContextPath()
            throws Exception {
        shouldCaptureTransactionNameWithLessNormalServletMapping("/zzz",
                WithLessNormalServletMappingWithContextPath.class);
    }

    @Test
    public void shouldCaptureTransactionNameWithLessNormalServletMappingHittingRoot()
            throws Exception {
        shouldCaptureTransactionNameWithLessNormalServletMappingHittingRoot("",
                WithLessNormalServletMappingHittingRoot.class);
    }

    @Test
    public void shouldCaptureTransactionNameWithLessNormalServletMappingHittingRootWithContextPath()
            throws Exception {
        shouldCaptureTransactionNameWithLessNormalServletMappingHittingRoot("/zzz",
                WithLessNormalServletMappingHittingRootWithContextPath.class);
    }

    @Test
    public void shouldCaptureAltTransactionName() throws Exception {
        // given
        container.getConfigService().setPluginProperty("jaxws", "useAltTransactionNaming", true);

        // when
        Trace trace = container.execute(WithNormalServletMapping.class);

        // then
        assertThat(trace.getHeader().getTransactionName()).isEqualTo("HelloService#echo");

        Iterator<Trace.Entry> i = trace.getEntryList().iterator();

        Trace.Entry entry = i.next();
        assertThat(entry.getDepth()).isEqualTo(0);
        assertThat(entry.getMessage()).isEqualTo("jaxws service:"
                + " org.glowroot.agent.plugin.jaxws.WebServiceIT$HelloService.echo()");

        assertThat(i.hasNext()).isFalse();
    }

    private void shouldCaptureTransactionNameWithNormalServletMapping(String contextPath,
            Class<? extends AppUnderTest> appUnderTestClass) throws Exception {
        // when
        Trace trace = container.execute(appUnderTestClass, "Web");

        // then
        assertThat(trace.getHeader().getTransactionName())
                .isEqualTo("POST " + contextPath + "/hello#echo");

        Iterator<Trace.Entry> i = trace.getEntryList().iterator();

        Trace.Entry entry = i.next();
        assertThat(entry.getDepth()).isEqualTo(0);
        assertThat(entry.getMessage()).isEqualTo("jaxws service:"
                + " org.glowroot.agent.plugin.jaxws.WebServiceIT$HelloService.echo()");

        assertThat(i.hasNext()).isFalse();
    }

    private void shouldCaptureTransactionNameWithNormalServletMappingHittingRoot(String contextPath,
            Class<? extends AppUnderTest> appUnderTestClass) throws Exception {
        // when
        Trace trace = container.execute(appUnderTestClass, "Web");

        // then
        assertThat(trace.getHeader().getTransactionName())
                .isEqualTo("POST " + contextPath + "/#echo");

        Iterator<Trace.Entry> i = trace.getEntryList().iterator();

        Trace.Entry entry = i.next();
        assertThat(entry.getDepth()).isEqualTo(0);
        assertThat(entry.getMessage()).isEqualTo("jaxws service:"
                + " org.glowroot.agent.plugin.jaxws.WebServiceIT$RootService.echo()");

        assertThat(i.hasNext()).isFalse();
    }

    private void shouldCaptureTransactionNameWithNestedServletMapping(String contextPath,
            Class<? extends AppUnderTest> appUnderTestClass) throws Exception {
        // when
        Trace trace = container.execute(appUnderTestClass, "Web");

        // then
        assertThat(trace.getHeader().getTransactionName())
                .isEqualTo("POST " + contextPath + "/service/hello#echo");

        Iterator<Trace.Entry> i = trace.getEntryList().iterator();

        Trace.Entry entry = i.next();
        assertThat(entry.getDepth()).isEqualTo(0);
        assertThat(entry.getMessage()).isEqualTo("jaxws service:"
                + " org.glowroot.agent.plugin.jaxws.WebServiceIT$HelloService.echo()");

        assertThat(i.hasNext()).isFalse();
    }

    private void shouldCaptureTransactionNameWithNestedServletMappingHittingRoot(String contextPath,
            Class<? extends AppUnderTest> appUnderTestClass) throws Exception {
        // when
        Trace trace = container.execute(appUnderTestClass, "Web");

        // then
        assertThat(trace.getHeader().getTransactionName())
                .isEqualTo("POST " + contextPath + "/service/#echo");

        Iterator<Trace.Entry> i = trace.getEntryList().iterator();

        Trace.Entry entry = i.next();
        assertThat(entry.getDepth()).isEqualTo(0);
        assertThat(entry.getMessage()).isEqualTo("jaxws service:"
                + " org.glowroot.agent.plugin.jaxws.WebServiceIT$RootService.echo()");

        assertThat(i.hasNext()).isFalse();
    }

    private void shouldCaptureTransactionNameWithLessNormalServletMapping(String contextPath,
            Class<? extends AppUnderTest> appUnderTestClass) throws Exception {
        // when
        Trace trace = container.execute(appUnderTestClass, "Web");

        // then
        if (trace.getHeader().getTransactionName().equals("POST " + contextPath + "#echo")) {
            // Jersey (2.5 and above) doesn't like this "less than normal" servlet mapping, and ends
            // up mapping everything to RootService
            assertThat(trace.getHeader().getTransactionName())
                    .isEqualTo("POST " + contextPath + "#echo");

            Iterator<Trace.Entry> i = trace.getEntryList().iterator();

            Trace.Entry entry = i.next();
            assertThat(entry.getDepth()).isEqualTo(0);
            assertThat(entry.getMessage()).isEqualTo("jaxws service:"
                    + " org.glowroot.agent.plugin.jaxws.WebServiceIT$RootService.echo()");

            assertThat(i.hasNext()).isFalse();
        } else {
            assertThat(trace.getHeader().getTransactionName())
                    .isEqualTo("POST " + contextPath + "/hello#echo");

            Iterator<Trace.Entry> i = trace.getEntryList().iterator();

            Trace.Entry entry = i.next();
            assertThat(entry.getDepth()).isEqualTo(0);
            assertThat(entry.getMessage()).isEqualTo("jaxws service:"
                    + " org.glowroot.agent.plugin.jaxws.WebServiceIT$RootService.echo()");

            assertThat(i.hasNext()).isFalse();
        }
    }

    private void shouldCaptureTransactionNameWithLessNormalServletMappingHittingRoot(
            String contextPath, Class<? extends AppUnderTest> appUnderTestClass) throws Exception {
        // when
        Trace trace = container.execute(appUnderTestClass, "Web");

        // then
        assertThat(trace.getHeader().getTransactionName())
                .isEqualTo("POST " + contextPath + "/#echo");

        Iterator<Trace.Entry> i = trace.getEntryList().iterator();

        Trace.Entry entry = i.next();
        assertThat(entry.getDepth()).isEqualTo(0);
        assertThat(entry.getMessage()).isEqualTo("jaxws service:"
                + " org.glowroot.agent.plugin.jaxws.WebServiceIT$RootService.echo()");

        assertThat(i.hasNext()).isFalse();
    }

    public static class WithNormalServletMapping extends InvokeJaxwsWebServiceInTomcat {
        @Override
        public void executeApp() throws Exception {
            executeApp("webapp1", "", "/hello");
        }
    }

    public static class WithNormalServletMappingWithContextPath
            extends InvokeJaxwsWebServiceInTomcat {
        @Override
        public void executeApp() throws Exception {
            executeApp("webapp1", "/zzz", "/hello");
        }
    }

    public static class WithNormalServletMappingHittingRoot extends InvokeJaxwsWebServiceInTomcat {
        @Override
        public void executeApp() throws Exception {
            executeApp("webapp1", "", "/");
        }
    }

    public static class WithNormalServletMappingHittingRootWithContextPath
            extends InvokeJaxwsWebServiceInTomcat {
        @Override
        public void executeApp() throws Exception {
            executeApp("webapp1", "/zzz", "/");
        }
    }

    public static class WithNestedServletMapping extends InvokeJaxwsWebServiceInTomcat {
        @Override
        public void executeApp() throws Exception {
            executeApp("webapp2", "", "/service/hello");
        }
    }

    public static class WithNestedServletMappingWithContextPath
            extends InvokeJaxwsWebServiceInTomcat {
        @Override
        public void executeApp() throws Exception {
            executeApp("webapp2", "/zzz", "/service/hello");
        }
    }

    public static class WithNestedServletMappingHittingRoot extends InvokeJaxwsWebServiceInTomcat {
        @Override
        public void executeApp() throws Exception {
            executeApp("webapp2", "", "/service/");
        }
    }

    public static class WithNestedServletMappingHittingRootWithContextPath
            extends InvokeJaxwsWebServiceInTomcat {
        @Override
        public void executeApp() throws Exception {
            executeApp("webapp2", "/zzz", "/service/");
        }
    }

    public static class WithLessNormalServletMapping extends InvokeJaxwsWebServiceInTomcat {
        @Override
        public void executeApp() throws Exception {
            executeApp("webapp3", "", "/hello");
        }
    }

    public static class WithLessNormalServletMappingWithContextPath
            extends InvokeJaxwsWebServiceInTomcat {
        @Override
        public void executeApp() throws Exception {
            executeApp("webapp3", "/zzz", "/hello");
        }
    }

    public static class WithLessNormalServletMappingHittingRoot
            extends InvokeJaxwsWebServiceInTomcat {
        @Override
        public void executeApp() throws Exception {
            executeApp("webapp3", "", "/");
        }
    }

    public static class WithLessNormalServletMappingHittingRootWithContextPath
            extends InvokeJaxwsWebServiceInTomcat {
        @Override
        public void executeApp() throws Exception {
            executeApp("webapp3", "/zzz", "/");
        }
    }

    @WebService
    public static class HelloService {
        @WebMethod
        public String echo(@WebParam(name = "param") String msg) {
            return msg;
        }
    }

    @WebService
    public static class RootService {
        @WebMethod
        public String echo(@WebParam(name = "param") String msg) {
            return msg;
        }
    }
}
