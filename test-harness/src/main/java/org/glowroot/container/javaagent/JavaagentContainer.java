/*
 * Copyright 2011-2014 the original author or authors.
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
package org.glowroot.container.javaagent;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.OutputStream;
import java.lang.management.ManagementFactory;
import java.lang.management.RuntimeMXBean;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Pattern;

import com.google.common.base.Charsets;
import com.google.common.base.Joiner;
import com.google.common.base.Splitter;
import com.google.common.base.StandardSystemProperty;
import com.google.common.base.Stopwatch;
import com.google.common.base.Strings;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;
import com.google.common.io.Files;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.glowroot.Agent;
import org.glowroot.GlowrootModule;
import org.glowroot.MainEntryPoint;
import org.glowroot.common.SpyingLogbackFilter.MessageCount;
import org.glowroot.config.ConfigService.OptimisticLockException;
import org.glowroot.config.TraceConfig;
import org.glowroot.container.AppUnderTest;
import org.glowroot.container.Container;
import org.glowroot.container.TempDirs;
import org.glowroot.container.config.ConfigService;
import org.glowroot.container.javaagent.JavaagentConfigService.GetUiPortCommand;
import org.glowroot.container.trace.TraceService;

import static com.google.common.base.Preconditions.checkNotNull;
import static java.util.concurrent.TimeUnit.SECONDS;

/**
 * @author Trask Stalnaker
 * @since 0.5
 */
public class JavaagentContainer implements Container {

    private static final Logger logger = LoggerFactory.getLogger(JavaagentContainer.class);

    private final File dataDir;
    private final boolean deleteDataDirOnClose;
    private final boolean shared;

    private final ServerSocket serverSocket;
    private final SocketCommander socketCommander;
    private final ExecutorService consolePipeExecutorService;
    private final Process process;
    private final ConsoleOutputPipe consoleOutputPipe;
    private final JavaagentHttpClient httpClient;
    private final JavaagentConfigService configService;
    private final JavaagentTraceService traceService;
    private final Thread shutdownHook;

    public static JavaagentContainer createWithFileDb() throws Exception {
        return new JavaagentContainer(null, true, false, false, ImmutableList.<String>of());
    }

    public static JavaagentContainer createWithFileDb(File dataDir) throws Exception {
        return new JavaagentContainer(dataDir, true, false, false, ImmutableList.<String>of());
    }

    public JavaagentContainer(@Nullable File dataDir, boolean useFileDb, boolean shared,
            final boolean captureConsoleOutput, List<String> extraJvmArgs) throws Exception {
        if (dataDir == null) {
            this.dataDir = TempDirs.createTempDir("glowroot-test-datadir");
            deleteDataDirOnClose = true;
        } else {
            this.dataDir = dataDir;
            deleteDataDirOnClose = false;
        }
        this.shared = shared;
        // need to start socket listener before spawning process so process can connect to socket
        serverSocket = new ServerSocket(0);
        // default to port 0 (any available)
        File configFile = new File(this.dataDir, "config.json");
        if (!configFile.exists()) {
            Files.write("{\"ui\":{\"port\":0}}", configFile, Charsets.UTF_8);
        }
        List<String> command =
                buildCommand(serverSocket.getLocalPort(), this.dataDir, useFileDb, extraJvmArgs);
        ProcessBuilder processBuilder = new ProcessBuilder(command);
        processBuilder.redirectErrorStream(true);
        final Process process = processBuilder.start();
        consolePipeExecutorService = Executors.newSingleThreadExecutor();
        InputStream in = process.getInputStream();
        // process.getInputStream() only returns null if ProcessBuilder.redirectOutput() is used
        // to redirect output to a file
        checkNotNull(in);
        consoleOutputPipe = new ConsoleOutputPipe(in, System.out, captureConsoleOutput);
        consolePipeExecutorService.submit(consoleOutputPipe);
        this.process = process;
        Socket socket = serverSocket.accept();
        ObjectOutputStream objectOut = new ObjectOutputStream(socket.getOutputStream());
        ObjectInputStream objectIn = new ObjectInputStream(socket.getInputStream());
        socketCommander = new SocketCommander(objectOut, objectIn);
        int uiPort;
        try {
            uiPort = getUiPort(socketCommander);
        } catch (StartupFailedException e) {
            // clean up and re-throw
            socketCommander.sendCommand(SocketCommandProcessor.SHUTDOWN);
            socketCommander.close();
            process.waitFor();
            serverSocket.close();
            consolePipeExecutorService.shutdownNow();
            throw e;
        }
        httpClient = new JavaagentHttpClient(uiPort);
        this.configService = new JavaagentConfigService(httpClient,
                new JavaagentContainerGetUiPort(socketCommander));
        traceService = new JavaagentTraceService(httpClient);
        shutdownHook = new ShutdownHookThread(socketCommander);
        // unfortunately, ctrl-c during maven test will kill the maven process, but won't kill the
        // forked surefire jvm where the tests are being run
        // (http://jira.codehaus.org/browse/SUREFIRE-413), and so this hook won't get triggered by
        // ctrl-c while running tests under maven
        Runtime.getRuntime().addShutdownHook(shutdownHook);
    }

    @Override
    public ConfigService getConfigService() {
        return configService;
    }

    @Override
    public void addExpectedLogMessage(String loggerName, String partialMessage) throws Exception {
        socketCommander.sendCommand(SocketCommandProcessor.ADD_EXPECTED_LOG_MESSAGE, loggerName,
                partialMessage);
    }

    @Override
    public void executeAppUnderTest(Class<? extends AppUnderTest> appUnderTestClass)
            throws Exception {
        socketCommander.sendCommand(SocketCommandProcessor.EXECUTE_APP,
                appUnderTestClass.getName());
        // wait for all traces to be stored
        Stopwatch stopwatch = Stopwatch.createStarted();
        while (traceService.getNumPendingCompleteTransactions() > 0
                && stopwatch.elapsed(SECONDS) < 5) {
            Thread.sleep(10);
        }
    }

    @Override
    public void interruptAppUnderTest() throws Exception {
        socketCommander.sendCommand(SocketCommandProcessor.INTERRUPT);
    }

    @Override
    public TraceService getTraceService() {
        return traceService;
    }

    @Override
    public int getUiPort() throws Exception {
        return getUiPort(socketCommander);
    }

    @Override
    public void checkAndReset() throws Exception {
        traceService.assertNoActiveTransactions();
        traceService.deleteAll();
        configService.resetAllConfig();
        // traceStoreThresholdMillis=0 is the default for testing
        configService.setTraceStoreThresholdMillis(0);
        // check and reset log messages
        MessageCount logMessageCount = (MessageCount) socketCommander
                .sendCommand(SocketCommandProcessor.CLEAR_LOG_MESSAGES);
        if (logMessageCount == null) {
            throw new AssertionError("Command returned null: "
                    + SocketCommandProcessor.CLEAR_LOG_MESSAGES);
        }
        if (logMessageCount.getExpectedCount() > 0) {
            throw new AssertionError("One or more expected messages were not logged");
        }
        if (logMessageCount.getUnexpectedCount() > 0) {
            throw new AssertionError("One or more unexpected messages were logged");
        }
    }

    @Override
    public void close() throws Exception {
        close(false);
    }

    @Override
    public void close(boolean evenIfShared) throws Exception {
        if (shared && !evenIfShared) {
            // this is the shared container and will be closed at the end of the run
            return;
        }
        socketCommander.sendCommand(SocketCommandProcessor.SHUTDOWN);
        cleanup();
    }

    public void kill() throws Exception {
        socketCommander.sendKillCommand();
        cleanup();
    }

    public List<String> getUnexpectedConsoleLines() {
        List<String> unexpectedLines = Lists.newArrayList();
        Splitter splitter = Splitter.on(Pattern.compile("\r?\n")).omitEmptyStrings();
        String capturedOutput = consoleOutputPipe.getCapturedOutput();
        if (capturedOutput == null) {
            throw new IllegalStateException("Cannot check console lines unless JavaagentContainer"
                    + " was created with captureConsoleOutput=true");
        }
        for (String line : splitter.split(capturedOutput)) {
            if (line.contains("Glowroot started") || line.contains("Glowroot listening")
                    || line.contains("Glowroot plugins loaded")) {
                continue;
            }
            unexpectedLines.add(line);
        }
        return unexpectedLines;
    }

    private void cleanup() throws Exception {
        socketCommander.close();
        process.waitFor();
        serverSocket.close();
        consolePipeExecutorService.shutdownNow();
        Runtime.getRuntime().removeShutdownHook(shutdownHook);
        httpClient.close();
        if (deleteDataDirOnClose) {
            TempDirs.deleteRecursively(dataDir);
        }
    }

    private static int getUiPort(SocketCommander socketCommander) throws Exception {
        Object response = socketCommander.sendCommand(SocketCommandProcessor.GET_PORT);
        if (response == null) {
            throw new AssertionError("Command returned null: " + SocketCommandProcessor.GET_PORT);
        }
        if (response.equals(SocketCommandProcessor.STARTUP_FAILED)) {
            throw new StartupFailedException();
        }
        return (Integer) response;
    }

    public static void main(String... args) throws Exception {
        // traceStoreThresholdMillis=0 is the default for testing
        setTraceStoreThresholdMillisToZero();
        int port = Integer.parseInt(args[0]);
        // socket is never closed since program is still running after main returns
        Socket socket = new Socket((String) null, port);
        ObjectInputStream objectIn = new ObjectInputStream(socket.getInputStream());
        ObjectOutputStream objectOut = new ObjectOutputStream(socket.getOutputStream());
        new Thread(new SocketHeartbeat(objectOut)).start();
        new Thread(new SocketCommandProcessor(objectIn, objectOut)).start();
        // spin a bit to so that caller can capture a trace with <multiple root nodes> if desired
        for (int i = 0; i < 1000; i++) {
            metricMarkerOne();
            metricMarkerTwo();
            Thread.sleep(1);
        }
        // non-daemon threads started above keep jvm alive after main returns
    }

    public static void setTraceStoreThresholdMillisToZero() throws OptimisticLockException,
            IOException {
        GlowrootModule glowrootModule = MainEntryPoint.getGlowrootModule();
        if (glowrootModule == null) {
            // failed to start, e.g. DataSourceLockTest
            return;
        }
        org.glowroot.config.ConfigService configService =
                glowrootModule.getConfigModule().getConfigService();
        TraceConfig traceConfig = configService.getTraceConfig();
        // conditional check is needed to prevent config file timestamp update when testing
        // ConfigFileLastModifiedTest.shouldNotUpdateFileOnStartupIfNoChanges()
        if (traceConfig.getStoreThresholdMillis() != 0) {
            TraceConfig.Overlay overlay = TraceConfig.overlay(traceConfig);
            overlay.setStoreThresholdMillis(0);
            configService.updateTraceConfig(overlay.build(), traceConfig.getVersion());
        }
    }

    private static void metricMarkerOne() throws InterruptedException {
        Thread.sleep(1);
    }

    private static void metricMarkerTwo() throws InterruptedException {
        Thread.sleep(1);
    }

    private static List<String> buildCommand(int containerPort, File dataDir, boolean useFileDb,
            List<String> extraJvmArgs) throws Exception {
        List<String> command = Lists.newArrayList();
        String javaExecutable = StandardSystemProperty.JAVA_HOME.value() + File.separator + "bin"
                + File.separator + "java";
        command.add(javaExecutable);
        command.addAll(extraJvmArgs);
        String classpath = Strings.nullToEmpty(StandardSystemProperty.JAVA_CLASS_PATH.value());
        List<String> paths = Lists.newArrayList();
        File javaagentJarFile = null;
        for (String path : Splitter.on(File.pathSeparatorChar).split(classpath)) {
            File file = new File(path);
            if (file.getName().matches("glowroot-core-[0-9.]+(-SNAPSHOT)?.jar")) {
                javaagentJarFile = file;
            } else {
                paths.add(path);
            }
        }
        command.add("-Xbootclasspath/a:" + Joiner.on(File.pathSeparatorChar).join(paths));
        command.addAll(getJacocoArgsFromCurrentJvm());
        if (javaagentJarFile == null) {
            // create jar file in data dir since that gets cleaned up at end of test already
            javaagentJarFile = DelegatingJavaagent.createDelegatingJavaagentJarFile(dataDir);
            command.add("-javaagent:" + javaagentJarFile);
            command.add("-DdelegateJavaagent=" + Agent.class.getName());
        } else {
            command.add("-javaagent:" + javaagentJarFile);
        }
        command.add("-Dglowroot.data.dir=" + dataDir.getAbsolutePath());
        command.add("-Dglowroot.internal.logging.spy=true");
        if (!useFileDb) {
            command.add("-Dglowroot.internal.h2.memdb=true");
        }
        Integer aggregateInterval =
                Integer.getInteger("glowroot.internal.collector.aggregateInterval");
        if (aggregateInterval != null) {
            command.add("-Dglowroot.internal.collector.aggregateInterval=" + aggregateInterval);
        }
        command.add(JavaagentContainer.class.getName());
        command.add(Integer.toString(containerPort));
        return command;
    }

    private static List<String> getJacocoArgsFromCurrentJvm() {
        RuntimeMXBean runtimeMXBean = ManagementFactory.getRuntimeMXBean();
        List<String> arguments = runtimeMXBean.getInputArguments();
        List<String> jacocoArgs = Lists.newArrayList();
        for (String argument : arguments) {
            if (argument.startsWith("-javaagent:") && argument.contains("jacoco")) {
                jacocoArgs.add(argument);
                jacocoArgs.add("-Djacoco.inclBootstrapClasses=true");
            }
        }
        return jacocoArgs;
    }

    private static class JavaagentContainerGetUiPort implements GetUiPortCommand {

        private final SocketCommander socketCommander;

        private JavaagentContainerGetUiPort(SocketCommander socketCommander) {
            this.socketCommander = socketCommander;
        }

        @Override
        public int getUiPort() throws Exception {
            return JavaagentContainer.getUiPort(socketCommander);
        }
    }

    private static class ShutdownHookThread extends Thread {

        private final SocketCommander socketCommander;

        private ShutdownHookThread(SocketCommander socketCommander) {
            this.socketCommander = socketCommander;
        }

        @Override
        public void run() {
            try {
                socketCommander.sendKillCommand();
            } catch (Exception e) {
                logger.error(e.getMessage(), e);
            }
        }
    }

    private static class ConsoleOutputPipe implements Runnable {

        private final InputStream in;
        private final OutputStream out;
        // the one place ever that StringBuffer's synchronization is useful :-)
        @Nullable
        private final StringBuffer capturedOutput;

        private ConsoleOutputPipe(InputStream in, OutputStream out, boolean captureOutput) {
            this.in = in;
            this.out = out;
            if (captureOutput) {
                capturedOutput = new StringBuffer();
            } else {
                capturedOutput = null;
            }
        }

        @Override
        public void run() {
            byte[] buffer = new byte[100];
            try {
                while (true) {
                    int n = in.read(buffer);
                    if (n == -1) {
                        break;
                    }
                    if (capturedOutput != null) {
                        // intentionally using platform default charset
                        capturedOutput.append(new String(buffer, 0, n));
                    }
                    out.write(buffer, 0, n);
                }
            } catch (IOException e) {
                logger.error(e.getMessage(), e);
            }
        }

        @Nullable
        private String getCapturedOutput() {
            return capturedOutput == null ? null : capturedOutput.toString();
        }
    }
}
