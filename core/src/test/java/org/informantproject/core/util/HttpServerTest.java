/**
 * Copyright 2011-2012 the original author or authors.
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
package org.informantproject.core.util;

import static org.hamcrest.CoreMatchers.is;
import static org.junit.Assert.assertThat;

import java.util.Set;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import com.ning.http.client.AsyncHttpClient;
import com.ning.http.client.AsyncHttpClient.BoundRequestBuilder;
import com.ning.http.client.Response;

/**
 * @author Trask Stalnaker
 * @since 0.5
 */
public class HttpServerTest {

    private Set<Thread> preExistingThreads;
    private HttpServerBase httpServer;
    private AsyncHttpClient asyncHttpClient;

    @Before
    public void before() {
        preExistingThreads = ThreadChecker.currentThreadList();
        httpServer = new EchoHttpServer();
        asyncHttpClient = new AsyncHttpClient();
    }

    @After
    public void after() throws InterruptedException {
        // asyncHttpClient is not part of the system under test, so it can be shut down
        // first before checking for non-daemon threads
        asyncHttpClient.close();
        ThreadChecker.preShutdownNonDaemonThreadCheck(preExistingThreads);
        httpServer.shutdown();
        ThreadChecker.postShutdownThreadCheck(preExistingThreads);
    }

    @Test
    public void shouldReceivePingResponse() throws Exception {
        BoundRequestBuilder request = asyncHttpClient.preparePost("http://localhost:"
                + httpServer.getPort());
        request.setBody("hello there");
        Response response = request.execute().get();
        assertThat(response.getResponseBody(), is("hello there"));
    }
}
