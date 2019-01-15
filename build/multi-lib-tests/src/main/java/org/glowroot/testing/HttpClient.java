/**
 * Copyright 2016-2019 the original author or authors.
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

import static org.glowroot.testing.JavaVersion.JAVA6;
import static org.glowroot.testing.JavaVersion.JAVA7;
import static org.glowroot.testing.JavaVersion.JAVA8;

public class HttpClient {

    private static final String MODULE_PATH = "agent/plugins/http-client-plugin";

    public static void main(String[] args) throws Exception {
        apacheHttpClient();
        apacheHttpAsyncClient();
        apacheHttpClient3x();
        asyncHttpClient();
        cxfClient();
        httpUrlConnection();
        okHttpClient();
        okHttpClient2x();
        springWebClient();
        axisClient();
    }

    private static void apacheHttpClient() throws Exception {
        final String test = "ApacheHttpClientPluginIT";
        updateLibVersion("apache.httpclient.version", "4.0");
        updateLibVersion("apache.httpcore.version", "4.0.1");
        run(test, "apache-httpclient-pre-4.2");
        for (int i = 1; i <= 3; i++) {
            updateLibVersion("apache.httpclient.version", "4.0." + i);
            updateLibVersion("apache.httpcore.version", "4.0.1");
            run(test, "apache-httpclient-pre-4.2");
        }
        updateLibVersion("apache.httpclient.version", "4.1");
        updateLibVersion("apache.httpcore.version", "4.1");
        run(test, "apache-httpclient-pre-4.2");
        for (int i = 1; i <= 3; i++) {
            updateLibVersion("apache.httpclient.version", "4.1." + i);
            updateLibVersion("apache.httpcore.version", "4.1");
            run(test, "apache-httpclient-pre-4.2");
        }
        updateLibVersion("apache.httpclient.version", "4.2");
        updateLibVersion("apache.httpcore.version", "4.2");
        run(test);
        for (int i = 1; i <= 6; i++) {
            updateLibVersion("apache.httpclient.version", "4.2." + i);
            updateLibVersion("apache.httpcore.version", "4.2");
            run(test);
        }
        updateLibVersion("apache.httpclient.version", "4.3");
        updateLibVersion("apache.httpcore.version", "4.3");
        run(test);
        for (int i = 1; i <= 6; i++) {
            updateLibVersion("apache.httpclient.version", "4.3." + i);
            updateLibVersion("apache.httpcore.version", "4.3");
            run(test);
        }
        run(test);
        updateLibVersion("apache.httpclient.version", "4.4");
        updateLibVersion("apache.httpcore.version", "4.4");
        run(test);
        updateLibVersion("apache.httpclient.version", "4.4.1");
        updateLibVersion("apache.httpcore.version", "4.4.1");
        run(test);
        updateLibVersion("apache.httpclient.version", "4.5");
        updateLibVersion("apache.httpcore.version", "4.4.1");
        run(test);
        updateLibVersion("apache.httpclient.version", "4.5.1");
        updateLibVersion("apache.httpcore.version", "4.4.3");
        run(test);
        updateLibVersion("apache.httpclient.version", "4.5.2");
        updateLibVersion("apache.httpcore.version", "4.4.4");
        run(test);
        updateLibVersion("apache.httpclient.version", "4.5.3");
        updateLibVersion("apache.httpcore.version", "4.4.6");
        run(test);
        updateLibVersion("apache.httpclient.version", "4.5.4");
        updateLibVersion("apache.httpcore.version", "4.4.7");
        run(test);
        updateLibVersion("apache.httpclient.version", "4.5.5");
        updateLibVersion("apache.httpcore.version", "4.4.9");
        run(test);
        updateLibVersion("apache.httpclient.version", "4.5.6");
        updateLibVersion("apache.httpcore.version", "4.4.10");
        run(test);
    }

    private static void apacheHttpAsyncClient() throws Exception {
        final String test = "ApacheHttpAsyncClientPluginIT";
        updateLibVersion("apache.httpasyncclient.version", "4.0");
        updateLibVersion("apache.httpcore.version", "4.3");
        updateLibVersion("apache.httpclient.version", "4.3.1");
        run(test);
        updateLibVersion("apache.httpasyncclient.version", "4.0.1");
        updateLibVersion("apache.httpcore.version", "4.3.2");
        updateLibVersion("apache.httpclient.version", "4.3.2");
        run(test);
        updateLibVersion("apache.httpasyncclient.version", "4.0.2");
        updateLibVersion("apache.httpcore.version", "4.3.2");
        updateLibVersion("apache.httpclient.version", "4.3.5");
        run(test);
        updateLibVersion("apache.httpasyncclient.version", "4.1");
        updateLibVersion("apache.httpcore.version", "4.4.1");
        updateLibVersion("apache.httpclient.version", "4.4.1");
        run(test);
        updateLibVersion("apache.httpasyncclient.version", "4.1.1");
        updateLibVersion("apache.httpcore.version", "4.4.4");
        updateLibVersion("apache.httpclient.version", "4.5.1");
        run(test);
        updateLibVersion("apache.httpasyncclient.version", "4.1.2");
        updateLibVersion("apache.httpcore.version", "4.4.5");
        updateLibVersion("apache.httpclient.version", "4.5.2");
        run(test);
        updateLibVersion("apache.httpasyncclient.version", "4.1.3");
        updateLibVersion("apache.httpcore.version", "4.4.6");
        updateLibVersion("apache.httpclient.version", "4.5.3");
        run(test);
    }

    private static void apacheHttpClient3x() throws Exception {
        final String test = "ApacheHttpClient3xPluginIT";
        updateLibVersion("apache.httpclient3x.version", "3.0");
        run(test);
        updateLibVersion("apache.httpclient3x.version", "3.0.1");
        run(test);
        updateLibVersion("apache.httpclient3x.version", "3.1");
        run(test);
    }

    private static void asyncHttpClient() throws Exception {
        for (int i = 1; i <= 5; i++) {
            runAsyncHttpClient1x("1.6." + i);
        }
        for (int i = 0; i <= 24; i++) {
            runAsyncHttpClient1x("1.7." + i);
        }
        for (int i = 0; i <= 17; i++) {
            runAsyncHttpClient1x("1.8." + i);
        }
        for (int i = 0; i <= 40; i++) {
            runAsyncHttpClient1x("1.9." + i);
        }
        runAsyncHttpClient2x("2.0.0");
        runAsyncHttpClient2x("2.0.1");
        runAsyncHttpClient2x("2.0.2");
        runAsyncHttpClient2x("2.0.3");
        runAsyncHttpClient2x("2.0.4");
        runAsyncHttpClient2x("2.0.5");
        runAsyncHttpClient2x("2.0.6");
        runAsyncHttpClient2x("2.0.7");
        runAsyncHttpClient2x("2.0.8");
        runAsyncHttpClient2x("2.0.9");
        runAsyncHttpClient2x("2.0.10");
        runAsyncHttpClient2x("2.0.11");
        runAsyncHttpClient2x("2.0.12");
        runAsyncHttpClient2x("2.0.13");
        runAsyncHttpClient2x("2.0.14");
        runAsyncHttpClient2x("2.0.15");
        runAsyncHttpClient2x("2.0.16");
        runAsyncHttpClient2x("2.0.17");
        runAsyncHttpClient2x("2.0.18");
        runAsyncHttpClient2x("2.0.19");
        runAsyncHttpClient2x("2.0.20");
        runAsyncHttpClient2x("2.0.21");
        runAsyncHttpClient2x("2.0.22");
        runAsyncHttpClient2x("2.0.23");
        runAsyncHttpClient2x("2.0.24");
        runAsyncHttpClient2x("2.0.25");
        runAsyncHttpClient2x("2.0.26");
        runAsyncHttpClient2x("2.0.27");
        runAsyncHttpClient2x("2.0.28");
        runAsyncHttpClient2x("2.0.29");
        runAsyncHttpClient2x("2.0.30");
        runAsyncHttpClient2x("2.0.31");
        runAsyncHttpClient2x("2.0.32");
        runAsyncHttpClient2x("2.0.33");
        runAsyncHttpClient2x("2.0.34");
        runAsyncHttpClient2x("2.0.35");
        runAsyncHttpClient2x("2.0.36");
        runAsyncHttpClient2x("2.0.37");
        runAsyncHttpClient2x("2.0.38");
        runAsyncHttpClient2x("2.0.39");

        runAsyncHttpClient2x("2.1.0");
        runAsyncHttpClient2x("2.1.1");
        runAsyncHttpClient2x("2.1.2");

        runAsyncHttpClient2x("2.2.0");
        runAsyncHttpClient2x("2.2.1");

        runAsyncHttpClient2x("2.3.0");

        runAsyncHttpClient2x("2.4.0");
        runAsyncHttpClient2x("2.4.1");
        runAsyncHttpClient2x("2.4.2");
        runAsyncHttpClient2x("2.4.3");
        runAsyncHttpClient2x("2.4.4");
        runAsyncHttpClient2x("2.4.5");
        runAsyncHttpClient2x("2.4.6");
        runAsyncHttpClient2x("2.4.7");
        runAsyncHttpClient2x("2.4.8");
        runAsyncHttpClient2x("2.4.9");

        runAsyncHttpClient2x("2.5.0");
        runAsyncHttpClient2x("2.5.1");
        runAsyncHttpClient2x("2.5.2");
        runAsyncHttpClient2x("2.5.3");
        runAsyncHttpClient2x("2.5.4");
        runAsyncHttpClient2x("2.6.0");
    }

    private static void cxfClient() throws Exception {
        final String test = "CxfClientPluginIT";
        for (int i = 1; i <= 10; i++) {
            updateLibVersion("cxf.version", "2.1." + i);
            run(test, "cxf-2.x");
        }
        for (int i = 1; i <= 12; i++) {
            updateLibVersion("cxf.version", "2.2." + i);
            run(test, "cxf-2.x");
        }
        for (int i = 0; i <= 11; i++) {
            updateLibVersion("cxf.version", "2.3." + i);
            run(test, "cxf-2.x");
        }
        for (int i = 0; i <= 10; i++) {
            updateLibVersion("cxf.version", "2.4." + i);
            run(test, "cxf-2.x");
        }
        for (int i = 0; i <= 11; i++) {
            updateLibVersion("cxf.version", "2.5." + i);
            run(test, "cxf-2.x");
        }
        for (int i = 0; i <= 17; i++) {
            updateLibVersion("cxf.version", "2.6." + i);
            run(test, "cxf-2.x");
        }
        for (int i = 0; i <= 18; i++) {
            updateLibVersion("cxf.version", "2.7." + i);
            run(test, "cxf-2.x");
        }
        for (int i = 0; i <= 15; i++) {
            updateLibVersion("cxf.version", "3.0." + i);
            run(test);
        }
        for (int i = 0; i <= 15; i++) {
            updateLibVersion("cxf.version", "3.1." + i);
            runJava7(test);
        }
        for (int i = 0; i <= 4; i++) {
            updateLibVersion("cxf.version", "3.2." + i);
            runJava8(test);
        }
    }

    private static void httpUrlConnection() throws Exception {
        final String test = "HttpURLConnectionIT";
        Util.log("http client plugin (HttpURLConnection)");
        Util.runTest(MODULE_PATH, test, JAVA8, JAVA7, JAVA6);
    }

    private static void okHttpClient() throws Exception {
        final String test = "OkHttpClientPluginIT";
        updateLibVersion("okhttpclient.version", "3.0.1");
        runJava7(test);
        updateLibVersion("okhttpclient.version", "3.1.0");
        runJava7(test);
        updateLibVersion("okhttpclient.version", "3.1.1");
        runJava7(test);
        updateLibVersion("okhttpclient.version", "3.1.2");
        runJava7(test);
        updateLibVersion("okhttpclient.version", "3.2.0");
        runJava7(test);
        updateLibVersion("okhttpclient.version", "3.3.0");
        runJava7(test);
        updateLibVersion("okhttpclient.version", "3.3.1");
        runJava7(test);
        updateLibVersion("okhttpclient.version", "3.4.0");
        runJava7(test);
        updateLibVersion("okhttpclient.version", "3.4.1");
        runJava7(test);
        updateLibVersion("okhttpclient.version", "3.4.2");
        runJava7(test);
        updateLibVersion("okhttpclient.version", "3.5.0");
        runJava7(test);
        updateLibVersion("okhttpclient.version", "3.6.0");
        runJava7(test);
        updateLibVersion("okhttpclient.version", "3.7.0");
        runJava7(test);
        updateLibVersion("okhttpclient.version", "3.8.0");
        runJava7(test);
        updateLibVersion("okhttpclient.version", "3.8.1");
        runJava7(test);
        updateLibVersion("okhttpclient.version", "3.9.0");
        runJava7(test);
        updateLibVersion("okhttpclient.version", "3.9.1");
        runJava7(test);
        updateLibVersion("okhttpclient.version", "3.10.0");
        runJava7(test);
        updateLibVersion("okhttpclient.version", "3.11.0");
        runJava7(test);
        updateLibVersion("okhttpclient.version", "3.12.0");
        runJava7(test);
        updateLibVersion("okhttpclient.version", "3.12.1");
        runJava7(test);
    }

    private static void okHttpClient2x() throws Exception {
        final String test = "OkHttpClient2xPluginIT";
        updateLibVersion("okhttpclient2x.version", "2.0.0");
        runJava7(test);
        updateLibVersion("okhttpclient2x.version", "2.1.0");
        runJava7(test);
        updateLibVersion("okhttpclient2x.version", "2.2.0");
        runJava7(test);
        updateLibVersion("okhttpclient2x.version", "2.3.0");
        runJava7(test);
        updateLibVersion("okhttpclient2x.version", "2.4.0");
        runJava7(test);
        updateLibVersion("okhttpclient2x.version", "2.5.0");
        runJava7(test);
        updateLibVersion("okhttpclient2x.version", "2.6.0");
        runJava7(test);
        for (int i = 0; i <= 5; i++) {
            updateLibVersion("okhttpclient2x.version", "2.7." + i);
            runJava7(test);
        }
    }

    private static void springWebClient() throws Exception {
        final String test = "RestTemplatePluginIT";
        for (int i = 0; i <= 7; i++) {
            updateLibVersion("spring.version", "3.0." + i + ".RELEASE");
            run(test);
        }
        for (int i = 0; i <= 4; i++) {
            updateLibVersion("spring.version", "3.1." + i + ".RELEASE");
            run(test);
        }
        for (int i = 0; i <= 18; i++) {
            updateLibVersion("spring.version", "3.2." + i + ".RELEASE");
            run(test);
        }
        for (int i = 0; i <= 9; i++) {
            updateLibVersion("spring.version", "4.0." + i + ".RELEASE");
            run(test);
        }
        for (int i = 0; i <= 9; i++) {
            updateLibVersion("spring.version", "4.1." + i + ".RELEASE");
            run(test);
        }
        for (int i = 0; i <= 9; i++) {
            updateLibVersion("spring.version", "4.2." + i + ".RELEASE");
            run(test);
        }
        for (int i = 0; i <= 18; i++) {
            updateLibVersion("spring.version", "4.3." + i + ".RELEASE");
            run(test);
        }
        for (int i = 0; i <= 7; i++) {
            updateLibVersion("spring.version", "5.0." + i + ".RELEASE");
            runJava8(test);
        }
    }

    private static void axisClient() throws Exception {
        final String test = "AxisClientPluginIT";
        updateLibVersion("cxf.version", "3.0.15"); // need a Java 6 compatible CXF version
        updateLibVersion("axis.version", "1.0");
        run(test);
        updateLibVersion("axis.version", "1.1");
        run(test);
        updateLibVersion("axis.version", "1.2");
        run(test);
        updateLibVersion("axis.version", "1.2.1");
        run(test);
        updateLibVersion("axis.version", "1.3");
        run(test);
        updateLibVersion("axis.version", "1.4");
        run(test);
    }

    private static void runAsyncHttpClient1x(String asyncHttpClientVersion) throws Exception {
        final String test = "AsyncHttpClientPluginIT";
        updateLibVersion("asynchttpclient1x.version", asyncHttpClientVersion);
        if (asyncHttpClientVersion.startsWith("1.9")) {
            runJava7(test, "async-http-client-1.x");
        } else {
            run(test, "async-http-client-1.x");
        }
    }

    private static void runAsyncHttpClient2x(String asyncHttpClientVersion) throws Exception {
        final String test = "AsyncHttpClientPluginIT";
        updateLibVersion("asynchttpclient2x.version", asyncHttpClientVersion);
        runJava8(test, "async-http-client-2.x");
    }

    private static void updateLibVersion(String property, String version) throws IOException {
        Util.updateLibVersion(MODULE_PATH, property, version);
    }

    private static void run(String test) throws Exception {
        Util.runTest(MODULE_PATH, test, JAVA8, JAVA7, JAVA6);
    }

    private static void run(String test, String profile) throws Exception {
        Util.runTest(MODULE_PATH, test, profile, JAVA8, JAVA7, JAVA6);
    }

    private static void runJava7(String test) throws Exception {
        Util.runTest(MODULE_PATH, test, JAVA8, JAVA7);
    }

    private static void runJava7(String test, String profile) throws Exception {
        Util.runTest(MODULE_PATH, test, profile, JAVA8, JAVA7);
    }

    private static void runJava8(String test) throws Exception {
        Util.runTest(MODULE_PATH, test, JAVA8);
    }

    private static void runJava8(String test, String profile) throws Exception {
        Util.runTest(MODULE_PATH, test, profile, JAVA8);
    }
}
