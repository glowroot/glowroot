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
import java.net.Socket;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.concurrent.ExecutorService;

import javax.annotation.concurrent.ThreadSafe;

import org.informantproject.api.Logger;
import org.informantproject.api.LoggerFactory;
import org.informantproject.core.util.DaemonExecutors;
import org.informantproject.core.util.UnitTests;
import org.informantproject.testkit.InformantContainer.ExecutionAdapter;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;
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

    ExternalJvmExecutionAdapter(Map<String, String> properties) throws IOException {
        String classpath = System.getProperty("java.class.path");
        String path = System.getProperty("java.home") + File.separator + "bin" + File.separator
                + "java";
        String javaagentArg = "-javaagent:" + UnitTests.findInformantCoreJarFile();
        socketCommander = new SocketCommander();
        List<String> command = Lists.newArrayList();
        command.add(path);
        command.add("-cp");
        command.add(classpath);
        command.add(javaagentArg);
        for (Entry<String, String> agentProperty : properties.entrySet()) {
            command.add("-Dinformant." + agentProperty.getKey() + "=" + agentProperty.getValue());
        }
        command.add(ExternalJvmExecutionAdapter.class.getName());
        command.add(Integer.toString(socketCommander.getLocalPort()));
        ProcessBuilder processBuilder = new ProcessBuilder(command);
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
    }

    public int getPort() throws IOException, InterruptedException {
        return (Integer) socketCommander.sendCommand(SocketCommandProcessor.GET_PORT_COMMAND);
    }

    public void executeAppUnderTest(Class<? extends AppUnderTest> appUnderTestClass,
            String threadName) throws IOException, InterruptedException {

        socketCommander.sendCommand(ImmutableList.of(SocketCommandProcessor.EXECUTE_APP_COMMAND,
                appUnderTestClass.getName(), threadName));
    }

    public void close() throws IOException, InterruptedException {
        Object response = socketCommander.sendCommand(SocketCommandProcessor.SHUTDOWN_COMMAND);
        if (response.equals(SocketCommandProcessor.EXCEPTION_RESPONSE)) {
            throw new IllegalStateException("Exception occurred inside external JVM");
        }
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
