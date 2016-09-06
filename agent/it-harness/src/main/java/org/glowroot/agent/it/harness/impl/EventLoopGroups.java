/*
 * Copyright 2015-2016 the original author or authors.
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

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import com.google.common.util.concurrent.ThreadFactoryBuilder;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.GenericFutureListener;

import static java.util.concurrent.TimeUnit.SECONDS;

class EventLoopGroups {

    private EventLoopGroups() {}

    // copy of io.grpc.netty.Utils.DefaultEventLoopGroupResource with some modification
    static EventLoopGroup create(String name) {
        final ExecutorService executor = Executors.newSingleThreadExecutor(
                new ThreadFactoryBuilder()
                        .setDaemon(true)
                        .setNameFormat(name)
                        .build());
        NioEventLoopGroup nioEventLoopGroup = new NioEventLoopGroup(1, executor);
        nioEventLoopGroup.terminationFuture()
                .addListener(new GenericFutureListener<Future<Object>>() {
                    @Override
                    public void operationComplete(Future<Object> future) throws Exception {
                        executor.shutdown();
                        if (!executor.awaitTermination(10, SECONDS)) {
                            throw new IllegalStateException("Could not terminate executor");
                        }
                    }
                });
        return nioEventLoopGroup;
    }
}
