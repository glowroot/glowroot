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
package org.glowroot.agent.plugin.servlet;

import java.io.File;
import java.net.ServerSocket;

import org.apache.catalina.Context;
import org.apache.catalina.loader.WebappLoader;
import org.apache.catalina.startup.Tomcat;
import org.apache.naming.resources.VirtualDirContext;

import org.glowroot.agent.it.harness.AppUnderTest;

abstract class InvokeServletInTomcat implements AppUnderTest {

    protected final String contextPath;

    protected InvokeServletInTomcat(String contextPath) {
        this.contextPath = contextPath;
    }

    @Override
    public void executeApp() throws Exception {
        int port = getAvailablePort();
        Tomcat tomcat = new Tomcat();
        tomcat.setBaseDir("target/tomcat");
        tomcat.setPort(port);
        Context context =
                tomcat.addWebapp(contextPath, new File("src/test/resources").getAbsolutePath());

        WebappLoader webappLoader =
                new WebappLoader(InvokeServletInTomcat.class.getClassLoader());
        context.setLoader(webappLoader);

        // this is needed in order for Tomcat to find annotated servlet
        VirtualDirContext resources = new VirtualDirContext();
        resources.setExtraResourcePaths("/WEB-INF/classes=target/test-classes");
        context.setResources(resources);

        tomcat.start();

        doTest(port);

        tomcat.stop();
        tomcat.destroy();
    }

    protected abstract void doTest(int port) throws Exception;

    private static int getAvailablePort() throws Exception {
        ServerSocket serverSocket = new ServerSocket(0);
        int port = serverSocket.getLocalPort();
        serverSocket.close();
        return port;
    }
}
