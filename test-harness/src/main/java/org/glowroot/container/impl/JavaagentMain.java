/*
 * Copyright 2014 the original author or authors.
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
package org.glowroot.container.impl;

import java.io.File;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.lang.management.ManagementFactory;
import java.lang.management.RuntimeMXBean;
import java.net.Socket;
import java.util.List;

import com.google.common.base.Joiner;
import com.google.common.base.Splitter;
import com.google.common.base.StandardSystemProperty;
import com.google.common.base.Strings;
import com.google.common.collect.Lists;

import org.glowroot.Agent;
import org.glowroot.GlowrootModule;
import org.glowroot.MainEntryPoint;
import org.glowroot.config.ConfigService.OptimisticLockException;
import org.glowroot.config.TraceConfig;

public class JavaagentMain {

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

    static List<String> buildCommand(int containerPort, File dataDir, boolean useFileDb,
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
        command.add(JavaagentMain.class.getName());
        command.add(Integer.toString(containerPort));
        return command;
    }

    static void setTraceStoreThresholdMillisToZero() throws OptimisticLockException,
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
}
