/*
 * Copyright 2011-2019 the original author or authors.
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
package org.glowroot.ui;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.concurrent.ThreadFactory;

import com.google.common.base.Stopwatch;
import com.google.common.base.Supplier;
import com.google.common.util.concurrent.ThreadFactoryBuilder;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.stream.ChunkedWriteHandler;
import io.netty.util.concurrent.Future;
import io.netty.util.internal.logging.InternalLoggerFactory;
import io.netty.util.internal.logging.Slf4JLoggerFactory;
import org.checkerframework.checker.nullness.qual.MonotonicNonNull;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.checkerframework.checker.nullness.qual.RequiresNonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static com.google.common.base.Preconditions.checkNotNull;
import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.concurrent.TimeUnit.SECONDS;

class HttpServer {

    private static final Logger logger = LoggerFactory.getLogger(HttpServer.class);

    // log startup messages using logger name "org.glowroot"
    private static final Logger startupLogger = LoggerFactory.getLogger("org.glowroot");

    private final ServerBootstrap bootstrap;
    private final HttpServerHandler handler;
    private final EventLoopGroup bossGroup;
    private final EventLoopGroup workerGroup;

    private final String bindAddress;
    private final List<File> confDirs;

    private final boolean offlineViewer;

    private volatile @Nullable SslContext sslContext;
    private volatile @MonotonicNonNull Channel serverChannel;
    private volatile @MonotonicNonNull Integer port;

    HttpServer(String bindAddress, boolean https, Supplier<String> contextPathSupplier,
            int numWorkerThreads, CommonHandler commonHandler, List<File> confDirs, boolean central,
            boolean offlineViewer) throws Exception {

        InternalLoggerFactory.setDefaultFactory(Slf4JLoggerFactory.INSTANCE);

        ThreadFactory bossThreadFactory = new ThreadFactoryBuilder()
                .setDaemon(true)
                .setNameFormat("Glowroot-Http-Boss")
                .build();
        ThreadFactory workerThreadFactory = new ThreadFactoryBuilder()
                .setDaemon(true)
                .setNameFormat("Glowroot-Http-Worker-%d")
                .build();
        bossGroup = new NioEventLoopGroup(1, bossThreadFactory);
        workerGroup = new NioEventLoopGroup(numWorkerThreads, workerThreadFactory);

        final HttpServerHandler handler = new HttpServerHandler(contextPathSupplier, commonHandler);

        if (https) {
            // upgrade from 0.9.26 to 0.9.27
            renameHttpsConfFileIfNeeded(confDirs, "certificate.pem", "ui-cert.pem", "certificate");
            renameHttpsConfFileIfNeeded(confDirs, "private.pem", "ui-key.pem", "private key");

            File certificateFile;
            File privateKeyFile;
            if (central) {
                certificateFile = getRequiredHttpsConfFile(confDirs.get(0), "ui-cert.pem",
                        "cert.pem", "certificate");
                privateKeyFile = getRequiredHttpsConfFile(confDirs.get(0), "ui-key.pem", "key.pem",
                        "private key");
            } else {
                certificateFile = getRequiredHttpsConfFile(confDirs, "ui-cert.pem");
                privateKeyFile = getRequiredHttpsConfFile(confDirs, "ui-key.pem");
            }
            sslContext = SslContextBuilder.forServer(certificateFile, privateKeyFile)
                    .build();
        }
        this.confDirs = confDirs;
        this.offlineViewer = offlineViewer;

        bootstrap = new ServerBootstrap();
        bootstrap.group(bossGroup, workerGroup).channel(NioServerSocketChannel.class)
                .childHandler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel ch) throws Exception {
                        ChannelPipeline p = ch.pipeline();
                        SslContext sslContextLocal = sslContext;
                        if (sslContextLocal != null) {
                            p.addLast(sslContextLocal.newHandler(ch.alloc()));
                        }
                        // bumping maxInitialLineLength (first arg below) from default 4096 to 65536
                        // in order to handle long urls on /jvm/gauges and /report/adhoc views
                        // bumping maxHeaderSize (second arg below) from default 8192 to 65536 for
                        // same reason due to "Referer" header once url becomes huge
                        // leaving maxChunkSize (third arg below) at default 8192
                        p.addLast(new HttpServerCodec(65536, 65536, 8192));
                        p.addLast(new HttpObjectAggregator(1048576));
                        p.addLast(new ConditionalHttpContentCompressor());
                        p.addLast(new ChunkedWriteHandler());
                        p.addLast(handler);
                    }
                });
        this.handler = handler;
        this.bindAddress = bindAddress;
    }

    void bindEventually(int port) {
        try {
            serverChannel =
                    bootstrap.bind(new InetSocketAddress(bindAddress, port)).sync().channel();
            onBindSuccess();
        } catch (Exception e) {
            // note: FailedChannelFuture.sync() is using UNSAFE to re-throw checked exceptions
            startupLogger.error("Error binding to {}:{}, the UI is not available (will keep trying"
                    + " to bind...): {}", bindAddress, port, e.getMessage());
            logger.debug(e.getMessage(), e);
            Thread thread = new Thread(new BindEventually(port));
            thread.setName("Glowroot-Init-Bind");
            thread.setDaemon(true);
            thread.start();
        }
    }

    @RequiresNonNull("serverChannel")
    private void onBindSuccess() {
        port = ((InetSocketAddress) serverChannel.localAddress()).getPort();
        String listener = offlineViewer ? "Offline viewer" : "UI";
        String optionalHttps = sslContext == null ? "" : " (HTTPS)";
        if (bindAddress.equals("127.0.0.1")) {
            startupLogger.info("{} listening on {}:{}{} (to access the UI from remote machines,"
                    + " change the bind address to 0.0.0.0, either in the Glowroot UI under"
                    + " Configuration > Web or directly in the admin.json file, and then restart"
                    + " JVM to take effect)", listener, bindAddress, port, optionalHttps);
        } else {
            startupLogger.info("{} listening on {}:{}{}", listener, bindAddress, port,
                    optionalHttps);
        }
    }

    String getBindAddress() {
        return bindAddress;
    }

    @Nullable
    Integer getPort() {
        return port;
    }

    boolean getHttps() {
        return sslContext != null;
    }

    void changePort(int newPort) throws Exception {
        checkNotNull(serverChannel);
        Channel previousServerChannel = serverChannel;
        InetSocketAddress localAddress = new InetSocketAddress(bindAddress, newPort);
        try {
            serverChannel = bootstrap.bind(localAddress).sync().channel();
        } catch (Exception e) {
            // note: FailedChannelFuture.sync() is using UNSAFE to re-throw checked exceptions
            throw new PortChangeFailedException(e);
        }
        port = newPort;
        previousServerChannel.close().get();
        handler.closeAllButCurrent();
    }

    void changeProtocol(boolean https) throws Exception {
        if (https) {
            sslContext = SslContextBuilder
                    .forServer(getRequiredHttpsConfFile(confDirs, "ui-cert.pem"),
                            getRequiredHttpsConfFile(confDirs, "ui-key.pem"))
                    .build();
        } else {
            sslContext = null;
        }
        handler.closeAllButCurrent();
    }

    // used by tests and by central ui
    void close() throws Exception {
        logger.debug("close(): stopping http server");
        Future<?> workerShutdownFuture = workerGroup.shutdownGracefully(1, 5, SECONDS);
        Future<?> bossShutdownFuture = bossGroup.shutdownGracefully(1, 5, SECONDS);
        Stopwatch stopwatch = Stopwatch.createStarted();
        workerShutdownFuture.get(10, SECONDS);
        long remainingMillis = Math.max(0, 10000 - stopwatch.elapsed(MILLISECONDS));
        bossShutdownFuture.get(remainingMillis, MILLISECONDS);
        logger.debug("close(): http server stopped");
    }

    // used by embedded agent
    private static File getRequiredHttpsConfFile(List<File> confDirs, String fileName)
            throws FileNotFoundException {
        File confFile = getHttpsConfFile(confDirs, fileName);
        if (confFile == null) {
            // build and throw nice exception message
            StringBuilder sb = new StringBuilder("HTTPS is enabled, but " + fileName
                    + " was not found under ");
            if (confDirs.size() == 2) {
                sb.append("either of ");
            } else if (confDirs.size() > 2) {
                sb.append("any of ");
            }
            for (int i = 0; i < confDirs.size(); i++) {
                if (i > 0) {
                    sb.append(", '");
                }
                sb.append(confDirs.get(i).getAbsolutePath());
                sb.append("'");
            }
            throw new FileNotFoundException(sb.toString());
        } else {
            return confFile;
        }
    }

    // used by central
    private static File getRequiredHttpsConfFile(File confDir, String fileName, String altFileName,
            String display) throws FileNotFoundException {
        File confFile = new File(confDir, fileName);
        if (confFile.exists()) {
            return confFile;
        }
        if (altFileName == null) {
            throw new FileNotFoundException("HTTPS is enabled, but " + fileName
                    + " was not found under '" + confDir.getAbsolutePath() + "'");
        }
        File altConfFile = new File(confDir, altFileName);
        if (altConfFile.exists()) {
            return altConfFile;
        }
        throw new FileNotFoundException("HTTPS is enabled, but " + fileName + " (or "
                + altFileName + " if using the same " + display + " for both ui and"
                + " grpc) was not found under '" + confDir.getAbsolutePath() + "'");
    }

    private static @Nullable File getHttpsConfFile(List<File> confDirs, String fileName) {
        for (File confDir : confDirs) {
            File confFile = new File(confDir, fileName);
            if (confFile.exists()) {
                return confFile;
            }
        }
        return null;
    }

    private static void renameHttpsConfFileIfNeeded(List<File> confDirs, String oldFileName,
            String newFileName, String display) throws IOException {
        for (File confDir : confDirs) {
            File newConfFile = new File(confDir, newFileName);
            if (newConfFile.exists()) {
                return;
            }
            File oldConfFile = new File(confDir, oldFileName);
            if (oldConfFile.exists()) {
                rename(oldConfFile, newConfFile, display);
                return;
            }
        }
    }

    private static void rename(File oldConfFile, File newConfFile, String display)
            throws IOException {
        if (oldConfFile.renameTo(newConfFile)) {
            throw new IOException("Unable to rename " + display + " file from '"
                    + oldConfFile.getAbsolutePath() + "' to '" + newConfFile.getAbsolutePath()
                    + "' as part of upgrade to 0.9.27 or later");
        }
    }

    private class BindEventually implements Runnable {

        private final int port;

        private BindEventually(int port) {
            this.port = port;
        }

        @Override
        public void run() {
            long backoffMillis = 1000;
            while (true) {
                try {
                    MILLISECONDS.sleep(backoffMillis);
                } catch (InterruptedException f) {
                    Thread.currentThread().interrupt();
                    return;
                }
                backoffMillis = Math.min(backoffMillis * 2, 60000);
                try {
                    serverChannel = bootstrap.bind(new InetSocketAddress(bindAddress, port)).sync()
                            .channel();
                    onBindSuccess();
                    return;
                } catch (Exception e) {
                    // note: FailedChannelFuture.sync() is using UNSAFE to re-throw checked
                    // exceptions
                    startupLogger.error("Error binding to {}:{}, the UI is not available (will keep"
                            + " trying to bind...): {}", bindAddress, port, e.getMessage());
                    logger.debug(e.getMessage(), e);
                }
            }
        }
    }

    @SuppressWarnings("serial")
    static class PortChangeFailedException extends Exception {
        private PortChangeFailedException(Exception cause) {
            super(cause);
        }
    }
}
