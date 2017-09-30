/**
 * Copyright 2016-2017 the original author or authors.
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
package org.glowroot.agent.plugin.httpclient;

import java.security.SecureRandom;
import java.security.cert.X509Certificate;

import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSession;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

import fi.iki.elonen.NanoHTTPD;

import org.glowroot.agent.it.harness.AppUnderTest;
import org.glowroot.agent.it.harness.TransactionMarker;

public abstract class ExecuteHttpBase implements AppUnderTest, TransactionMarker {

    static {
        TrustManager[] trustAllCerts = new TrustManager[] {
                new X509TrustManager() {
                    @Override
                    public X509Certificate[] getAcceptedIssuers() {
                        return null;
                    }
                    @Override
                    public void checkClientTrusted(X509Certificate[] certs, String authType) {}
                    @Override
                    public void checkServerTrusted(X509Certificate[] certs, String authType) {}
                }};
        SSLContext sc;
        try {
            sc = SSLContext.getInstance("TLS");
            sc.init(null, trustAllCerts, new SecureRandom());
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        HttpsURLConnection.setDefaultSSLSocketFactory(sc.getSocketFactory());
        HttpsURLConnection.setDefaultHostnameVerifier(new HostnameVerifier() {
            @Override
            public boolean verify(String hostname, SSLSession sslSession) {
                return hostname.equals("localhost");
            }
        });
    }

    private int port;

    @Override
    public void executeApp() throws Exception {
        HttpServer httpServer = new HttpServer();
        if (getClass().getName().endsWith("HTTPS")) {
            httpServer.makeSecure(
                    NanoHTTPD.makeSSLSocketFactory("/keystore.jks", "password".toCharArray()),
                    null);
        }
        httpServer.start();
        port = httpServer.getListeningPort();
        try {
            transactionMarker();
        } finally {
            httpServer.stop();
        }
    }

    protected int getPort() {
        return port;
    }
}
