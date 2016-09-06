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
package org.glowroot.agent.plugin.spring;

import java.util.List;

import org.junit.After;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseBody;

import org.glowroot.agent.it.harness.Container;
import org.glowroot.agent.it.harness.Containers;
import org.glowroot.wire.api.model.TraceOuterClass.Trace;

import static org.assertj.core.api.Assertions.assertThat;

public class ControllerIT {

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

    @Test
    public void shouldCaptureTransactionNameWithNormalServletMappingHittingRoot() throws Exception {
        // given
        // when
        Trace trace = container.execute(WithNormalServletMappingHittingRoot.class);
        // then
        assertThat(trace.getHeader().getTransactionName()).isEqualTo("/");
        List<Trace.Entry> entries = trace.getEntryList();
        assertThat(entries).hasSize(1);
        Trace.Entry entry = entries.get(0);
        assertThat(entry.getMessage()).isEqualTo("spring controller:"
                + " org.glowroot.agent.plugin.spring.ControllerIT$RootController.echo()");
    }

    @Test
    public void shouldCaptureTransactionNameWithNestedServletMapping() throws Exception {
        // given
        // when
        Trace trace = container.execute(WithNestedServletMapping.class);
        // then
        assertThat(trace.getHeader().getTransactionName()).isEqualTo("/spring/hello/echo/*");
        List<Trace.Entry> entries = trace.getEntryList();
        assertThat(entries).hasSize(1);
        Trace.Entry entry = entries.get(0);
        assertThat(entry.getMessage()).isEqualTo("spring controller:"
                + " org.glowroot.agent.plugin.spring.ControllerIT$TestController.echo()");
    }

    @Test
    public void shouldCaptureTransactionNameWithNestedServletMappingHittingRoot() throws Exception {
        // given
        // when
        Trace trace = container.execute(WithNestedServletMappingHittingRoot.class);
        // then
        assertThat(trace.getHeader().getTransactionName()).isEqualTo("/spring/");
        List<Trace.Entry> entries = trace.getEntryList();
        assertThat(entries).hasSize(1);
        Trace.Entry entry = entries.get(0);
        assertThat(entry.getMessage()).isEqualTo("spring controller:"
                + " org.glowroot.agent.plugin.spring.ControllerIT$RootController.echo()");
    }

    @Test
    public void shouldCaptureTransactionNameWithLessNormalServletMapping() throws Exception {
        // given
        // when
        Trace trace = container.execute(WithLessNormalServletMapping.class);
        // then
        assertThat(trace.getHeader().getTransactionName()).isEqualTo("/hello/echo/*");
        List<Trace.Entry> entries = trace.getEntryList();
        assertThat(entries).hasSize(1);
        Trace.Entry entry = entries.get(0);
        assertThat(entry.getMessage()).isEqualTo("spring controller:"
                + " org.glowroot.agent.plugin.spring.ControllerIT$TestController.echo()");
    }

    @Test
    public void shouldCaptureTransactionNameWithLessNormalServletMappingHittingRoot()
            throws Exception {
        // given
        // when
        Trace trace = container.execute(WithLessNormalServletMappingHittingRoot.class);
        // then
        assertThat(trace.getHeader().getTransactionName()).isEqualTo("/");
        List<Trace.Entry> entries = trace.getEntryList();
        assertThat(entries).hasSize(1);
        Trace.Entry entry = entries.get(0);
        assertThat(entry.getMessage()).isEqualTo("spring controller:"
                + " org.glowroot.agent.plugin.spring.ControllerIT$RootController.echo()");
    }

    @Test
    public void shouldCaptureAltTransactionName() throws Exception {
        // given
        container.getConfigService().setPluginProperty("spring", "useAltTransactionNaming", true);
        // when
        Trace trace = container.execute(WithNormalServletMapping.class);
        // then
        assertThat(trace.getHeader().getTransactionName()).isEqualTo("TestController#echo");
        List<Trace.Entry> entries = trace.getEntryList();
        assertThat(entries).hasSize(1);
        Trace.Entry entry = entries.get(0);
        assertThat(entry.getMessage()).isEqualTo("spring controller:"
                + " org.glowroot.agent.plugin.spring.ControllerIT$TestController.echo()");
    }

    public static class WithNormalServletMapping extends InvokeSpringControllerInTomcat {
        @Override
        public void executeApp() throws Exception {
            executeApp("webapp1", "/hello/echo/5");
        }
    }

    public static class WithNormalServletMappingHittingRoot extends InvokeSpringControllerInTomcat {
        @Override
        public void executeApp() throws Exception {
            executeApp("webapp1", "/");
        }
    }

    public static class WithNestedServletMapping extends InvokeSpringControllerInTomcat {
        @Override
        public void executeApp() throws Exception {
            executeApp("webapp2", "/spring/hello/echo/5");
        }
    }

    public static class WithNestedServletMappingHittingRoot extends InvokeSpringControllerInTomcat {
        @Override
        public void executeApp() throws Exception {
            executeApp("webapp2", "/spring/");
        }
    }

    public static class WithLessNormalServletMapping extends InvokeSpringControllerInTomcat {
        @Override
        public void executeApp() throws Exception {
            executeApp("webapp3", "/hello/echo/5");
        }
    }

    public static class WithLessNormalServletMappingHittingRoot
            extends InvokeSpringControllerInTomcat {
        @Override
        public void executeApp() throws Exception {
            executeApp("webapp3", "/");
        }
    }

    @Controller
    @RequestMapping("hello")
    public static class TestController {
        @RequestMapping("echo/{id}")
        public @ResponseBody String echo() {
            return "";
        }
    }

    @Controller
    public static class RootController {
        @RequestMapping("")
        public @ResponseBody String echo() {
            return "";
        }
    }
}
