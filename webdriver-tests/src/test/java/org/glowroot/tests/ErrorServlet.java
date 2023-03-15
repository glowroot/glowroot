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

import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.glowroot.agent.it.harness.AppUnderTest;
import org.mockito.Mockito;

import static org.mockito.Mockito.doReturn;

@SuppressWarnings("serial")
public class ErrorServlet extends HttpServlet implements AppUnderTest {

    @Override
    public void executeApp() throws Exception {
        HttpServletRequest request = Mockito.mock(HttpServletRequest.class);
        doReturn("GET").when(request).getMethod();
        doReturn("/errorservlet").when(request).getRequestURI();

        HttpServletResponse response = Mockito.mock(HttpServletResponse.class);
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
