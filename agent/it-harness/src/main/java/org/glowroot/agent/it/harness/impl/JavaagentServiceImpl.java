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

import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;

import javax.annotation.Nullable;

import com.google.common.collect.Sets;
import io.grpc.stub.StreamObserver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.glowroot.agent.MainEntryPoint;
import org.glowroot.agent.init.GlowrootAgentInit;
import org.glowroot.agent.it.harness.AppUnderTest;
import org.glowroot.agent.it.harness.Threads;
import org.glowroot.agent.it.harness.grpc.JavaagentServiceGrpc.JavaagentServiceImplBase;
import org.glowroot.agent.it.harness.grpc.JavaagentServiceOuterClass.AppUnderTestClassName;
import org.glowroot.agent.it.harness.grpc.JavaagentServiceOuterClass.Void;

import static com.google.common.base.Preconditions.checkNotNull;

class JavaagentServiceImpl extends JavaagentServiceImplBase {

    private static final Logger logger = LoggerFactory.getLogger(JavaagentServiceImpl.class);

    private static final boolean CHECK_THREADS;

    static {
        // only check threads for glowroot-agent-integration-tests
        // it is not always possible to clean up threads in plugin tests, e.g. OkHttp starts a
        // couple of threads that are not stoppable
        CHECK_THREADS = Boolean.getBoolean("glowroot.test.checkThreads");
    }

    private volatile @Nullable Thread executingAppThread;

    private volatile @Nullable Set<Thread> preExistingThreads;

    private volatile @Nullable Callable<java.lang.Void> serverCloseable;

    @Override
    public void ping(Void request, StreamObserver<Void> responseObserver) {
        responseObserver.onNext(Void.getDefaultInstance());
        responseObserver.onCompleted();
    }

    @Override
    public void executeApp(AppUnderTestClassName request, StreamObserver<Void> responseObserver) {
        if (preExistingThreads == null) {
            preExistingThreads = Sets.newHashSet(Threads.currentThreads());
        }
        if (executingAppThread != null) {
            throw new IllegalStateException("Already executing an app");
        }
        try {
            executingAppThread = Thread.currentThread();
            Class<?> appClass = Class.forName(request.getValue());
            AppUnderTest app = (AppUnderTest) appClass.newInstance();
            app.executeApp();
        } catch (Throwable t) {
            logger.error(t.getMessage(), t);
            responseObserver.onError(t);
            return;
        } finally {
            executingAppThread = null;
        }
        try {
            // this avoids a sporadic gRPC error when running AnalyzedClassPlanBeeIT
            // TODO after next gRPC release, remove this and see if sporadic error still occurs
            Thread.sleep(100);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return;
        }
        responseObserver.onNext(Void.getDefaultInstance());
        responseObserver.onCompleted();
    }

    @Override
    public void interruptApp(Void request, StreamObserver<Void> responseObserver) {
        Thread thread = executingAppThread;
        if (thread != null) {
            try {
                thread.interrupt();
            } catch (Throwable t) {
                logger.error(t.getMessage(), t);
                responseObserver.onError(t);
                return;
            }
        }
        responseObserver.onNext(Void.getDefaultInstance());
        responseObserver.onCompleted();
    }

    @Override
    public void setSlowThresholdToZero(Void request, StreamObserver<Void> responseObserver) {
        try {
            GlowrootAgentInit glowrootAgentInit =
                    checkNotNull(MainEntryPoint.getGlowrootAgentInit());
            glowrootAgentInit.setSlowThresholdToZero();
        } catch (Throwable t) {
            logger.error(t.getMessage(), t);
            responseObserver.onError(t);
            return;
        }
        responseObserver.onNext(Void.getDefaultInstance());
        responseObserver.onCompleted();
    }

    @Override
    public void resetConfig(Void request, StreamObserver<Void> responseObserver) {
        try {
            GlowrootAgentInit glowrootAgentInit =
                    checkNotNull(MainEntryPoint.getGlowrootAgentInit());
            glowrootAgentInit.resetConfig();
        } catch (Throwable t) {
            logger.error(t.getMessage(), t);
            responseObserver.onError(t);
            return;
        }
        responseObserver.onNext(Void.getDefaultInstance());
        responseObserver.onCompleted();
    }

    @Override
    public void shutdown(Void request, StreamObserver<Void> responseObserver) {
        try {
            if (CHECK_THREADS && preExistingThreads != null) {
                Threads.preShutdownCheck(preExistingThreads);
            }
            checkNotNull(MainEntryPoint.getGlowrootAgentInit()).close();
            if (CHECK_THREADS && preExistingThreads != null) {
                Threads.postShutdownCheck(preExistingThreads);
            }
        } catch (Throwable t) {
            logger.error(t.getMessage(), t);
            responseObserver.onError(t);
            return;
        }
        responseObserver.onNext(Void.getDefaultInstance());
        responseObserver.onCompleted();
    }

    @Override
    public void kill(Void request, StreamObserver<Void> responseObserver) {
        responseObserver.onNext(Void.getDefaultInstance());
        responseObserver.onCompleted();
        Executors.newSingleThreadExecutor().execute(new Runnable() {
            @Override
            public void run() {
                try {
                    // wait a few millis for response to be returned
                    Thread.sleep(10);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                try {
                    checkNotNull(serverCloseable).call();
                } catch (Exception e) {
                    logger.error(e.getMessage(), e);
                }
                System.exit(0);
            }
        });
    }

    void setServerCloseable(Callable<java.lang.Void> serverCloseable) {
        this.serverCloseable = serverCloseable;
    }
}
