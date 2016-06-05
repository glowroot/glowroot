/*
 * Copyright 2012-2016 the original author or authors.
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
package org.glowroot.tests;

import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import org.glowroot.agent.it.harness.AppUnderTest;

@SuppressWarnings("serial")
public class ErrorServlet extends HttpServlet implements AppUnderTest {

    @Override
    public void executeApp() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/errorservlet");
        MockHttpServletResponse response = new MockHttpServletResponse();
        try {
            service((ServletRequest) request, (ServletResponse) response);
        } catch (IllegalStateException e) {
            if (!e.getMessage().equals("xyz")) {
                throw e;
            }
        }
    }

    @Override
    protected void doGet(HttpServletRequest request, HttpServletResponse response) {
        throw new IllegalStateException("xyz");
    }
}
