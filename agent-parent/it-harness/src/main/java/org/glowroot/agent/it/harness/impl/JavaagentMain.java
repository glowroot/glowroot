/*
 * Copyright 2014-2015 the original author or authors.
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

import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import javax.annotation.Nullable;

import com.google.common.collect.ImmutableList;
import com.google.common.util.concurrent.ThreadFactoryBuilder;
import io.grpc.Server;
import io.grpc.netty.NettyServerBuilder;
import io.netty.channel.EventLoopGroup;

import org.glowroot.agent.AgentModule;
import org.glowroot.agent.MainEntryPoint;
import org.glowroot.agent.config.ConfigService;
import org.glowroot.agent.it.harness.grpc.ConfigUpdateServiceGrpc;
import org.glowroot.agent.it.harness.grpc.JavaagentServiceGrpc;
import org.glowroot.common.config.ImmutableTransactionConfig;
import org.glowroot.common.config.TransactionConfig;
import org.glowroot.common.live.LiveWeavingService;
import org.glowroot.wire.api.model.GaugeValueOuterClass.GaugeValue;

import static java.util.concurrent.TimeUnit.SECONDS;

public class JavaagentMain {

    public static void main(String... args) throws Exception {
        // transactionSlowThresholdMillis=0 is the default for testing
        setTransactionSlowThresholdMillisToZero();
        int javaagentServicePort = Integer.parseInt(args[0]);
        AgentModule agentModule = MainEntryPoint.getGlowrootAgentInit().getAgentModule();
        ConfigService configService = agentModule.getConfigService();
        LiveWeavingService liveWeavingService = agentModule.getLiveWeavingService();
        ConfigUpdateServiceHelper helper =
                new ConfigUpdateServiceHelper(configService, liveWeavingService);
        JavaagentServiceImpl javaagentService = new JavaagentServiceImpl();
        final EventLoopGroup bossEventLoopGroup = EventLoopGroups.create("Glowroot-grpc-boss-ELG");
        final EventLoopGroup workerEventLoopGroup =
                EventLoopGroups.create("Glowroot-grpc-worker-ELG");
        final ExecutorService executor = Executors.newCachedThreadPool(
                new ThreadFactoryBuilder()
                        .setDaemon(true)
                        .setNameFormat("Glowroot-grpc-executor-%d")
                        .build());
        final Server server = NettyServerBuilder.forPort(javaagentServicePort)
                .bossEventLoopGroup(bossEventLoopGroup)
                .workerEventLoopGroup(workerEventLoopGroup)
                .executor(executor)
                .addService(JavaagentServiceGrpc.bindService(javaagentService))
                .addService(
                        ConfigUpdateServiceGrpc.bindService(new ConfigUpdateServiceImpl(helper)))
                .build()
                .start();
        Executors.newSingleThreadExecutor().execute(new Heartbeat());
        javaagentService.setServerCloseable(new Callable</*@Nullable*/Void>() {
            @Override
            public @Nullable Void call() throws Exception {
                server.shutdown();
                if (!server.awaitTermination(10, SECONDS)) {
                    throw new IllegalStateException("Could not terminate gRPC channel");
                }
                executor.shutdown();
                if (!executor.awaitTermination(10, SECONDS)) {
                    throw new IllegalStateException("Could not terminate gRPC executor");
                }
                if (!bossEventLoopGroup.shutdownGracefully(0, 0, SECONDS).await(10, SECONDS)) {
                    throw new IllegalStateException(
                            "Could not terminate gRPC boss event loop group");
                }
                if (!workerEventLoopGroup.shutdownGracefully(0, 0, SECONDS).await(10,
                        SECONDS)) {
                    throw new IllegalStateException(
                            "Could not terminate gRPC worker event loop group");
                }
                return null;
            }
        });

        // spin a bit to so that caller can capture a trace with <multiple root nodes> if desired
        for (int i = 0; i < 1000; i++) {
            timerMarkerOne();
            timerMarkerTwo();
            Thread.sleep(1);
        }
        // non-daemon threads started above keep jvm alive after main returns
        Thread.sleep(Long.MAX_VALUE);
    }

    static void setTransactionSlowThresholdMillisToZero() throws Exception {
        ConfigService configService =
                MainEntryPoint.getGlowrootAgentInit().getAgentModule().getConfigService();
        TransactionConfig config = configService.getTransactionConfig();
        // conditional check is needed to prevent config file timestamp update when testing
        // ConfigFileLastModifiedTest.shouldNotUpdateFileOnStartupIfNoChanges()
        if (config.slowThresholdMillis() != 0) {
            TransactionConfig updatedConfig = ImmutableTransactionConfig.builder().copyFrom(config)
                    .slowThresholdMillis(0).build();
            configService.updateTransactionConfig(updatedConfig);
        }
    }

    private static void timerMarkerOne() throws InterruptedException {
        Thread.sleep(1);
    }

    private static void timerMarkerTwo() throws InterruptedException {
        Thread.sleep(1);
    }

    private static class Heartbeat implements Runnable {
        @Override
        public void run() {
            while (true) {
                try {
                    MainEntryPoint.getGlowrootAgentInit().getAgentModule().getCollector()
                            .collectGaugeValues(ImmutableList.<GaugeValue>of());
                    Thread.sleep(100);
                } catch (Exception e) {
                    // wait a bit since this could be RejectedExecutionException from shutting down
                    // the AgentModule (and therefore the GrpcCollector)
                    try {
                        Thread.sleep(10000);
                    } catch (InterruptedException f) {
                    }
                    System.exit(1);
                }
            }
        }
    }
}
