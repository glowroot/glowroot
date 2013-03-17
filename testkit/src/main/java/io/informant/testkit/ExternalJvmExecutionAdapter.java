/**
 * Copyright 2011-2013 the original author or authors.
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
package io.informant.testkit;

import io.informant.MainEntryPoint;
import io.informant.marker.ThreadSafe;
import io.informant.testkit.InformantContainer.ExecutionAdapter;
import io.informant.testkit.SpyingConsoleAppender.MessageCount;
import io.informant.testkit.internal.ClassPath;
import io.informant.testkit.internal.DelegatingJavaagent;

import java.io.File;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.lang.management.ManagementFactory;
import java.lang.management.RuntimeMXBean;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

import org.fest.reflect.core.Reflection;
import org.jboss.netty.bootstrap.ClientBootstrap;
import org.jboss.netty.channel.ChannelHandlerContext;
import org.jboss.netty.channel.ChannelPipeline;
import org.jboss.netty.channel.ChannelPipelineFactory;
import org.jboss.netty.channel.MessageEvent;
import org.jboss.netty.channel.SimpleChannelHandler;
import org.jboss.netty.handler.codec.http.HttpHeaders;
import org.jboss.netty.handler.codec.http.HttpMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import checkers.igj.quals.ReadOnly;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;
import com.ning.http.client.AsyncHttpClient;
import com.ning.http.client.AsyncHttpClientConfig;
import com.ning.http.client.providers.netty.NettyAsyncHttpProviderConfig;

/**
 * @author Trask Stalnaker
 * @since 0.5
 */
@ThreadSafe
class ExternalJvmExecutionAdapter implements ExecutionAdapter {

    private static final Logger logger = LoggerFactory.getLogger(ExternalJvmExecutionAdapter.class);

    private final ServerSocket serverSocket;
    private final SocketCommander socketCommander;
    private final Process process;
    private final ExecutorService consolePipeExecutorService;
    private final AsyncHttpClient asyncHttpClient;
    private final ExternalJvmInformant informant;
    private final Thread shutdownHook;
    private final int uiPort;

    private volatile long numConsoleBytes;

    ExternalJvmExecutionAdapter(@ReadOnly Map<String, String> properties, File dataDir)
            throws Exception {
        // need to start socket listener before spawning process so process can connect to socket
        serverSocket = new ServerSocket(0);
        List<String> command = buildCommand(properties, serverSocket.getLocalPort(), dataDir);
        ProcessBuilder processBuilder = new ProcessBuilder(command);
        processBuilder.redirectErrorStream(true);
        process = processBuilder.start();
        consolePipeExecutorService = Executors.newSingleThreadExecutor();
        consolePipeExecutorService.submit(new Runnable() {
            public void run() {
                try {
                    byte[] buffer = new byte[100];
                    while (true) {
                        int n = process.getInputStream().read(buffer);
                        if (n == -1) {
                            break;
                        }
                        numConsoleBytes += n;
                        System.out.write(buffer, 0, n);
                    }
                } catch (IOException e) {
                    logger.error(e.getMessage(), e);
                }
            }
        });
        Socket socket = serverSocket.accept();
        ObjectOutputStream objectOut = new ObjectOutputStream(socket.getOutputStream());
        ObjectInputStream objectIn = new ObjectInputStream(socket.getInputStream());
        socketCommander = new SocketCommander(objectOut, objectIn);
        uiPort = (Integer) socketCommander.sendCommand(SocketCommandProcessor.GET_PORT_COMMAND);
        asyncHttpClient = createAsyncHttpClient();
        informant = new ExternalJvmInformant(uiPort, asyncHttpClient);
        shutdownHook = new Thread() {
            @Override
            public void run() {
                try {
                    socketCommander.sendKillCommand();
                } catch (IOException e) {
                    logger.error(e.getMessage(), e);
                } catch (InterruptedException e) {
                    logger.error(e.getMessage(), e);
                }
            }
        };
        // unfortunately, ctrl-c during maven test will kill the maven process, but won't kill the
        // forked surefire jvm where the tests are being run
        // (http://jira.codehaus.org/browse/SUREFIRE-413), and so this hook won't get triggered by
        // ctrl-c while running tests under maven
        Runtime.getRuntime().addShutdownHook(shutdownHook);
    }

    public Informant getInformant() {
        return informant;
    }

    public void addExpectedLogMessage(String loggerName, String partialMessage) throws Exception {
        socketCommander.sendCommand(ImmutableList.of(
                SocketCommandProcessor.ADD_EXPECTED_LOG_MESSAGE, loggerName, partialMessage));
    }

    public void executeAppUnderTest(Class<? extends AppUnderTest> appUnderTestClass)
            throws IOException, InterruptedException {
        socketCommander.sendCommand(ImmutableList.of(SocketCommandProcessor.EXECUTE_APP_COMMAND,
                appUnderTestClass.getName()));
    }

    public void interruptAppUnderTest() throws IOException, InterruptedException {
        socketCommander.sendCommand(SocketCommandProcessor.INTERRUPT);
    }

    public void checkAndResetInformant() throws Exception {
        informant.checkAndReset();
    }

    public void close() throws IOException, InterruptedException {
        socketCommander.sendCommand(SocketCommandProcessor.SHUTDOWN_COMMAND);
        socketCommander.close();
        process.waitFor();
        serverSocket.close();
        consolePipeExecutorService.shutdownNow();
        Runtime.getRuntime().removeShutdownHook(shutdownHook);
        asyncHttpClient.close();
    }

    public MessageCount clearLogMessages() throws Exception {
        return (MessageCount) socketCommander
                .sendCommand(SocketCommandProcessor.CLEAR_LOG_MESSAGES);
    }

    void kill() throws IOException, InterruptedException {
        socketCommander.sendKillCommand();
        socketCommander.close();
        process.waitFor();
        consolePipeExecutorService.shutdownNow();
        Runtime.getRuntime().removeShutdownHook(shutdownHook);
    }

    int getUiPort() {
        return uiPort;
    }

    long getNumConsoleBytes() {
        return numConsoleBytes;
    }

    public static void main(String[] args) throws Exception {
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
        // spin a bit to so that caller can capture a trace with <multiple root nodes> if desired
        for (int i = 0; i < 1000; i++) {
            Thread.sleep(1);
            Thread.sleep(1);
            Thread.sleep(1);
        }
        // do not close socket since program is still running after main returns
    }

    private static List<String> buildCommand(Map<String, String> properties, int port, File dataDir)
            throws IOException {
        List<String> command = Lists.newArrayList();
        String javaExecutable = System.getProperty("java.home") + File.separator + "bin"
                + File.separator + "java";
        command.add(javaExecutable);
        String classpath = System.getProperty("java.class.path");
        command.add("-cp");
        command.add(classpath);
        command.addAll(getJavaAgentsFromCurrentJvm());
        File javaagentJarFile = ClassPath.getInformantCoreJarFile();
        if (javaagentJarFile == null) {
            // create jar file in data dir since that gets cleaned up at end of test already
            javaagentJarFile = DelegatingJavaagent.createDelegatingJavaagentJarFile(dataDir);
            command.add("-javaagent:" + javaagentJarFile);
            command.add("-DdelegateJavaagent=" + MainEntryPoint.class.getName());
        } else {
            command.add("-javaagent:" + javaagentJarFile);
        }
        for (Entry<String, String> agentProperty : properties.entrySet()) {
            command.add("-Dinformant." + agentProperty.getKey() + "=" + agentProperty.getValue());
        }
        command.add(ExternalJvmExecutionAdapter.class.getName());
        command.add(Integer.toString(port));
        return command;
    }

    private static List<String> getJavaAgentsFromCurrentJvm() {
        RuntimeMXBean runtimeMXBean = ManagementFactory.getRuntimeMXBean();
        List<String> arguments = runtimeMXBean.getInputArguments();
        List<String> javaAgents = Lists.newArrayList();
        for (String argument : arguments) {
            if (argument.startsWith("-javaagent:")) {
                // pass on the jacoco agent in particular
                javaAgents.add(argument);
            }
        }
        return javaAgents;
    }

    private static AsyncHttpClient createAsyncHttpClient() {
        ExecutorService executorService = Executors.newCachedThreadPool();
        ScheduledExecutorService scheduledExecutor = Executors.newSingleThreadScheduledExecutor();
        AsyncHttpClientConfig.Builder builder = new AsyncHttpClientConfig.Builder()
                .setCompressionEnabled(true)
                .setMaxRequestRetry(0)
                .setExecutorService(executorService)
                .setScheduledExecutorService(scheduledExecutor);
        NettyAsyncHttpProviderConfig providerConfig = new NettyAsyncHttpProviderConfig();
        providerConfig.addProperty(NettyAsyncHttpProviderConfig.BOSS_EXECUTOR_SERVICE,
                executorService);
        builder.setAsyncHttpClientProviderConfig(providerConfig);
        AsyncHttpClient asyncHttpClient = new AsyncHttpClient(builder.build());
        addSaveTheEncodingHandlerToNettyPipeline(asyncHttpClient);
        return asyncHttpClient;
    }

    // Netty's HttpContentDecoder removes the Content-Encoding header during the decompression step
    // which makes it difficult to verify that the response from Informant was compressed
    //
    // this method adds a ChannelHandler to the netty pipeline, before the decompression handler,
    // and saves the original Content-Encoding header into another http header so it can be used
    // later to verify that the response was compressed
    private static void addSaveTheEncodingHandlerToNettyPipeline(AsyncHttpClient asyncHttpClient) {
        // the next release of AsyncHttpClient will include a hook to modify the pipeline without
        // having to resort to this reflection hack, see
        // https://github.com/AsyncHttpClient/async-http-client/pull/205
        ClientBootstrap plainBootstrap = Reflection.field("plainBootstrap")
                .ofType(ClientBootstrap.class).in(asyncHttpClient.getProvider()).get();
        final ChannelPipelineFactory pipelineFactory = plainBootstrap.getPipelineFactory();
        plainBootstrap.setPipelineFactory(new ChannelPipelineFactory() {
            public ChannelPipeline getPipeline() throws Exception {
                ChannelPipeline pipeline = pipelineFactory.getPipeline();
                pipeline.addBefore("inflater", "saveTheEncoding", new SaveTheEncodingHandler());
                return pipeline;
            }
        });
    }

    private static class SaveTheEncodingHandler extends SimpleChannelHandler {
        @Override
        public void messageReceived(ChannelHandlerContext ctx, MessageEvent e) {
            Object msg = e.getMessage();
            if (msg instanceof HttpMessage) {
                HttpMessage m = (HttpMessage) msg;
                String contentEncoding = m.getHeader(HttpHeaders.Names.CONTENT_ENCODING);
                if (contentEncoding != null) {
                    m.setHeader("X-Original-Content-Encoding", contentEncoding);
                }
            }
            ctx.sendUpstream(e);
        }
    }
}
