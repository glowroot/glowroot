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
package org.glowroot.agent.plugin.struts;

import java.util.List;

import com.opensymphony.xwork2.mock.MockActionProxy;
import org.apache.struts.action.Action;
import org.apache.struts.mock.MockHttpServletRequest;
import org.apache.struts.mock.MockHttpServletResponse;
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

public class ActionProxyIT {

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
    public void shouldCaptureTransactionNameStruts2() throws Exception {
        // given
        // when
        Trace trace = container.execute(ExecuteActionProxy.class);
        // then
        assertThat(trace.getHeader().getTransactionName()).isEqualTo("CustomAction#doLogin");
        List<Trace.Entry> entries = trace.getEntryList();
        assertThat(entries).hasSize(1);
        Trace.Entry entry = entries.get(0);
        assertThat(entry.getMessage()).isEqualTo("struts action: CustomAction#doLogin");
    }

    @Test
    public void shouldCaptureTransactionNameStruts() throws Exception {
        // given
        // when
        Trace trace = container.execute(ExecuteAction.class);
        // then
        assertThat(trace.getHeader().getTransactionName()).isEqualTo("HelloWorldAction#execute");
        List<Trace.Entry> entries = trace.getEntryList();
        assertThat(entries).hasSize(1);
        Trace.Entry entry = entries.get(0);
        assertThat(entry.getMessage()).isEqualTo("struts action: HelloWorldAction#execute");
    }

    public static class ExecuteAction implements AppUnderTest, TransactionMarker {

        @Override
        public void executeApp() throws Exception {
            transactionMarker();
        }

        @Override
        public void transactionMarker() throws Exception {
            HelloWorldAction helloWorldAction = new HelloWorldAction();
            helloWorldAction.execute(null, null, new MockHttpServletRequest(),
                    new MockHttpServletResponse());
        }
    }

    public static class HelloWorldAction extends Action {}

    public static class ExecuteActionProxy implements AppUnderTest, TransactionMarker {
        @Override
        public void executeApp() throws Exception {
            transactionMarker();
        }

        @Override
        public void transactionMarker() throws Exception {
            MockActionProxy proxy = new MockActionProxy();
            proxy.setMethod("doLogin");
            proxy.setAction(new CustomAction());
            proxy.execute();
        }
    }

    public static class CustomAction {
        public void doLogin() {}
    }
}
