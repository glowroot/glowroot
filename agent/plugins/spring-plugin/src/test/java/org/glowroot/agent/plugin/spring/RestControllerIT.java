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
package org.glowroot.agent.plugin.spring;

import java.util.Iterator;

import org.junit.After;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import org.glowroot.agent.it.harness.AppUnderTest;
import org.glowroot.agent.it.harness.Container;
import org.glowroot.agent.it.harness.Containers;
import org.glowroot.wire.api.model.TraceOuterClass.Trace;

import static org.assertj.core.api.Assertions.assertThat;

public class RestControllerIT {

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
    public void shouldCaptureTransactionNameWithNormalServletMappingHittingRest() throws Exception {
        shouldCaptureTransactionNameWithNormalServletMappingHittingRest("",
                WithNormalServletMappingHittingRest.class);
    }

    @Test
    public void shouldCaptureTransactionNameWithNormalServletMappingHittingAbc() throws Exception {
        shouldCaptureTransactionNameWithNormalServletMappingHittingAbc("",
                WithNormalServletMappingHittingAbc.class);
    }

    @Test
    public void shouldCaptureTransactionNameWithContextPathAndNormalServletMappingHittingRest()
            throws Exception {
        shouldCaptureTransactionNameWithNormalServletMappingHittingRest("/zzz",
                WithContextPathAndNormalServletMappingHittingRest.class);
    }

    @Test
    public void shouldCaptureTransactionNameWithContextPathAndNormalServletMappingHittingAbc()
            throws Exception {
        shouldCaptureTransactionNameWithNormalServletMappingHittingAbc("/zzz",
                WithContextPathAndNormalServletMappingHittingAbc.class);
    }

    private void shouldCaptureTransactionNameWithNormalServletMappingHittingRest(String contextPath,
            Class<? extends AppUnderTest> appUnderTestClass) throws Exception {
        // when
        Trace trace = container.execute(appUnderTestClass, "Web");

        // then
        assertThat(trace.getHeader().getTransactionName()).isEqualTo(contextPath + "/rest");

        Iterator<Trace.Entry> i = trace.getEntryList().iterator();

        Trace.Entry entry = i.next();
        assertThat(entry.getDepth()).isEqualTo(0);
        assertThat(entry.getMessage()).isEqualTo("spring controller:"
                + " org.glowroot.agent.plugin.spring.RestControllerIT$TestRestController.rest()");

        assertThat(i.hasNext()).isFalse();
    }

    private void shouldCaptureTransactionNameWithNormalServletMappingHittingAbc(String contextPath,
            Class<? extends AppUnderTest> appUnderTestClass) throws Exception {
        // when
        Trace trace = container.execute(appUnderTestClass, "Web");

        // then
        assertThat(trace.getHeader().getTransactionName()).isEqualTo(contextPath + "/abc");

        Iterator<Trace.Entry> i = trace.getEntryList().iterator();

        Trace.Entry entry = i.next();
        assertThat(entry.getDepth()).isEqualTo(0);
        assertThat(entry.getMessage()).isEqualTo("spring controller:"
                + " org.glowroot.agent.plugin.spring.RestControllerIT"
                + "$TestRestWithPropertyController.abc()");

        assertThat(i.hasNext()).isFalse();
    }

    public static class WithNormalServletMappingHittingRest extends InvokeSpringControllerInTomcat {
        @Override
        public void executeApp() throws Exception {
            executeApp("webapp1", "", "/rest");
        }
    }

    public static class WithNormalServletMappingHittingAbc extends InvokeSpringControllerInTomcat {
        @Override
        public void executeApp() throws Exception {
            executeApp("webapp1", "", "/abc");
        }
    }

    public static class WithContextPathAndNormalServletMappingHittingRest
            extends InvokeSpringControllerInTomcat {
        @Override
        public void executeApp() throws Exception {
            executeApp("webapp1", "/zzz", "/rest");
        }
    }

    public static class WithContextPathAndNormalServletMappingHittingAbc
            extends InvokeSpringControllerInTomcat {
        @Override
        public void executeApp() throws Exception {
            executeApp("webapp1", "/zzz", "/abc");
        }
    }

    @RestController
    public static class TestRestController {
        @RequestMapping("rest")
        public String rest() {
            return "";
        }
    }

    @RestController
    public static class TestRestWithPropertyController {
        @RequestMapping("${abc.path:abc}")
        public String abc() {
            return "";
        }
    }
}
