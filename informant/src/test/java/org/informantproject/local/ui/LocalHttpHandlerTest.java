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
package org.informantproject.local.ui;

import static org.hamcrest.CoreMatchers.is;
import static org.junit.Assert.assertThat;

import java.util.Set;

import org.informantproject.MainEntryPoint;
import org.informantproject.configuration.CoreConfigurationTestData;
import org.informantproject.configuration.ImmutableCoreConfiguration;
import org.informantproject.util.ThreadChecker;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.ning.http.client.AsyncHttpClient;
import com.ning.http.client.AsyncHttpClient.BoundRequestBuilder;
import com.ning.http.client.Response;

/**
 * @author Trask Stalnaker
 * @since 0.5
 */
public class LocalHttpHandlerTest {

    private Set<Thread> preExistingThreads;
    private AsyncHttpClient asyncHttpClient;

    @Before
    public void before() {
        preExistingThreads = ThreadChecker.currentThreadList();
        MainEntryPoint.start();
        asyncHttpClient = new AsyncHttpClient();
    }

    @After
    public void after() throws Exception {
        // asyncHttpClient is not part of the system under test, so it can be shut down
        // first before checking for non-daemon threads
        asyncHttpClient.close();
        // wait for the non-daemon thread in AsyncHttpClient to shutdown
        // (see NettyConnectionsPool.idleConnectionDetector)
        // a patch has been submitted to change this non-daemon thread to a daemon thread
        // (see https://github.com/sonatype/async-http-client/pull/35)
        // TODO remove this sleep (and comment above) once the patch is accepted
        // and a new release of async-http-client is available
        Thread.sleep(200);
        ThreadChecker.preShutdownNonDaemonThreadCheck(preExistingThreads);
        MainEntryPoint.shutdown();
        ThreadChecker.postShutdownThreadCheck(preExistingThreads);
    }

    @Test
    public void shouldUpdateAndReadBackConfiguration() throws Exception {
        // given
        ImmutableCoreConfiguration randomCoreConfiguration = new CoreConfigurationTestData()
                .getRandomCoreConfiguration();
        String json = "{\"coreConfiguration\":" + randomCoreConfiguration.toJson() + "}";
        BoundRequestBuilder updateRequest = asyncHttpClient
                .preparePost("http://localhost:4000/configuration/update");
        updateRequest.setBody(json);
        updateRequest.execute().get();
        BoundRequestBuilder readRequest = asyncHttpClient
                .prepareGet("http://localhost:4000/configuration/read");
        // when
        Response response = readRequest.execute().get();
        // then
        String responseText = response.getResponseBody();
        JsonObject rootNode = new JsonParser().parse(responseText).getAsJsonObject();
        String coreConfigurationJson = new Gson().toJson(rootNode.get("coreConfiguration"));
        assertThat(coreConfigurationJson, is(randomCoreConfiguration.toJson()));
    }
}
