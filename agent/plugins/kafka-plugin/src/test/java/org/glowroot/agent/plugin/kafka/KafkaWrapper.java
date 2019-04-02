/**
 * Copyright 2018-2019 the original author or authors.
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
package org.glowroot.agent.plugin.kafka;

import java.io.EOFException;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URL;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import com.google.common.base.Joiner;
import com.google.common.collect.Lists;
import com.google.common.io.Files;
import org.rauschig.jarchivelib.ArchiveFormat;
import org.rauschig.jarchivelib.Archiver;
import org.rauschig.jarchivelib.ArchiverFactory;
import org.rauschig.jarchivelib.CompressionType;

import org.glowroot.agent.it.harness.TempDirs;

import static com.google.common.base.Charsets.UTF_8;
import static java.util.concurrent.TimeUnit.SECONDS;

class KafkaWrapper {

    // Kafka 2.0.0+ requires Java 8+
    private static final String KAFKA_VERSION = "1.1.1";
    private static final String SCALA_VERSION = "2.11";

    private static Process zookeeperProcess;
    private static Process kafkaProcess;
    private static ExecutorService consolePipeExecutorService;

    static void start() throws Exception {
        File baseDir = new File("kafka");
        File kafkaDir = new File(baseDir, "kafka_" + SCALA_VERSION + "-" + KAFKA_VERSION);
        if (!kafkaDir.exists()) {
            try {
                downloadAndExtract(baseDir, kafkaDir);
            } catch (EOFException e) {
                // partial download, try again
                System.out.println("Retrying...");
                downloadAndExtract(baseDir, kafkaDir);
            }
        }
        List<String> zookeeperCommand = buildZookeeperCommandLine(kafkaDir);
        ProcessBuilder zookeeperProcessBuilder = new ProcessBuilder(zookeeperCommand);
        zookeeperProcessBuilder.directory(kafkaDir);
        zookeeperProcessBuilder.redirectErrorStream(true);
        zookeeperProcess = zookeeperProcessBuilder.start();
        consolePipeExecutorService = Executors.newSingleThreadExecutor();
        consolePipeExecutorService
                .submit(new ConsoleOutputPipe(zookeeperProcess.getInputStream(), System.out));

        List<String> kafkaCommand = buildKafkaCommandLine(kafkaDir);
        ProcessBuilder kafkaProcessBuilder = new ProcessBuilder(kafkaCommand);
        kafkaProcessBuilder.directory(kafkaDir);
        kafkaProcessBuilder.redirectErrorStream(true);
        kafkaProcess = kafkaProcessBuilder.start();
        consolePipeExecutorService = Executors.newSingleThreadExecutor();
        consolePipeExecutorService
                .submit(new ConsoleOutputPipe(kafkaProcess.getInputStream(), System.out));

        waitForKafka();
        Thread.sleep(10000);
    }

    static void stop() throws Exception {
        kafkaProcess.destroy();
        kafkaProcess.waitFor();
        zookeeperProcess.destroy();
        zookeeperProcess.waitFor();
        // delete zookeeper state, otherwise subsequent runs can fail with "Error while creating
        // ephemeral at /brokers/ids/0, node already exists and owner does not match current
        // session"
        TempDirs.deleteRecursively(new File("target/zookeeper"));
        consolePipeExecutorService.shutdown();
        if (!consolePipeExecutorService.awaitTermination(30, SECONDS)) {
            throw new IllegalStateException("Could not terminate executor");
        }
    }

    private static void downloadAndExtract(File baseDir, File kafkaDir) throws IOException {
        // using System.out to make sure user sees why there is a big delay here
        System.out.print("Downloading Kafka " + KAFKA_VERSION + "...");
        URL url = new URL("https://archive.apache.org/dist/kafka/" + KAFKA_VERSION + "/kafka_"
                + SCALA_VERSION + "-" + KAFKA_VERSION + ".tgz");
        InputStream in = url.openStream();
        File archiveFile = File.createTempFile("kafka_" + SCALA_VERSION + "-" + KAFKA_VERSION + "-",
                ".tar.gz");
        Files.asByteSink(archiveFile).writeFrom(in);
        in.close();
        Archiver archiver = ArchiverFactory.createArchiver(ArchiveFormat.TAR, CompressionType.GZIP);
        archiver.extract(archiveFile, baseDir);
        archiveFile.delete();
        System.out.println(" OK");

        File configDir = new File(kafkaDir, "config");
        // put zookeeper state under target directory so it can be cleaned up, otherwise it
        // maintains kafka connection info and can cause subsequent runs to fail with "Error while
        // creating ephemeral at /brokers/ids/0, node already exists and owner does not match
        // current session"
        File zookeeperPropertiesFile = new File(configDir, "zookeeper.properties");
        String contents = Files.asCharSource(zookeeperPropertiesFile, UTF_8).read();
        contents = contents.replace("dataDir=/tmp/zookeeper", "dataDir=../../target/zookeeper");
        Files.asCharSink(zookeeperPropertiesFile, UTF_8).write(contents);
    }

    private static List<String> buildZookeeperCommandLine(File kafkaDir) {
        List<String> command = Lists.newArrayList();
        String javaExecutable =
                System.getProperty("java.home") + File.separator + "bin" + File.separator + "java";
        command.add(javaExecutable);
        command.add("-Xmx256m");
        // leave as much memory as possible to old gen
        command.add("-XX:NewRatio=20");
        command.add("-Djava.awt.headless=true");
        command.add("-Dkafka.logs.dir=logs");
        command.add("-Dlog4j.configuration=file:config/log4j.properties");
        command.add("-cp");
        command.add(buildClasspath(kafkaDir));
        // this is used inside low-entropy docker containers
        String sourceOfRandomness = System.getProperty("java.security.egd");
        if (sourceOfRandomness != null) {
            command.add("-Djava.security.egd=" + sourceOfRandomness);
        }
        command.add("org.apache.zookeeper.server.quorum.QuorumPeerMain");
        command.add("config/zookeeper.properties");
        return command;
    }

    private static List<String> buildKafkaCommandLine(File kafkaDir) {
        List<String> command = Lists.newArrayList();
        String javaExecutable =
                System.getProperty("java.home") + File.separator + "bin" + File.separator + "java";
        command.add(javaExecutable);
        command.add("-Xmx256m");
        // leave as much memory as possible to old gen
        command.add("-XX:NewRatio=20");
        command.add("-Djava.awt.headless=true");
        command.add("-Dkafka.logs.dir=logs");
        command.add("-Dlog4j.configuration=file:config/log4j.properties");
        command.add("-cp");
        command.add(buildClasspath(kafkaDir));
        // this is used inside low-entropy docker containers
        String sourceOfRandomness = System.getProperty("java.security.egd");
        if (sourceOfRandomness != null) {
            command.add("-Djava.security.egd=" + sourceOfRandomness);
        }
        command.add("kafka.Kafka");
        command.add("config/server.properties");
        return command;
    }

    private static String buildClasspath(File kafkaDir) {
        File libsDir = new File(kafkaDir, "libs");
        List<String> classpath = Lists.newArrayList();
        for (File file : libsDir.listFiles()) {
            if (file.getName().endsWith(".jar")) {
                classpath.add(file.getAbsolutePath());
            }
        }
        return Joiner.on(File.pathSeparator).join(classpath);
    }

    private static void waitForKafka() throws Exception {
        // FIXME this was code for Elasticsearch
        // TransportClient client = Util.client(new InetSocketAddress("127.0.0.1", 9300));
        // while (client.connectedNodes().isEmpty()) {
        // SECONDS.sleep(1);
        // }
        // client.close();
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
            byte[] buffer = new byte[100];
            try {
                while (true) {
                    int n = in.read(buffer);
                    if (n == -1) {
                        break;
                    }
                    out.write(buffer, 0, n);
                }
            } catch (IOException e) {
            }
        }
    }
}
