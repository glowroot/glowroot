/**
 * Copyright 2011 the original author or authors.
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
package org.informantproject.testkit;

import java.io.File;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

import org.informantproject.util.DaemonExecutors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.base.Strings;
import com.google.common.io.ByteStreams;

/**
 * @author Trask Stalnaker
 * @since 0.5
 */
class ExternalJvmInformantContainer extends InformantContainer {

    private static final Logger logger = LoggerFactory
            .getLogger(ExternalJvmInformantContainer.class);

    private Process process;
    private ExecutorService executorService;

    private Socket socket;
    private ObjectOutputStream objectOut;
    private ObjectInputStream objectIn;

    @Override
    protected void initImpl(String options) throws Exception {
        String classpath = System.getProperty("java.class.path");
        String path = System.getProperty("java.home") + File.separator + "bin" + File.separator
                + "java";
        String informantJarAndOptions = findInformantJarPath(classpath);
        if (!Strings.isNullOrEmpty(options)) {
            informantJarAndOptions += "=" + options;
        }
        ServerSocket serverSocket = new ServerSocket(0);
        ProcessBuilder processBuilder = new ProcessBuilder(path, "-cp", classpath, "-javaagent:"
                + informantJarAndOptions, ExternalJvmInformantContainer.class.getName(),
                Integer.toString(serverSocket.getLocalPort()));
        processBuilder.redirectErrorStream(true);
        process = processBuilder.start();
        executorService = DaemonExecutors.newSingleThreadExecutor("ExternalMainPipe");
        executorService.submit(new Runnable() {
            public void run() {
                try {
                    ByteStreams.copy(process.getInputStream(), System.out);
                } catch (IOException e) {
                    logger.error(e.getMessage(), e);
                }
            }
        });
        socket = serverSocket.accept();
        objectOut = new ObjectOutputStream(socket.getOutputStream());
        objectIn = new ObjectInputStream(socket.getInputStream());
    }

    @Override
    protected void executeAppUnderTestImpl(Class<? extends AppUnderTest> appUnderTestClass,
            String threadName) throws Exception {

        objectOut.writeObject(appUnderTestClass.getName());
        objectOut.writeObject(threadName);
        objectIn.readObject();
    }

    @Override
    protected void closeImpl() throws Exception {
        objectOut.close();
        objectIn.close();
        socket.close();
        process.waitFor();
        executorService.shutdownNow();
    }

    public static void main(String[] args) {
        try {
            DaemonExecutors.newSingleThreadScheduledExecutor("TimeoutAction").schedule(
                    new TimeoutAction(), 60, TimeUnit.SECONDS);
            int port = Integer.parseInt(args[0]);
            Socket socket = new Socket((String) null, port);
            new Thread(new SocketCommandProcessor(socket)).start();
        } catch (Exception e) {
            logger.error(e.getMessage(), e);
        }
    }

    private static String findInformantJarPath(String classpath) {
        String[] classpathElements = classpath.split(File.pathSeparator);
        for (String classpathElement : classpathElements) {
            if (new File(classpathElement).getName().matches("informant-[0-9.]+(-SNAPSHOT)?.jar")) {
                return classpathElement;
            }
        }
        throw new IllegalStateException("Could not find informant.jar on the classpath: "
                + classpath);
    }

    private static class TimeoutAction implements Runnable {
        public void run() {
            System.exit(1);
        }
    }
}
