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
import com.sun.net.httpserver.HttpHandler;

import org.glowroot.agent.it.harness.AppUnderTest;

@SuppressWarnings("restriction")
class TestHandler implements HttpHandler, AppUnderTest {

    @Override
    public void executeApp() throws Exception {
        MockHttpExchange exchange = new MockHttpExchange("GET", "/testhandler");
        before(exchange);
        handle(exchange);
    }

    // hook for subclasses
    protected void before(@SuppressWarnings("unused") HttpExchange exchange) {}

    @Override
    public void handle(HttpExchange exchange) throws IOException {}
}
