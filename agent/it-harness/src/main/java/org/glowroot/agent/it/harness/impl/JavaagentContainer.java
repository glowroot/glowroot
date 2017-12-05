/*
 * Copyright 2011-2017 the original author or authors.
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
package org.glowroot.agent.it.harness.impl;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.management.ManagementFactory;
import java.lang.management.RuntimeMXBean;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.List;
import java.util.Map.Entry;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.jar.JarEntry;
import java.util.jar.JarInputStream;

import javax.annotation.Nullable;

import com.google.common.base.Joiner;
import com.google.common.base.Splitter;
import com.google.common.base.StandardSystemProperty;
import com.google.common.base.Stopwatch;
import com.google.common.base.Strings;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;
import com.google.common.io.ByteStreams;
import com.google.common.reflect.Reflection;
import com.google.common.util.concurrent.ThreadFactoryBuilder;
import io.grpc.ManagedChannel;
import io.grpc.netty.NegotiationType;
import io.grpc.netty.NettyChannelBuilder;
import io.netty.channel.EventLoopGroup;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.glowroot.agent.AgentPremain;
import org.glowroot.agent.it.harness.AppUnderTest;
import org.glowroot.agent.it.harness.ConfigService;
import org.glowroot.agent.it.harness.Container;
import org.glowroot.agent.it.harness.TempDirs;
import org.glowroot.agent.it.harness.grpc.JavaagentServiceGrpc;
import org.glowroot.agent.it.harness.grpc.JavaagentServiceGrpc.JavaagentServiceBlockingStub;
import org.glowroot.agent.it.harness.grpc.JavaagentServiceOuterClass.AppUnderTestClassName;
import org.glowroot.agent.it.harness.grpc.JavaagentServiceOuterClass.Void;
import org.glowroot.wire.api.model.TraceOuterClass.Trace;

import static com.google.common.base.Preconditions.checkNotNull;
import static java.util.concurrent.TimeUnit.SECONDS;

public class JavaagentContainer implements Container {

    private static final boolean XDEBUG = Boolean.getBoolean("glowroot.test.xdebug");

    private static final Logger logger = LoggerFactory.getLogger(JavaagentContainer.class);

    static {
        Reflection.initialize(InitLogging.class);
    }

    private final File testDir;
    private final boolean deleteTestDirOnClose;

    private final ServerSocket heartbeatListenerSocket;
    private final ExecutorService heartbeatListenerExecutor;
    private final @Nullable GrpcServerWrapper server;
    private final EventLoopGroup eventLoopGroup;
    private final ExecutorService executor;
    private final ManagedChannel channel;
    private final @Nullable TraceCollector traceCollector;
    private final JavaagentServiceBlockingStub javaagentService;
    private final ExecutorService consolePipeExecutor;
    private final Process process;
    private final ConsoleOutputPipe consoleOutputPipe;
    private final @Nullable ConfigServiceImpl configService;
    private final Thread shutdownHook;

    public static JavaagentContainer create() throws Exception {
        return new JavaagentContainer(null, false, ImmutableList.<String>of());
    }

    public static JavaagentContainer create(File testDir) throws Exception {
        return new JavaagentContainer(testDir, false, ImmutableList.<String>of());
    }

    public static JavaagentContainer createWithExtraJvmArgs(List<String> extraJvmArgs)
            throws Exception {
        return new JavaagentContainer(null, false, extraJvmArgs);
    }

    public JavaagentContainer(@Nullable File testDir, boolean embedded, List<String> extraJvmArgs)
            throws Exception {
        if (testDir == null) {
            this.testDir = TempDirs.createTempDir("glowroot-test-dir");
            deleteTestDirOnClose = true;
        } else {
            this.testDir = testDir;
            deleteTestDirOnClose = false;
        }

        // need to start heartbeat socket listener before spawning process
        heartbeatListenerSocket = new ServerSocket(0);
        heartbeatListenerExecutor = Executors.newSingleThreadExecutor();
        heartbeatListenerExecutor.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    // TODO report checker framework issue that occurs without checkNotNull
                    Socket socket = checkNotNull(heartbeatListenerSocket).accept();
                    InputStream socketIn = socket.getInputStream();
                    while (socketIn.read() != -1) {
                    }
                } catch (IOException e) {
                    logger.error(e.getMessage(), e);
                }
            }
        });

        boolean pointingToCentral = false;
        for (String extraJvmArg : extraJvmArgs) {
            if (extraJvmArg.startsWith("-Dglowroot.collector.address=")) {
                pointingToCentral = true;
                break;
            }
        }
        int collectorPort;
        if (embedded || pointingToCentral) {
            collectorPort = 0;
            traceCollector = null;
            server = null;
        } else {
            collectorPort = LocalContainer.getAvailablePort();
            traceCollector = new TraceCollector();
            server = new GrpcServerWrapper(traceCollector, collectorPort);
        }
        int javaagentServicePort = LocalContainer.getAvailablePort();
        List<String> command = buildCommand(heartbeatListenerSocket.getLocalPort(), collectorPort,
                javaagentServicePort, this.testDir, extraJvmArgs);
        ProcessBuilder processBuilder = new ProcessBuilder(command);
        processBuilder.redirectErrorStream(true);
        Process process = processBuilder.start();
        consolePipeExecutor = Executors.newSingleThreadExecutor();
        InputStream in = process.getInputStream();
        // process.getInputStream() only returns null if ProcessBuilder.redirectOutput() is used
        // to redirect output to a file
        checkNotNull(in);
        consoleOutputPipe = new ConsoleOutputPipe(in, System.out);
        consolePipeExecutor.submit(consoleOutputPipe);
        this.process = process;

        eventLoopGroup = EventLoopGroups.create("Glowroot-IT-Harness*-GRPC-Worker-ELG");
        executor = Executors.newCachedThreadPool(
                new ThreadFactoryBuilder()
                        .setDaemon(true)
                        .setNameFormat("Glowroot-IT-Harness*-GRPC-Executor-%d")
                        .build());
        channel = NettyChannelBuilder.forAddress("localhost", javaagentServicePort)
                .eventLoopGroup(eventLoopGroup)
                .executor(executor)
                .negotiationType(NegotiationType.PLAINTEXT)
                .build();

        Stopwatch stopwatch = Stopwatch.createStarted();
        // this can take a while on slow travis ci build machines
        while (stopwatch.elapsed(SECONDS) < 30) {
            try {
                JavaagentServiceBlockingStub javaagentService =
                        JavaagentServiceGrpc.newBlockingStub(channel)
                                .withCompression("gzip");
                javaagentService.ping(Void.getDefaultInstance());
                break;
            } catch (Exception e) {
                logger.debug(e.getMessage(), e);
            }
            Thread.sleep(100);
        }
        javaagentService = JavaagentServiceGrpc.newBlockingStub(channel)
                .withCompression("gzip");
        if (server == null) {
            configService = null;
            javaagentService.setSlowThresholdToZero(Void.getDefaultInstance());
        } else {
            configService = new ConfigServiceImpl(server, true);
            // need to set through config service so config service can keep track of changes,
            // otherwise it will clobber slow threshold value on next update through config service
            configService.setSlowThresholdToZero();
        }
        shutdownHook = new ShutdownHookThread(javaagentService);
        // unfortunately, ctrl-c during maven test will kill the maven process, but won't kill the
        // forked surefire jvm where the tests are being run
        // (http://jira.codehaus.org/browse/SUREFIRE-413), and so this hook won't get triggered by
        // ctrl-c while running tests under maven
        Runtime.getRuntime().addShutdownHook(shutdownHook);
    }

    @Override
    public ConfigService getConfigService() {
        checkNotNull(configService);
        return configService;
    }

    @Override
    public void addExpectedLogMessage(String loggerName, String partialMessage) throws Exception {
        checkNotNull(traceCollector);
        traceCollector.addExpectedLogMessage(loggerName, partialMessage);
    }

    @Override
    public Trace execute(Class<? extends AppUnderTest> appClass) throws Exception {
        checkNotNull(traceCollector);
        executeInternal(appClass);
        // extra long wait time is needed for StackOverflowOOMIT on slow travis ci machines since it
        // can sometimes take a long time for that large trace to be serialized and transferred
        Trace trace = traceCollector.getCompletedTrace(20, SECONDS);
        traceCollector.clearTrace();
        return trace;
    }

    @Override
    public Trace execute(Class<? extends AppUnderTest> appClass, String transactionType)
            throws Exception {
        checkNotNull(traceCollector);
        executeInternal(appClass);
        // extra long wait time is needed for StackOverflowOOMIT on slow travis ci machines since it
        // can sometimes take a long time for that large trace to be serialized and transferred
        Trace trace = traceCollector.getCompletedTrace(transactionType, 20, SECONDS);
        traceCollector.clearTrace();
        return trace;
    }

    @Override
    public void executeNoExpectedTrace(Class<? extends AppUnderTest> appClass) throws Exception {
        executeInternal(appClass);
        // give a short time to see if trace gets collected
        Thread.sleep(10);
        if (traceCollector != null && traceCollector.hasTrace()) {
            throw new IllegalStateException("Trace was collected when none was expected");
        }
    }

    @Override
    public void interruptAppUnderTest() throws Exception {
        javaagentService.interruptApp(Void.getDefaultInstance());
    }

    @Override
    public Trace getCollectedPartialTrace() throws InterruptedException {
        checkNotNull(traceCollector);
        return traceCollector.getPartialTrace(10, SECONDS);
    }

    @Override
    public void checkAndReset() throws Exception {
        if (configService == null) {
            javaagentService.resetConfig(Void.getDefaultInstance());
        } else {
            // need to reset through config service so config service can keep track of changes,
            // otherwise it will clobber the reset config on next update through config service
            configService.resetConfig();
        }
        if (traceCollector != null) {
            traceCollector.checkAndResetLogMessages();
        }
    }

    @Override
    public void close() throws Exception {
        javaagentService.shutdown(Void.getDefaultInstance());
        javaagentService.kill(Void.getDefaultInstance());
        channel.shutdown();
        if (!channel.awaitTermination(10, SECONDS)) {
            throw new IllegalStateException("Could not terminate channel");
        }
        executor.shutdown();
        if (!executor.awaitTermination(10, SECONDS)) {
            throw new IllegalStateException("Could not terminate executor");
        }
        if (!eventLoopGroup.shutdownGracefully(0, 0, SECONDS).await(10, SECONDS)) {
            throw new IllegalStateException("Could not terminate event loop group");
        }
        if (server != null) {
            server.close();
        }
        process.waitFor();
        consolePipeExecutor.shutdown();
        if (!consolePipeExecutor.awaitTermination(10, SECONDS)) {
            throw new IllegalStateException("Could not terminate executor");
        }
        heartbeatListenerExecutor.shutdown();
        if (!heartbeatListenerExecutor.awaitTermination(10, SECONDS)) {
            throw new IllegalStateException("Could not terminate executor");
        }
        heartbeatListenerSocket.close();
        Runtime.getRuntime().removeShutdownHook(shutdownHook);
        if (deleteTestDirOnClose) {
            TempDirs.deleteRecursively(testDir);
        }
    }

    private void executeInternal(Class<? extends AppUnderTest> appUnderTestClass) {
        javaagentService.executeApp(AppUnderTestClassName.newBuilder()
                .setValue(appUnderTestClass.getName())
                .build());
    }

    private static List<String> buildCommand(int heartbeatPort, int collectorPort,
            int javaagentServicePort, File testDir, List<String> extraJvmArgs) throws Exception {
        List<String> command = Lists.newArrayList();
        String javaExecutable = StandardSystemProperty.JAVA_HOME.value() + File.separator + "bin"
                + File.separator + "java";
        command.add(javaExecutable);
        boolean hasXmx = false;
        for (String extraJvmArg : extraJvmArgs) {
            command.add(extraJvmArg);
            if (extraJvmArg.startsWith("-Xmx")) {
                hasXmx = true;
            }
        }
        // it is important for jacoco javaagent to be prior to glowroot javaagent so that jacoco
        // will use original class bytes to form its class id at runtime which will then match up
        // with the class id at analysis time
        command.addAll(getJacocoArgsFromCurrentJvm());
        String classpath = Strings.nullToEmpty(StandardSystemProperty.JAVA_CLASS_PATH.value());
        List<String> bootPaths = Lists.newArrayList();
        List<String> paths = Lists.newArrayList();
        List<String> maybeShadedInsideAgentJars = Lists.newArrayList();
        List<String> maybeBootstrapJar = Lists.newArrayList();
        File javaagentJarFile = null;
        for (String path : Splitter.on(File.pathSeparatorChar).split(classpath)) {
            File file = new File(path);
            String name = file.getName();
            boolean agentClasses = false;
            if (name.equals("classes") && !file.getAbsolutePath().endsWith(File.separator
                    + "it-harness" + File.separator + "target" + File.separator + "classes")) {
                agentClasses = true;
            }
            if (name.matches("glowroot-agent-core-[0-9.]+(-SNAPSHOT)?.jar")) {
                javaagentJarFile = file;
            } else if (name.matches("glowroot-common-[0-9.]+(-SNAPSHOT)?.jar")
                    || name.matches("glowroot-wire-api-[0-9.]+(-SNAPSHOT)?.jar")
                    || name.matches("glowroot-agent-api-[0-9.]+(-SNAPSHOT)?.jar")
                    || name.matches("glowroot-agent-plugin-api-[0-9.]+(-SNAPSHOT)?.jar")) {
                // these artifacts should not be present since glowroot-agent-core shades them
                // but maven 3.3.1/3.3.3 are not using the dependency reduced pom during downstream
                // module builds, which causes the glowroot artifacts to be included
                // when running "mvn clean install" from the project root, see MSHADE-206
                maybeShadedInsideAgentJars.add(path);
            } else if (agentClasses) {
                bootPaths.add(path);
            } else if (name.matches("glowroot-agent-it-harness-[0-9.]+(-SNAPSHOT)?\\.jar")) {
                paths.add(path);
            } else if (file.getAbsolutePath().endsWith(File.separator + "it-harness"
                    + File.separator + "target" + File.separator + "classes")) {
                paths.add(path);
            } else if (name.endsWith(".jar") && file.getAbsolutePath()
                    .endsWith(File.separator + "target" + File.separator + name)) {
                bootPaths.add(path);
            } else if (name.matches("glowroot-.*\\.jar")) {
                bootPaths.add(path);
            } else if (name.matches("guava-.*\\.jar")) {
                // several plugins use guava
                bootPaths.add(path);
            } else if (name.matches("asm-.*\\.jar")
                    || name.matches("compress-lzf-.*\\.jar")
                    || name.matches("grpc-.*\\.jar")
                    || name.matches("opencensus-.*\\.jar")
                    || name.matches("guava-.*\\.jar")
                    || name.matches("h2-.*\\.jar")
                    || name.matches("HdrHistogram-.*\\.jar")
                    || name.matches("instrumentation-api-.*\\.jar")
                    || name.matches("jackson-.*\\.jar")
                    || name.matches("javax.servlet-api-.*\\.jar")
                    || name.matches("jzlib-.*\\.jar")
                    || name.matches("logback-.*\\.jar")
                    || name.matches("mailapi-.*\\.jar")
                    || name.matches("netty-.*\\.jar")
                    || name.matches("protobuf-java-.*\\.jar")
                    || name.matches("slf4j-api-.*\\.jar")
                    || name.matches("smtp-.*\\.jar")
                    || name.matches("value-.*\\.jar")
                    || name.matches("error_prone_annotations-.*\\.jar")
                    || name.matches("jsr305-.*\\.jar")) {
                // these are glowroot-agent-core transitive dependencies
                maybeBootstrapJar.add(path);
            } else {
                paths.add(path);
            }
        }
        if (javaagentJarFile == null) {
            bootPaths.addAll(maybeBootstrapJar);
        } else {
            boolean shaded = false;
            JarInputStream jarIn = new JarInputStream(new FileInputStream(javaagentJarFile));
            try {
                JarEntry jarEntry;
                while ((jarEntry = jarIn.getNextJarEntry()) != null) {
                    if (jarEntry.getName().startsWith("org/glowroot/agent/shaded/")) {
                        shaded = true;
                        break;
                    }
                }
            } finally {
                jarIn.close();
            }
            if (shaded) {
                paths.addAll(maybeBootstrapJar);
            } else {
                bootPaths.addAll(maybeBootstrapJar);
                bootPaths.addAll(maybeShadedInsideAgentJars);
            }
        }
        command.add("-Xbootclasspath/a:" + Joiner.on(File.pathSeparatorChar).join(bootPaths));
        command.add("-classpath");
        command.add(Joiner.on(File.pathSeparatorChar).join(paths));
        if (javaagentJarFile == null) {
            // create jar file in data dir since that gets cleaned up at end of test already
            javaagentJarFile = DelegatingJavaagent.createDelegatingJavaagentJarFile(testDir);
            command.add("-javaagent:" + javaagentJarFile);
            command.add("-DdelegateJavaagent=" + AgentPremain.class.getName());
        } else {
            command.add("-javaagent:" + javaagentJarFile);
        }
        command.add("-Dglowroot.test.dir=" + testDir.getAbsolutePath());
        if (collectorPort != 0) {
            command.add("-Dglowroot.collector.address=localhost:" + collectorPort);
        }
        // this is used inside low-entropy docker containers
        String sourceOfRandomness = System.getProperty("java.security.egd");
        if (sourceOfRandomness != null) {
            command.add("-Djava.security.egd=" + sourceOfRandomness);
        }
        if (!hasXmx) {
            command.add("-Xmx" + Runtime.getRuntime().maxMemory());
        }
        // leave as much memory as possible to old gen
        command.add("-XX:NewRatio=20");
        for (Entry<Object, Object> entry : System.getProperties().entrySet()) {
            Object keyObject = entry.getKey();
            if (!(keyObject instanceof String)) {
                continue;
            }
            String key = (String) keyObject;
            if (key.startsWith("glowroot.internal.") || key.startsWith("glowroot.test.")) {
                command.add("-D" + key + "=" + entry.getValue());
            }
        }
        if (XDEBUG) {
            command.add("-Xdebug");
            command.add("-agentlib:jdwp=transport=dt_socket,address=8000,server=y,suspend=y");
        }
        command.add(JavaagentMain.class.getName());
        command.add(Integer.toString(heartbeatPort));
        command.add(Integer.toString(javaagentServicePort));
        return command;
    }

    private static List<String> getJacocoArgsFromCurrentJvm() {
        RuntimeMXBean runtimeMXBean = ManagementFactory.getRuntimeMXBean();
        List<String> arguments = runtimeMXBean.getInputArguments();
        List<String> jacocoArgs = Lists.newArrayList();
        for (String argument : arguments) {
            if (argument.startsWith("-javaagent:") && argument.contains("jacoco")) {
                jacocoArgs.add(argument + ",inclbootstrapclasses=true,includes=org.glowroot.*");
                break;
            }
        }
        return jacocoArgs;
    }

    private static class ShutdownHookThread extends Thread {

        private final JavaagentServiceBlockingStub javaagentService;

        private ShutdownHookThread(JavaagentServiceBlockingStub javaagentService) {
            this.javaagentService = javaagentService;
        }

        @Override
        public void run() {
            try {
                javaagentService.kill(Void.getDefaultInstance());
            } catch (Exception e) {
                logger.error(e.getMessage(), e);
            }
        }
    }

    private static class ConsoleOutputPipe implements Runnable {

        private final InputStream in;
        private final OutputStream out;

        private ConsoleOutputPipe(InputStream in, OutputStream out) {
            this.in = in;
            this.out = out;
        }

        @Override
        public void run() {
            try {
                ByteStreams.copy(in, out);
            } catch (IOException e) {
                logger.error(e.getMessage(), e);
            }
        }
    }
}
