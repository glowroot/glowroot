/**
 * Copyright 2015-2017 the original author or authors.
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
package org.glowroot.agent.plugin.cassandra;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URL;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import com.datastax.driver.core.Cluster;
import com.datastax.driver.core.exceptions.NoHostAvailableException;
import com.google.common.base.Charsets;
import com.google.common.base.StandardSystemProperty;
import com.google.common.collect.Lists;
import com.google.common.io.Files;
import org.rauschig.jarchivelib.ArchiveFormat;
import org.rauschig.jarchivelib.Archiver;
import org.rauschig.jarchivelib.ArchiverFactory;
import org.rauschig.jarchivelib.CompressionType;

import static java.util.concurrent.TimeUnit.SECONDS;

// see copies of this class in glowroot-server and glowroot-webdriver-tests
class CassandraWrapper {

    private static final String CASSANDRA_VERSION;
    private static final String JAVA_7_OR_LATER_HOME;

    static {
        if (System.getProperty("os.name").startsWith("Windows")) {
            // Cassandra 2.1 has issues on Windows
            // see https://issues.apache.org/jira/browse/CASSANDRA-10673
            CASSANDRA_VERSION = "2.2.11";
        } else {
            CASSANDRA_VERSION = "2.1.19";
        }
        if (StandardSystemProperty.JAVA_VERSION.value().startsWith("1.6")) {
            JAVA_7_OR_LATER_HOME = System.getProperty("java7.home");
            if (JAVA_7_OR_LATER_HOME == null) {
                throw new IllegalStateException("Cassandra itself requires Java 7+, but this test"
                        + " is running under Java 6, so you must provide -Djava7.home=... (or run"
                        + " this test under Java 7+)");
            }
        } else {
            JAVA_7_OR_LATER_HOME = System.getProperty("java.home");
        }
    }

    private static Process process;
    private static ExecutorService consolePipeExecutorService;

    static void start() throws Exception {
        File baseDir = new File("cassandra");
        File cassandraDir = new File(baseDir, "apache-cassandra-" + CASSANDRA_VERSION);
        if (!cassandraDir.exists()) {
            downloadAndExtract(baseDir);
        }
        List<String> command = buildCommandLine(cassandraDir);
        ProcessBuilder processBuilder = new ProcessBuilder(command);
        processBuilder.directory(new File(cassandraDir, "bin"));
        processBuilder.redirectErrorStream(true);
        process = processBuilder.start();
        ConsoleOutputPipe consoleOutputPipe =
                new ConsoleOutputPipe(process.getInputStream(), System.out);
        consolePipeExecutorService = Executors.newSingleThreadExecutor();
        consolePipeExecutorService.submit(consoleOutputPipe);
        waitForCassandra();
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
        System.out.print("Downloading Cassandra " + CASSANDRA_VERSION + " ...");
        URL url = new URL("http://www.apache.org/dist/cassandra/" + CASSANDRA_VERSION
                + "/apache-cassandra-" + CASSANDRA_VERSION + "-bin.tar.gz");
        InputStream in = url.openStream();
        File archiveFile = File.createTempFile("cassandra-" + CASSANDRA_VERSION + "-", ".tar.gz");
        Files.asByteSink(archiveFile).writeFrom(in);
        in.close();
        Archiver archiver = ArchiverFactory.createArchiver(ArchiveFormat.TAR, CompressionType.GZIP);
        archiver.extract(archiveFile, baseDir);
        archiveFile.delete();
        System.out.println(" OK");

        File cassandraDir = new File(baseDir, "apache-cassandra-" + CASSANDRA_VERSION);
        File confDir = new File(cassandraDir, "conf");
        // reduce logging to stdout
        File logbackXmlFile = new File(confDir, "logback.xml");
        String xml = Files.toString(logbackXmlFile, Charsets.UTF_8);
        xml = xml.replace("<root level=\"INFO\">", "<root level=\"ERROR\">");
        xml = xml.replace("<logger name=\"org.apache.cassandra\" level=\"DEBUG\"/>", "");
        Files.asCharSink(logbackXmlFile, Charsets.UTF_8).write(xml);
        // long timeouts needed on slow travis ci machines
        File yamlFile = new File(confDir, "cassandra.yaml");
        String yaml = Files.toString(yamlFile, Charsets.UTF_8);
        yaml = yaml.replaceAll("(?m)^read_request_timeout_in_ms: .*$",
                "read_request_timeout_in_ms: 30000");
        yaml = yaml.replaceAll("(?m)^write_request_timeout_in_ms: .*$",
                "write_request_timeout_in_ms: 30000");
        Files.asCharSink(yamlFile, Charsets.UTF_8).write(yaml);
    }

    private static List<String> buildCommandLine(File cassandraDir) {
        List<String> command = Lists.newArrayList();
        String javaExecutable =
                JAVA_7_OR_LATER_HOME + File.separator + "bin" + File.separator + "java";
        command.add(javaExecutable);
        command.add("-cp");
        command.add(buildClasspath(cassandraDir));
        command.add("-javaagent:" + cassandraDir.getAbsolutePath() + "/lib/jamm-0.3.0.jar");
        command.add("-Dlogback.configurationFile=logback.xml");
        command.add("-Dcassandra.jmx.local.port=7199");
        command.add("-Dcassandra");
        command.add("-Dcassandra-foreground=yes");
        command.add("-Dcassandra.logdir=" + cassandraDir.getAbsolutePath() + "/log");
        command.add("-Dcassandra.storagedir=" + cassandraDir.getAbsolutePath() + "/data");
        // this is used inside low-entropy docker containers
        String sourceOfRandomness = System.getProperty("java.security.egd");
        if (sourceOfRandomness != null) {
            command.add("-Djava.security.egd=" + sourceOfRandomness);
        }
        command.add("-Xmx256m");
        // leave as much memory as possible to old gen
        command.add("-XX:NewRatio=20");
        command.add("org.apache.cassandra.service.CassandraDaemon");
        return command;
    }

    private static String buildClasspath(File cassandraDir) {
        File libDir = new File(cassandraDir, "lib");
        File confDir = new File(cassandraDir, "conf");
        String classpath = confDir.getAbsolutePath();
        for (File file : libDir.listFiles()) {
            if (file.getName().endsWith(".jar")) {
                classpath += File.pathSeparator + file.getAbsolutePath();
            }
        }
        return classpath;
    }

    private static void waitForCassandra() throws InterruptedException {
        while (true) {
            Cluster cluster = Cluster.builder().addContactPoint("127.0.0.1").build();
            try {
                cluster.connect();
                cluster.close();
                return;
            } catch (NoHostAvailableException e) {
                cluster.close();
                Thread.sleep(1000);
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
