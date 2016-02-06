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

import java.io.IOException;

import javax.servlet.Filter;
import javax.servlet.FilterChain;
import javax.servlet.FilterConfig;
import javax.servlet.ServletException;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;

import org.glowroot.agent.it.harness.TransactionMarker;

public class TransactionMarkingFilter implements Filter, TransactionMarker {

    private ThreadLocal<ServletRequest> request = new ThreadLocal<ServletRequest>();
    private ThreadLocal<ServletResponse> response = new ThreadLocal<ServletResponse>();
    private ThreadLocal<FilterChain> chain = new ThreadLocal<FilterChain>();

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        this.request.set(request);
        this.response.set(response);
        this.chain.set(chain);
        try {
            transactionMarker();
        } finally {
            this.request.remove();
            this.response.remove();
            this.chain.remove();
        }
    }

    @Override
    public void transactionMarker() throws ServletException, IOException {
        chain.get().doFilter(request.get(), response.get());
    }

    @Override
    public void init(FilterConfig filterConfig) {}

    @Override
    public void destroy() {}
}
