/*
 * Copyright 2016-2018 the original author or authors.
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

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.glowroot.agent.it.harness.AppUnderTest;

import static java.util.concurrent.TimeUnit.MILLISECONDS;

@SuppressWarnings("serial")
public class SleepServlet extends JdbcServlet implements AppUnderTest {

    @Override
    protected void doGet(HttpServletRequest request, HttpServletResponse response)
            throws ServletException {
        super.doGet(request, response);
        try {
            MILLISECONDS.sleep(Long.MAX_VALUE);
        } catch (InterruptedException e) {
            // ok, intentionally stopped by Container.interruptAppUnderTest()
        }
    }
}
