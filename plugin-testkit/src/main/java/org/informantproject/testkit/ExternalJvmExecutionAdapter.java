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
package org.informantproject.testkit;

import java.io.File;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.ExecutorService;

import javax.annotation.concurrent.ThreadSafe;

import org.informantproject.api.Logger;
import org.informantproject.api.LoggerFactory;
import org.informantproject.core.util.DaemonExecutors;
import org.informantproject.core.util.UnitTests;
import org.informantproject.testkit.InformantContainer.ExecutionAdapter;

import com.google.common.base.Strings;
import com.google.common.collect.ImmutableList;
import com.google.common.io.ByteStreams;

/**
 * @author Trask Stalnaker
 * @since 0.5
 */
@ThreadSafe
class ExternalJvmExecutionAdapter implements ExecutionAdapter {

    private static final Logger logger = LoggerFactory.getLogger(ExternalJvmExecutionAdapter.class);

    private final Process process;
    private final ExecutorService consolePipeExecutorService;

    private final SocketCommander socketCommander;

    ExternalJvmExecutionAdapter(String agentArgs) throws IOException {
        String classpath = System.getProperty("java.class.path");
        String path = System.getProperty("java.home") + File.separator + "bin" + File.separator
                + "java";
        String javaagentArg = "-javaagent:" + UnitTests.findInformantCoreJarFile();
        if (!Strings.isNullOrEmpty(agentArgs)) {
            javaagentArg += "=" + agentArgs;
        }
        ServerSocket serverSocket = new ServerSocket(0);
        ProcessBuilder processBuilder = new ProcessBuilder(path, "-cp", classpath, javaagentArg,
                ExternalJvmExecutionAdapter.class.getName(), Integer.toString(serverSocket
                        .getLocalPort()));
        processBuilder.redirectErrorStream(true);
        process = processBuilder.start();
        consolePipeExecutorService = DaemonExecutors
                .newSingleThreadExecutor("ExternalJvmConsolePipe");
        consolePipeExecutorService.submit(new Runnable() {
            public void run() {
                try {
                    ByteStreams.copy(process.getInputStream(), System.out);
                } catch (IOException e) {
                    logger.error(e.getMessage(), e);
                }
            }
        });
        // TODO should ServerSocket.accept() be called to start listening before external process is
        // started?
        socketCommander = new SocketCommander(serverSocket.accept());
    }

    public int getPort() throws InterruptedException {
        return (Integer) socketCommander.sendCommand(SocketCommandProcessor.GET_PORT_COMMAND);
    }

    public void executeAppUnderTestImpl(Class<? extends AppUnderTest> appUnderTestClass,
            String threadName) throws InterruptedException {

        socketCommander.sendCommand(ImmutableList.of(SocketCommandProcessor.EXECUTE_APP_COMMAND,
                appUnderTestClass.getName(), threadName));
    }

    public void closeImpl() throws IOException, InterruptedException {
        socketCommander.close();
        process.waitFor();
        consolePipeExecutorService.shutdownNow();
    }

    public static void main(String[] args) {
        try {
            int port = Integer.parseInt(args[0]);
            Socket socket = new Socket((String) null, port);
            ObjectInputStream objectIn = new ObjectInputStream(socket.getInputStream());
            ObjectOutputStream objectOut = new ObjectOutputStream(socket.getOutputStream());
            new Thread(new SocketCommandProcessor(objectIn, objectOut)).start();
            new Thread(new SocketHeartbeat(objectOut)).start();
        } catch (Throwable t) {
            // log error and exit gracefully
            logger.error(t.getMessage(), t);
        }
    }
}
