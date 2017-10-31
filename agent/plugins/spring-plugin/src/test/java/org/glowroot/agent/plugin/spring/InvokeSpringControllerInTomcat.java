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
package org.glowroot.agent.plugin.spring;

import java.io.File;
import java.lang.reflect.Method;
import java.net.ServerSocket;

import com.ning.http.client.AsyncHttpClient;
import org.apache.catalina.Context;
import org.apache.catalina.loader.WebappClassLoaderBase;
import org.apache.catalina.loader.WebappLoader;
import org.apache.catalina.startup.Tomcat;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.glowroot.agent.it.harness.AppUnderTest;

abstract class InvokeSpringControllerInTomcat implements AppUnderTest {

    private static final Logger logger =
            LoggerFactory.getLogger(InvokeSpringControllerInTomcat.class);

    public void executeApp(String webapp, String contextPath, String url) throws Exception {
        int port = getAvailablePort();
        Tomcat tomcat = new Tomcat();
        tomcat.setBaseDir("target/tomcat");
        tomcat.setPort(port);
        Context context = tomcat.addWebapp(contextPath,
                new File("src/test/resources/" + webapp).getAbsolutePath());

        WebappLoader webappLoader =
                new WebappLoader(InvokeSpringControllerInTomcat.class.getClassLoader());
        context.setLoader(webappLoader);

        tomcat.start();

        AsyncHttpClient asyncHttpClient = new AsyncHttpClient();
        int statusCode = asyncHttpClient.prepareGet("http://localhost:" + port + contextPath + url)
                .execute().get().getStatusCode();
        asyncHttpClient.close();
        if (statusCode != 200) {
            throw new IllegalStateException("Unexpected status code: " + statusCode);
        }

        // spring still does a bit of work after the response is concluded,
        // see org.springframework.web.servlet.FrameworkServlet.publishRequestHandledEvent(),
        // so give a bit of time here, otherwise end up with sporadic test failures due to
        // ERROR logged by org.apache.catalina.loader.WebappClassLoaderBase, e.g.
        // "The web application [] is still processing a request that has yet to finish"
        Thread.sleep(200);
        checkForRequestThreads(webappLoader);
        tomcat.stop();
        tomcat.destroy();
    }

    private static int getAvailablePort() throws Exception {
        ServerSocket serverSocket = new ServerSocket(0);
        int port = serverSocket.getLocalPort();
        serverSocket.close();
        return port;
    }

    // log stack traces for any currently running request threads in order to troubleshoot sporadic
    // test failures due to ERROR logged by org.apache.catalina.loader.WebappClassLoaderBase
    // "The web application [] is still processing a request that has yet to finish. This is very
    // likely to create a memory leak..."
    private void checkForRequestThreads(WebappLoader webappLoader) throws Exception {
        Method getThreadsMethod = WebappClassLoaderBase.class.getDeclaredMethod("getThreads");
        getThreadsMethod.setAccessible(true);
        Method isRequestThreadMethod =
                WebappClassLoaderBase.class.getDeclaredMethod("isRequestThread", Thread.class);
        isRequestThreadMethod.setAccessible(true);
        ClassLoader webappClassLoader = webappLoader.getClassLoader();
        Thread[] threads = (Thread[]) getThreadsMethod.invoke(webappClassLoader);
        for (Thread thread : threads) {
            if (thread == null) {
                continue;
            }
            if ((boolean) isRequestThreadMethod.invoke(webappClassLoader, thread)) {
                StringBuilder sb = new StringBuilder();
                for (StackTraceElement element : thread.getStackTrace()) {
                    sb.append(element);
                    sb.append('\n');
                }
                logger.error("tomcat request thread \"{}\" is still active:\n{}", thread.getName(),
                        sb);
            }
        }
    }
}
