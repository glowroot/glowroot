/**
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
package org.glowroot.testing;

import java.io.IOException;

public class HttpClient {

    private static final String MODULE_PATH = "agent-parent/plugins/http-client-plugin";

    public static void main(String[] args) throws Exception {
        apacheHttpClient();
        apacheHttpAsyncClient();
        apacheHttpClient3x();
        asyncHttpClient();
        okHttpClient();
        cxfClient();
        springWebClient();
    }

    private static void apacheHttpClient() throws Exception {
        final String test = "ApacheHttpClientPluginIT";
        updateLibVersion("apache.httpclient.version", "4.0");
        updateLibVersion("apache.httpcore.version", "4.0.1");
        runTest(test, "apache-httpclient-pre-4.2");
        for (int i = 1; i <= 3; i++) {
            updateLibVersion("apache.httpclient.version", "4.0." + i);
            updateLibVersion("apache.httpcore.version", "4.0.1");
            runTest(test, "apache-httpclient-pre-4.2");
        }
        updateLibVersion("apache.httpclient.version", "4.1");
        updateLibVersion("apache.httpcore.version", "4.1");
        runTest(test, "apache-httpclient-pre-4.2");
        for (int i = 1; i <= 3; i++) {
            updateLibVersion("apache.httpclient.version", "4.1." + i);
            updateLibVersion("apache.httpcore.version", "4.1");
            runTest(test, "apache-httpclient-pre-4.2");
        }
        updateLibVersion("apache.httpclient.version", "4.2");
        updateLibVersion("apache.httpcore.version", "4.2");
        runTest(test);
        for (int i = 1; i <= 6; i++) {
            updateLibVersion("apache.httpclient.version", "4.2." + i);
            updateLibVersion("apache.httpcore.version", "4.2");
            runTest(test);
        }
        updateLibVersion("apache.httpclient.version", "4.3");
        updateLibVersion("apache.httpcore.version", "4.3");
        runTest(test);
        for (int i = 1; i <= 6; i++) {
            updateLibVersion("apache.httpclient.version", "4.3." + i);
            updateLibVersion("apache.httpcore.version", "4.3");
            runTest(test);
        }
        runTest(test);
        updateLibVersion("apache.httpclient.version", "4.4");
        updateLibVersion("apache.httpcore.version", "4.4");
        runTest(test);
        updateLibVersion("apache.httpclient.version", "4.4.1");
        updateLibVersion("apache.httpcore.version", "4.4.1");
        runTest(test);
        updateLibVersion("apache.httpclient.version", "4.5");
        updateLibVersion("apache.httpcore.version", "4.4.1");
        runTest(test);
        updateLibVersion("apache.httpclient.version", "4.5.1");
        updateLibVersion("apache.httpcore.version", "4.4.3");
        runTest(test);
        updateLibVersion("apache.httpclient.version", "4.5.2");
        updateLibVersion("apache.httpcore.version", "4.4.4");
        runTest(test);
    }

    private static void apacheHttpAsyncClient() throws Exception {
        final String test = "ApacheHttpAsyncClientPluginIT";
        updateLibVersion("apache.httpasyncclient.version", "4.0");
        updateLibVersion("apache.httpcore.version", "4.3");
        updateLibVersion("apache.httpclient.version", "4.3.1");
        runTest(test);
        updateLibVersion("apache.httpasyncclient.version", "4.0.1");
        updateLibVersion("apache.httpcore.version", "4.3.2");
        updateLibVersion("apache.httpclient.version", "4.3.2");
        runTest(test);
        updateLibVersion("apache.httpasyncclient.version", "4.0.2");
        updateLibVersion("apache.httpcore.version", "4.3.2");
        updateLibVersion("apache.httpclient.version", "4.3.5");
        runTest(test);
        updateLibVersion("apache.httpasyncclient.version", "4.1");
        updateLibVersion("apache.httpcore.version", "4.4.1");
        updateLibVersion("apache.httpclient.version", "4.4.1");
        runTest(test);
        updateLibVersion("apache.httpasyncclient.version", "4.1.1");
        updateLibVersion("apache.httpcore.version", "4.4.4");
        updateLibVersion("apache.httpclient.version", "4.5.1");
        runTest(test);
        updateLibVersion("apache.httpasyncclient.version", "4.1.2");
        updateLibVersion("apache.httpcore.version", "4.4.5");
        updateLibVersion("apache.httpclient.version", "4.5.2");
        runTest(test);
    }

    private static void apacheHttpClient3x() throws Exception {
        final String test = "ApacheHttpClient3xPluginIT";
        updateLibVersion("apache.httpclient3x.version", "3.0");
        runTest(test);
        updateLibVersion("apache.httpclient3x.version", "3.0.1");
        runTest(test);
        updateLibVersion("apache.httpclient3x.version", "3.1");
        runTest(test);
    }

    private static void asyncHttpClient() throws Exception {
        final String test = "AsyncHttpClientPluginIT";
        for (int i = 1; i <= 24; i++) {
            updateLibVersion("asynchttpclient.version", "1.7." + i);
            runTest(test, "async-http-client-1.x");
        }
        for (int i = 0; i <= 17; i++) {
            updateLibVersion("asynchttpclient.version", "1.8." + i);
            runTest(test, "async-http-client-1.x");
        }
        for (int i = 0; i <= 39; i++) {
            updateLibVersion("asynchttpclient.version", "1.9." + i);
            runTest(test, "async-http-client-1.x");
        }
        for (int i = 0; i <= 11; i++) {
            updateLibVersion("asynchttpclient.version", "2.0." + i);
            runTest(test, "async-http-client-2.x");
        }
    }

    private static void okHttpClient() throws Exception {
        final String test = "OkHttpClientPluginIT";
        updateLibVersion("okhttpclient.version", "2.0.0");
        runTest(test);
        updateLibVersion("okhttpclient.version", "2.1.0");
        runTest(test);
        updateLibVersion("okhttpclient.version", "2.2.0");
        runTest(test);
        updateLibVersion("okhttpclient.version", "2.3.0");
        runTest(test);
        updateLibVersion("okhttpclient.version", "2.4.0");
        runTest(test);
        updateLibVersion("okhttpclient.version", "2.5.0");
        runTest(test);
        updateLibVersion("okhttpclient.version", "2.6.0");
        runTest(test);
        for (int i = 0; i <= 5; i++) {
            updateLibVersion("okhttpclient.version", "2.7." + i);
            runTest(test);
        }
    }

    private static void cxfClient() throws Exception {
        final String test = "CxfClientPluginIT";
        for (int i = 1; i <= 10; i++) {
            updateLibVersion("cxf.version", "2.1." + i);
            runTest(test, "cxf-2.x");
        }
        for (int i = 1; i <= 12; i++) {
            updateLibVersion("cxf.version", "2.2." + i);
            runTest(test, "cxf-2.x");
        }
        for (int i = 0; i <= 11; i++) {
            updateLibVersion("cxf.version", "2.3." + i);
            runTest(test, "cxf-2.x");
        }
        for (int i = 0; i <= 10; i++) {
            updateLibVersion("cxf.version", "2.4." + i);
            runTest(test, "cxf-2.x");
        }
        for (int i = 0; i <= 11; i++) {
            updateLibVersion("cxf.version", "2.5." + i);
            runTest(test, "cxf-2.x");
        }
        for (int i = 0; i <= 16; i++) {
            updateLibVersion("cxf.version", "2.6." + i);
            runTest(test, "cxf-2.x");
        }
        for (int i = 0; i <= 18; i++) {
            updateLibVersion("cxf.version", "2.7." + i);
            runTest(test, "cxf-2.x");
        }
        for (int i = 0; i <= 8; i++) {
            updateLibVersion("cxf.version", "3.0." + i);
            runTest(test);
        }
        for (int i = 0; i <= 6; i++) {
            updateLibVersion("cxf.version", "3.1." + i);
            runTest(test);
        }
    }

    private static void springWebClient() throws Exception {
        final String test = "RestTemplatePluginIT";
        for (int i = 0; i <= 7; i++) {
            updateLibVersion("spring.version", "3.0." + i + ".RELEASE");
            runTest(test);
        }
        for (int i = 0; i <= 4; i++) {
            updateLibVersion("spring.version", "3.1." + i + ".RELEASE");
            runTest(test);
        }
        for (int i = 0; i <= 4; i++) {
            updateLibVersion("spring.version", "3.1." + i + ".RELEASE");
            runTest(test);
        }
        for (int i = 0; i <= 16; i++) {
            updateLibVersion("spring.version", "3.2." + i + ".RELEASE");
            runTest(test);
        }
        for (int i = 0; i <= 9; i++) {
            updateLibVersion("spring.version", "4.0." + i + ".RELEASE");
            runTest(test);
        }
        for (int i = 0; i <= 9; i++) {
            updateLibVersion("spring.version", "4.1." + i + ".RELEASE");
            runTest(test);
        }
        for (int i = 0; i <= 7; i++) {
            updateLibVersion("spring.version", "4.2." + i + ".RELEASE");
            runTest(test);
        }
        updateLibVersion("spring.version", "4.3.0.RELEASE");
        updateLibVersion("spring.version", "4.3.1.RELEASE");
        updateLibVersion("spring.version", "4.3.2.RELEASE");
    }

    private static void updateLibVersion(String property, String version) throws IOException {
        Util.updateLibVersion(MODULE_PATH, property, version);
    }

    private static void runTest(String test, String... profiles) throws Exception {
        Util.runTest(MODULE_PATH, test, profiles);
    }
}
