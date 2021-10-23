/*
 * Copyright 2014-2018 the original author or authors.
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

import com.google.common.reflect.Reflection;
import com.google.common.util.concurrent.ThreadFactoryBuilder;
import io.grpc.Server;
import io.grpc.netty.NettyServerBuilder;
import io.netty.channel.EventLoopGroup;
import org.checkerframework.checker.nullness.qual.Nullable;

import org.glowroot.agent.it.harness.Container;

import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.concurrent.TimeUnit.SECONDS;

class JavaagentMain {

    public static void main(String[] args) throws Exception {

        // this is needed on Java 9+ now that sun.boot.class.path no longer exists, so that
        // instrumentation config auto complete can find this class in InstrumentationConfigIT
        Reflection.initialize(Container.class);

        int port = Integer.parseInt(args[0]);
        final SocketHeartbeat socketHeartbeat = new SocketHeartbeat(port);
        new Thread(socketHeartbeat).start();

        int javaagentServicePort = Integer.parseInt(args[1]);
        JavaagentServiceImpl javaagentService = new JavaagentServiceImpl();
        // need at least 2 threads, one for executeApp(), and another for handling interruptApp() at
        // the same time
        final ExecutorService executor = Executors.newCachedThreadPool(
                new ThreadFactoryBuilder()
                        .setDaemon(true)
                        .setNameFormat("Glowroot-IT-Harness*-GRPC-Executor-%d")
                        .build());
        final Server server = NettyServerBuilder.forPort(javaagentServicePort)
                .executor(executor)
                .addService(javaagentService.bindService())
                .build()
                .start();
        javaagentService.setServerCloseable(new Callable</*@Nullable*/ Void>() {
            @Override
            public @Nullable Void call() throws Exception {
                server.shutdown();
                if (!server.awaitTermination(10, SECONDS)) {
                    throw new IllegalStateException("Could not terminate channel");
                }
                executor.shutdown();
                if (!executor.awaitTermination(10, SECONDS)) {
                    throw new IllegalStateException("Could not terminate executor");
                }
                socketHeartbeat.close();
                return null;
            }
        });

        // spin a bit to so that caller can capture a trace with <multiple root nodes> if desired
        for (int i = 0; i < 1000; i++) {
            timerMarkerOne();
            timerMarkerTwo();
            MILLISECONDS.sleep(1);
        }
        // non-daemon threads started above keep jvm alive after main returns
        MILLISECONDS.sleep(Long.MAX_VALUE);
    }

    private static void timerMarkerOne() throws InterruptedException {
        MILLISECONDS.sleep(1);
    }

    private static void timerMarkerTwo() throws InterruptedException {
        MILLISECONDS.sleep(1);
    }
}
