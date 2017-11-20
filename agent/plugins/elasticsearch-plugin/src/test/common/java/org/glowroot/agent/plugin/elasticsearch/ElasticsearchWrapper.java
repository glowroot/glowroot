/**
 * Copyright 2017 the original author or authors.
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
package org.glowroot.agent.plugin.elasticsearch;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URL;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import com.google.common.base.Charsets;
import com.google.common.base.Joiner;
import com.google.common.collect.Lists;
import com.google.common.io.Files;
import org.elasticsearch.client.transport.TransportClient;
import org.elasticsearch.common.transport.InetSocketTransportAddress;
import org.rauschig.jarchivelib.ArchiveFormat;
import org.rauschig.jarchivelib.Archiver;
import org.rauschig.jarchivelib.ArchiverFactory;
import org.rauschig.jarchivelib.CompressionType;

import static java.util.concurrent.TimeUnit.SECONDS;

class ElasticsearchWrapper {

    private static final String ELASTICSEARCH_VERSION;

    private static Process process;
    private static ExecutorService consolePipeExecutorService;

    static {
        boolean elasticsearch5x;
        try {
            Class.forName("org.elasticsearch.transport.client.PreBuiltTransportClient");
            elasticsearch5x = true;
        } catch (ClassNotFoundException e) {
            elasticsearch5x = false;
        }
        if (elasticsearch5x) {
            ELASTICSEARCH_VERSION = "5.6.4";
        } else {
            ELASTICSEARCH_VERSION = "2.4.6";
        }
    }

    static void start() throws Exception {
        File baseDir = new File("elasticsearch");
        File elasticsearchDir = new File(baseDir, "elasticsearch-" + ELASTICSEARCH_VERSION);
        if (!elasticsearchDir.exists()) {
            downloadAndExtract(baseDir);
        }
        List<String> command = buildCommandLine(elasticsearchDir);
        ProcessBuilder processBuilder = new ProcessBuilder(command);
        processBuilder.directory(new File(elasticsearchDir, "bin"));
        processBuilder.redirectErrorStream(true);
        process = processBuilder.start();
        ConsoleOutputPipe consoleOutputPipe =
                new ConsoleOutputPipe(process.getInputStream(), System.out);
        consolePipeExecutorService = Executors.newSingleThreadExecutor();
        consolePipeExecutorService.submit(consoleOutputPipe);
        waitForElasticsearch();
    }

    static void stop() throws Exception {
        process.destroy();
        consolePipeExecutorService.shutdown();
        if (!consolePipeExecutorService.awaitTermination(30, SECONDS)) {
            throw new IllegalStateException("Could not terminate executor");
        }
    }

    private static void downloadAndExtract(File baseDir) throws IOException {
        // using System.out to make sure user sees why there is a big delay here
        System.out.print("Downloading Elasticsearch " + ELASTICSEARCH_VERSION + " ...");
        URL url;
        if (ELASTICSEARCH_VERSION.startsWith("5.")) {
            url = new URL("https://artifacts.elastic.co/downloads/elasticsearch/elasticsearch-"
                    + ELASTICSEARCH_VERSION + ".tar.gz");
        } else if (ELASTICSEARCH_VERSION.startsWith("2.")) {
            url = new URL("https://download.elastic.co/elasticsearch/release/org/elasticsearch"
                    + "/distribution/tar/elasticsearch/" + ELASTICSEARCH_VERSION + "/elasticsearch-"
                    + ELASTICSEARCH_VERSION + ".tar.gz");
        } else {
            throw new IllegalStateException(
                    "Unexpected Elasticsearch version: " + ELASTICSEARCH_VERSION);
        }
        InputStream in = url.openStream();
        File archiveFile =
                File.createTempFile("elasticsearch-" + ELASTICSEARCH_VERSION + "-", ".tar.gz");
        Files.asByteSink(archiveFile).writeFrom(in);
        in.close();
        Archiver archiver = ArchiverFactory.createArchiver(ArchiveFormat.TAR, CompressionType.GZIP);
        archiver.extract(archiveFile, baseDir);
        archiveFile.delete();
        System.out.println(" OK");

        File elasticsearchDir = new File(baseDir, "elasticsearch-" + ELASTICSEARCH_VERSION);
        File configDir = new File(elasticsearchDir, "config");
        // reduce logging to stdout
        if (ELASTICSEARCH_VERSION.startsWith("5.")) {
            File log4j2PropertiesFile = new File(configDir, "log4j2.properties");
            String contents = Files.asCharSource(log4j2PropertiesFile, Charsets.UTF_8).read();
            contents = contents.replace("rootLogger.level = info", "rootLogger.level = warn");
            Files.asCharSink(log4j2PropertiesFile, Charsets.UTF_8).write(contents);
        } else if (ELASTICSEARCH_VERSION.startsWith("2.")) {
            File loggingYamlFile = new File(configDir, "logging.yml");
            String contents = Files.asCharSource(loggingYamlFile, Charsets.UTF_8).read();
            contents = contents.replace("es.logger.level: INFO", "es.logger.level: WARN");
            contents = contents.replace("action: DEBUG", "action: INFO");
            Files.asCharSink(loggingYamlFile, Charsets.UTF_8).write(contents);
        } else {
            throw new IllegalStateException(
                    "Unexpected Elasticsearch version: " + ELASTICSEARCH_VERSION);
        }
    }

    private static List<String> buildCommandLine(File elasticsearchDir) {
        List<String> command = Lists.newArrayList();
        String javaExecutable =
                System.getProperty("java.home") + File.separator + "bin" + File.separator + "java";
        command.add(javaExecutable);
        command.add("-Djava.awt.headless=true");
        command.add("-Dfile.encoding=UTF-8");
        command.add("-Djna.nosys=true");
        if (ELASTICSEARCH_VERSION.startsWith("5.")) {
            command.add("-Djdk.io.permissionsUseCanonicalPath=true");
            command.add("-Dio.netty.noUnsafe=true");
            command.add("-Dio.netty.noKeySetOptimization=true");
            command.add("-Dio.netty.recycler.maxCapacityPerThread=0");
            command.add("-Dlog4j.shutdownHookEnabled=false");
            command.add("-Dlog4j2.disable.jmx=true");
            command.add("-Dlog4j.skipJansi=true");
            command.add("-Delasticsearch");
        } else if (ELASTICSEARCH_VERSION.startsWith("2.")) {
            command.add("-Des-foreground=yes");
            command.add("-Delasticsearch");
        } else {
            throw new IllegalStateException(
                    "Unexpected Elasticsearch version: " + ELASTICSEARCH_VERSION);
        }
        command.add("-Des.path.home=" + elasticsearchDir.getAbsolutePath());
        command.add("-cp");
        command.add(buildClasspath(elasticsearchDir));
        // this is used inside low-entropy docker containers
        String sourceOfRandomness = System.getProperty("java.security.egd");
        if (sourceOfRandomness != null) {
            command.add("-Djava.security.egd=" + sourceOfRandomness);
        }
        command.add("-Xmx256m");
        // leave as much memory as possible to old gen
        command.add("-XX:NewRatio=20");
        command.add("org.elasticsearch.bootstrap.Elasticsearch");
        if (ELASTICSEARCH_VERSION.startsWith("2.")) {
            command.add("start");
        }
        return command;
    }

    private static String buildClasspath(File elasticsearchDir) {
        File libDir = new File(elasticsearchDir, "lib");
        List<String> classpath = Lists.newArrayList();
        for (File file : libDir.listFiles()) {
            if (file.getName().endsWith(".jar")) {
                classpath.add(file.getAbsolutePath());
            }
        }
        return Joiner.on(File.pathSeparator).join(classpath);
    }

    private static void waitForElasticsearch() throws Exception {
        TransportClient client = Util.client();
        client.addTransportAddress(
                new InetSocketTransportAddress(new InetSocketAddress("127.0.0.1", 9300)));
        while (client.connectedNodes().isEmpty()) {
            Thread.sleep(1000);
        }
        client.close();
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
