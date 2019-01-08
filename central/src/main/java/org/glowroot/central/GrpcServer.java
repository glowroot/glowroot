/*
 * Copyright 2015-2019 the original author or authors.
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
package org.glowroot.central;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLSessionContext;

import io.grpc.Server;
import io.grpc.netty.GrpcSslContexts;
import io.grpc.netty.NettyServerBuilder;
import io.netty.buffer.ByteBufAllocator;
import io.netty.handler.ssl.SslContext;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.glowroot.central.repo.ActiveAgentDao;
import org.glowroot.central.repo.AgentConfigDao;
import org.glowroot.central.repo.AgentDisplayDao;
import org.glowroot.central.repo.AggregateDao;
import org.glowroot.central.repo.EnvironmentDao;
import org.glowroot.central.repo.GaugeValueDao;
import org.glowroot.central.repo.HeartbeatDao;
import org.glowroot.central.repo.TraceDao;
import org.glowroot.central.repo.V09AgentRollupDao;
import org.glowroot.central.util.ClusterManager;
import org.glowroot.common.util.Clock;

import static com.google.common.base.Preconditions.checkNotNull;
import static java.nio.file.StandardWatchEventKinds.ENTRY_CREATE;
import static java.nio.file.StandardWatchEventKinds.ENTRY_MODIFY;
import static java.util.concurrent.TimeUnit.MINUTES;
import static java.util.concurrent.TimeUnit.SECONDS;

class GrpcServer {

    // log startup messages using logger name "org.glowroot"
    private static final Logger startupLogger = LoggerFactory.getLogger("org.glowroot");

    private static final Logger logger = LoggerFactory.getLogger(GrpcServer.class);

    private final DownstreamServiceImpl downstreamService;

    private final @Nullable Server httpServer;
    private final @Nullable Server httpsServer;

    private final @Nullable ExecutorService confDirWatchExecutor;

    GrpcServer(String bindAddress, @Nullable Integer httpPort, @Nullable Integer httpsPort,
            File confDir, AgentDisplayDao agentDisplayDao, AgentConfigDao agentConfigDao,
            ActiveAgentDao activeAgentDao, EnvironmentDao environmentDao, HeartbeatDao heartbeatDao,
            AggregateDao aggregateDao, GaugeValueDao gaugeValueDao, TraceDao traceDao,
            V09AgentRollupDao v09AgentRollupDao, CentralAlertingService centralAlertingService,
            ClusterManager clusterManager, Clock clock, String version) throws IOException {

        GrpcCommon grpcCommon = new GrpcCommon(agentDisplayDao, v09AgentRollupDao);
        downstreamService = new DownstreamServiceImpl(grpcCommon, clusterManager);

        CollectorServiceImpl collectorService = new CollectorServiceImpl(agentDisplayDao,
                agentConfigDao, activeAgentDao, environmentDao, heartbeatDao, aggregateDao,
                gaugeValueDao, traceDao, v09AgentRollupDao, grpcCommon, centralAlertingService,
                clock, version);

        if (httpPort == null) {
            httpServer = null;
        } else {
            httpServer = startServer(bindAddress, httpPort, false, confDir, null, downstreamService,
                    collectorService);
            if (httpsPort == null) {
                startupLogger.info("gRPC listening on {}:{}", bindAddress, httpPort);
            } else {
                startupLogger.info("gRPC listening on {}:{} (HTTP)", bindAddress, httpPort);
            }
        }
        if (httpsPort == null) {
            confDirWatchExecutor = null;
            httpsServer = null;
        } else {
            confDirWatchExecutor = Executors.newSingleThreadExecutor();
            httpsServer = startServer(bindAddress, httpsPort, true, confDir, confDirWatchExecutor,
                    downstreamService, collectorService);
            startupLogger.info("gRPC listening on {}:{} (HTTPS)", bindAddress, httpsPort);
        }
    }

    private static Server startServer(String bindAddress, int port, boolean https, File confDir,
            @Nullable ExecutorService confDirWatchExecutor, DownstreamServiceImpl downstreamService,
            CollectorServiceImpl collectorService) throws IOException {
        NettyServerBuilder builder =
                NettyServerBuilder.forAddress(new InetSocketAddress(bindAddress, port));
        if (https) {
            builder.sslContext(
                    DelegatingSslContext.create(confDir, checkNotNull(confDirWatchExecutor)));
        }
        return builder.addService(collectorService.bindService())
                .addService(downstreamService.bindService())
                // need to override default max message size of 4mb until streaming is implemented
                // for DownstreamService.EntriesResponse and FullTraceResponse
                .maxInboundMessageSize(64 * 1024 * 1024)
                // aggressive keep alive is used by agent to detect silently dropped connections
                // (see org.glowroot.agent.central.CentralConnection)
                .permitKeepAliveTime(20, SECONDS)
                // aggressive max connection age forces agents to re-resolve DNS often for DNS-based
                // load balancing (e.g. to pick up and spread load across new central collectors)
                .maxConnectionAge(20, MINUTES)
                .build()
                .start();
    }

    DownstreamServiceImpl getDownstreamService() {
        return downstreamService;
    }

    void close() throws InterruptedException {
        if (confDirWatchExecutor != null) {
            // shutdownNow() is needed here to send interrupt to conf dir watching thread
            confDirWatchExecutor.shutdownNow();
        }

        // immediately start sending "shutting-down" responses for new downstream requests
        // and wait for existing downstream requests to complete before proceeding
        downstreamService.stopSendingDownstreamRequests();

        // "shutting-down" responses will continue to be sent for new downstream requests until
        // ClusterManager is closed at the very end of CentralModule.shutdown(), which will give
        // time for agents to reconnect to a new central cluster node, and for the UI to retry
        // for a few seconds if it receives a "shutting-down" response

        // shutdown to prevent new grpc requests
        if (httpsServer != null) {
            // stop accepting new requests
            httpsServer.shutdown();
        }
        if (httpServer != null) {
            // stop accepting new requests
            httpServer.shutdown();
        }
        // wait for existing requests to complete
        SECONDS.sleep(5);
        if (httpsServer != null) {
            shutdownNow(httpsServer);
        }
        if (httpServer != null) {
            shutdownNow(httpServer);
        }
    }

    private static void shutdownNow(Server server) throws InterruptedException {
        // TODO shutdownNow() has been needed to interrupt grpc threads since grpc-java 1.7.0
        server.shutdownNow();
        if (!server.awaitTermination(10, SECONDS)) {
            throw new IllegalStateException("Timed out waiting for grpc server to terminate");
        }
    }

    private static class DelegatingSslContext extends SslContext {

        private final File confDir;

        private volatile SslContext sslContext;

        public static SslContext create(File confDir, ExecutorService executor) throws IOException {
            DelegatingSslContext delegatingSslContext = new DelegatingSslContext(confDir);
            executor.execute(new CertificateReloader(confDir, delegatingSslContext));
            return delegatingSslContext;
        }

        private DelegatingSslContext(File confDir) throws IOException {
            this.confDir = confDir;
            sslContext = createSslContext(confDir);
        }

        @Override
        public boolean isClient() {
            return sslContext.isClient();
        }

        @Override
        public List<String> cipherSuites() {
            return sslContext.cipherSuites();
        }

        @Override
        public long sessionCacheSize() {
            return sslContext.sessionCacheSize();
        }

        @Override
        public long sessionTimeout() {
            return sslContext.sessionTimeout();
        }

        @Override
        @SuppressWarnings("deprecation")
        public io.netty.handler.ssl.ApplicationProtocolNegotiator applicationProtocolNegotiator() {
            return sslContext.applicationProtocolNegotiator();
        }

        @Override
        public SSLEngine newEngine(ByteBufAllocator alloc) {
            return sslContext.newEngine(alloc);
        }

        @Override
        public SSLEngine newEngine(ByteBufAllocator alloc, String peerHost, int peerPort) {
            return sslContext.newEngine(alloc, peerHost, peerPort);
        }

        @Override
        public SSLSessionContext sessionContext() {
            return sslContext.sessionContext();
        }

        private void reloadCertificate() throws IOException {
            sslContext = createSslContext(confDir);
            startupLogger.info("gRPC certificate reloaded");
        }

        private static SslContext createSslContext(File confDir) throws IOException {
            return GrpcSslContexts.forServer(
                    getHttpsConfFile(confDir, "grpc-cert.pem", "cert.pem", "certificate"),
                    getHttpsConfFile(confDir, "grpc-key.pem", "key.pem", "private key"))
                    .build();
        }

        private static File getHttpsConfFile(File confDir, String fileName, String sharedFileName,
                String display) throws FileNotFoundException {
            File confFile = new File(confDir, fileName);
            if (confFile.exists()) {
                return confFile;
            } else {
                File sharedConfFile = new File(confDir, sharedFileName);
                if (sharedConfFile.exists()) {
                    return sharedConfFile;
                } else {
                    throw new FileNotFoundException("HTTPS is enabled, but " + fileName + " (or "
                            + sharedFileName + " if using the same " + display
                            + " for both grpc and ui) was not found under '"
                            + confDir.getAbsolutePath() + "'");
                }
            }
        }
    }

    private static final class CertificateReloader implements Runnable {

        private final WatchService watcher;
        private final DelegatingSslContext delegatingSslContext;

        private CertificateReloader(File confDir, DelegatingSslContext delegatingSslContext)
                throws IOException {
            this.delegatingSslContext = delegatingSslContext;
            watcher = FileSystems.getDefault().newWatchService();
            confDir.toPath().register(watcher, ENTRY_CREATE, ENTRY_MODIFY);
        }

        @Override
        public void run() {
            while (true) {
                try {
                    runInternal();
                } catch (InterruptedException e) {
                    // probably shutdown requested
                    logger.debug(e.getMessage(), e);
                    return;
                } catch (Throwable t) {
                    logger.error(t.getMessage(), t);
                }
            }
        }

        public void runInternal() throws Exception {
            WatchKey watchKey = watcher.take();
            watchKey.reset();
            if (!certificateModified(watchKey)) {
                return;
            }
            // wait for system to settle (in case copying over cert/key pair one by one)
            while (true) {
                SECONDS.sleep(5);
                watchKey = watcher.poll();
                if (watchKey == null) {
                    break;
                }
                watchKey.reset();
                if (!certificateModified(watchKey)) {
                    break;
                }
            }
            delegatingSslContext.reloadCertificate();
        }

        private boolean certificateModified(WatchKey watchKey) {
            for (WatchEvent<?> event : watchKey.pollEvents()) {
                Object context = event.context();
                if (context instanceof Path) {
                    String fileName = ((Path) context).toString();
                    if (fileName.equals("grpc-cert.pem") || fileName.equals("cert.pem")
                            || fileName.equals("grpc-key.pem") || fileName.equals("key.pem")) {
                        return true;
                    }
                }
            }
            return false;
        }
    }
}
