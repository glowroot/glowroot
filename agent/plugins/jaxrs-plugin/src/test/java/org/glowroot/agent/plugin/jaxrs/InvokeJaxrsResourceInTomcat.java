/*
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
package org.glowroot.agent.plugin.jaxrs;

import java.io.File;
import java.net.ServerSocket;

import com.ning.http.client.AsyncHttpClient;
import org.apache.catalina.Context;
import org.apache.catalina.loader.WebappLoader;
import org.apache.catalina.startup.Tomcat;

import org.glowroot.agent.it.harness.AppUnderTest;

abstract class InvokeJaxrsResourceInTomcat implements AppUnderTest {

    public void executeApp(String webapp, String contextPath, String url) throws Exception {
        int port = getAvailablePort();
        Tomcat tomcat = new Tomcat();
        tomcat.setBaseDir("target/tomcat");
        tomcat.setPort(port);
        Context context = tomcat.addWebapp(contextPath,
                new File("src/test/resources/" + webapp).getAbsolutePath());

        WebappLoader webappLoader =
                new WebappLoader(InvokeJaxrsResourceInTomcat.class.getClassLoader());
        context.setLoader(webappLoader);

        tomcat.start();
        AsyncHttpClient asyncHttpClient = new AsyncHttpClient();
        int statusCode = asyncHttpClient.prepareGet("http://localhost:" + port + contextPath + url)
                .execute().get().getStatusCode();
        asyncHttpClient.close();
        if (statusCode != 200) {
            throw new IllegalStateException("Unexpected status code: " + statusCode);
        }

        tomcat.stop();
        tomcat.destroy();
    }

    private static int getAvailablePort() throws Exception {
        ServerSocket serverSocket = new ServerSocket(0);
        int port = serverSocket.getLocalPort();
        serverSocket.close();
        return port;
    }
}
