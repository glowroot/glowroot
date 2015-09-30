/*
 * Copyright 2011-2015 the original author or authors.
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
package org.glowroot.agent.harness.common;

import java.io.InputStream;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import javax.annotation.Nullable;

import com.ning.http.client.AsyncHttpClient;
import com.ning.http.client.AsyncHttpClient.BoundRequestBuilder;
import com.ning.http.client.AsyncHttpClientConfig;
import com.ning.http.client.Response;
import com.ning.http.client.cookie.Cookie;
import com.ning.http.client.providers.netty.NettyAsyncHttpProviderConfig;

public class HttpClient {

    private final AsyncHttpClient asyncHttpClient;
    private volatile int port;
    private volatile @Nullable Cookie sessionIdCookie;

    public HttpClient(int uiPort) {
        this.port = uiPort;
        this.asyncHttpClient = createAsyncHttpClient();
    }

    public void updateUiPort(int uiPort) {
        this.port = uiPort;
    }

    public String get(String path) throws Exception {
        String url = "http://localhost:" + port + path;
        BoundRequestBuilder request = asyncHttpClient.prepareGet(url);
        Response response = execute(request);
        return validateAndReturnBody(response, url);
    }

    public InputStream getAsStream(String path) throws Exception {
        String url = "http://localhost:" + port + path;
        BoundRequestBuilder request = asyncHttpClient.prepareGet(url);
        Response response = execute(request);
        return validateAndReturnBodyAsStream(response, url);
    }

    public String post(String path, String data) throws Exception {
        String url = "http://localhost:" + port + path;
        BoundRequestBuilder request = asyncHttpClient.preparePost(url);
        request.setBody(data);
        Response response = execute(request);
        return validateAndReturnBody(response, url);
    }

    public void close() {
        asyncHttpClient.close();
    }

    private Response execute(BoundRequestBuilder request) throws Exception {
        populateSessionIdCookie(request);
        Response response = request.execute().get();
        extractSessionIdCookie(response);
        return response;
    }

    private void populateSessionIdCookie(BoundRequestBuilder request) {
        if (sessionIdCookie != null) {
            request.addCookie(sessionIdCookie);
        }
    }

    private void extractSessionIdCookie(Response response) {
        for (Cookie cookie : response.getCookies()) {
            if (cookie.getName().equals("GLOWROOT_SESSION_ID")) {
                if (cookie.getValue().isEmpty()) {
                    sessionIdCookie = null;
                } else {
                    sessionIdCookie = cookie;
                }
                return;
            }
        }
    }

    private static String validateAndReturnBody(Response response, String url) throws Exception {
        if (response.getStatusCode() == 200) {
            return response.getResponseBody();
        } else {
            throw new IllegalStateException("HTTP status code " + response.getStatusCode()
                    + " was returned for url: " + url);
        }
    }

    private static InputStream validateAndReturnBodyAsStream(Response response, String url)
            throws Exception {
        if (response.getStatusCode() == 200) {
            return response.getResponseBodyAsStream();
        } else {
            throw new IllegalStateException("HTTP status code " + response.getStatusCode()
                    + " was returned for url: " + url);
        }
    }

    private static AsyncHttpClient createAsyncHttpClient() {
        ExecutorService executorService = Executors.newCachedThreadPool();
        AsyncHttpClientConfig.Builder builder = new AsyncHttpClientConfig.Builder()
                .setCompressionEnabled(true)
                .setMaxRequestRetry(0)
                .setExecutorService(executorService);
        NettyAsyncHttpProviderConfig providerConfig = new NettyAsyncHttpProviderConfig();
        providerConfig.addProperty(NettyAsyncHttpProviderConfig.BOSS_EXECUTOR_SERVICE,
                executorService);
        builder.setAsyncHttpClientProviderConfig(providerConfig);
        return new AsyncHttpClient(builder.build());
    }
}
