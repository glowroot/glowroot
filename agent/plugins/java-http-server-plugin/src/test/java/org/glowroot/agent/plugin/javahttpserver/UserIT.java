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
package org.glowroot.agent.plugin.javahttpserver;

import java.io.IOException;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpPrincipal;
import org.junit.After;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import org.glowroot.agent.it.harness.Container;
import org.glowroot.agent.it.harness.Containers;
import org.glowroot.wire.api.model.TraceOuterClass.Trace;

import static org.assertj.core.api.Assertions.assertThat;

@SuppressWarnings("restriction")
public class UserIT {

    private static final String PRINCIPAL_NAME = "my name is mock";

    private static Container container;

    @BeforeClass
    public static void setUp() throws Exception {
        // tests only work with javaagent container because they need to weave bootstrap classes
        // that implement com.sun.net.httpserver.HttpExchange
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
    public void testHasExchangePrincipal() throws Exception {
        // when
        Trace trace = container.execute(HasExchangePrincipal.class, "Web");
        // then
        assertThat(trace.getHeader().getUser()).isEqualTo(PRINCIPAL_NAME);
    }

    @Test
    public void testHasExchangeWithExceptionOnGetPrincipal() throws Exception {
        // when
        container.execute(HasExchangeWithExceptionOnGetPrincipal.class, "Web");
        // then don't blow up
    }

    public static class HasExchangePrincipal extends TestHandler {
        @Override
        protected void before(HttpExchange exchange) {
            ((MockHttpExchange) exchange)
                    .setPrincipal(new HttpPrincipal(PRINCIPAL_NAME, "my realm"));
        }

        @Override
        public void handle(HttpExchange exchange) {
            // user principal is only captured if app actually uses it
            // (since it may throw exception)
            exchange.getPrincipal();
        }
    }

    public static class HasExchangeWithExceptionOnGetPrincipal extends TestHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            super.handle(new PrincipalThrowingMockHttpExchange("GET", "/testhandler"));
        }
    }

    public static class PrincipalThrowingMockHttpExchange extends MockHttpExchange {
        public PrincipalThrowingMockHttpExchange(String method, String requestURI) {
            super(method, requestURI);
        }

        @Override
        public HttpPrincipal getPrincipal() {
            throw new RuntimeException("Glowroot should not call this directly as it may fail");
        }
    }
}
