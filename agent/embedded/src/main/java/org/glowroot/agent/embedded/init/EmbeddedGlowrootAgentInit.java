/*
 * Copyright 2013-2018 the original author or authors.
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
package org.glowroot.agent.embedded.init;

import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.lang.instrument.Instrumentation;
import java.util.List;
import java.util.Map;

import org.checkerframework.checker.nullness.qual.MonotonicNonNull;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.glowroot.agent.collector.Collector;
import org.glowroot.agent.impl.BytecodeServiceImpl.OnEnteringMain;
import org.glowroot.agent.init.AgentDirsLocking;
import org.glowroot.agent.init.AgentModule;
import org.glowroot.agent.init.GlowrootAgentInit;
import org.glowroot.agent.init.NettyInit;
import org.glowroot.agent.init.PreCheckLoadedClasses.PreCheckClassFileTransformer;
import org.glowroot.common.util.OnlyUsedByTests;

import static com.google.common.base.Preconditions.checkNotNull;

class EmbeddedGlowrootAgentInit implements GlowrootAgentInit {

    private static final Logger logger = LoggerFactory.getLogger(EmbeddedGlowrootAgentInit.class);

    private final File dataDir;
    private final boolean offlineViewer;
    private final @Nullable Class<? extends Collector> collectorProxyClass;

    private volatile @MonotonicNonNull Closeable agentDirsLockingCloseable;

    EmbeddedGlowrootAgentInit(File dataDir, boolean offlineViewer,
            @Nullable Class<? extends Collector> collectorProxyClass) {
        this.dataDir = dataDir;
        this.offlineViewer = offlineViewer;
        this.collectorProxyClass = collectorProxyClass;
    }

    private @MonotonicNonNull EmbeddedAgentModule embeddedAgentModule;

    @Override
    public void init(@Nullable File pluginsDir, final List<File> confDirs, File logDir, File tmpDir,
            final @Nullable File glowrootJarFile, final Map<String, String> properties,
            final @Nullable Instrumentation instrumentation,
            @Nullable PreCheckClassFileTransformer preCheckClassFileTransformer,
            final String glowrootVersion) throws Exception {

        agentDirsLockingCloseable = AgentDirsLocking.lockAgentDirs(tmpDir, false, offlineViewer);
        embeddedAgentModule = new EmbeddedAgentModule(pluginsDir, confDirs, logDir, tmpDir,
                instrumentation, preCheckClassFileTransformer, glowrootJarFile, glowrootVersion,
                offlineViewer);
        OnEnteringMain onEnteringMain = new OnEnteringMain() {
            @Override
            public void run(@Nullable String mainClass) throws Exception {
                NettyInit.run();
                // TODO report checker framework issue that occurs without checkNotNull
                checkNotNull(embeddedAgentModule);
                embeddedAgentModule.onEnteringMain(confDirs, dataDir, glowrootJarFile, properties,
                        instrumentation, collectorProxyClass, glowrootVersion, mainClass);
                // starting new thread in order not to block startup
                Thread thread = new Thread(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            // TODO report checker framework issue that occurs without
                            // checkNotNull
                            checkNotNull(embeddedAgentModule);
                            embeddedAgentModule.waitForSimpleRepoModule();
                            embeddedAgentModule.initEmbeddedServer();
                        } catch (Exception e) {
                            logger.error(e.getMessage(), e);
                        }
                    }
                });
                thread.setName("Glowroot-Init-UI");
                thread.setDaemon(true);
                thread.start();
            }
        };
        if (instrumentation == null) {
            // this is for offline viewer and for tests
            onEnteringMain.run(null);
        } else {
            embeddedAgentModule.setOnEnteringMain(onEnteringMain);
        }
    }

    @Override
    @OnlyUsedByTests
    public void setSlowThresholdToZero() throws IOException {
        EmbeddedAgentModule embeddedAgentModule = checkNotNull(this.embeddedAgentModule);
        AgentModule agentModule = embeddedAgentModule.getAgentModule();
        agentModule.getConfigService().setSlowThresholdToZero();
    }

    @Override
    @OnlyUsedByTests
    public void resetConfig() throws Exception {
        EmbeddedAgentModule embeddedAgentModule = checkNotNull(this.embeddedAgentModule);
        AgentModule agentModule = embeddedAgentModule.getAgentModule();
        agentModule.getConfigService().resetConfig();
        embeddedAgentModule.getSimpleRepoModule().getConfigRepository().resetAdminConfig();
        agentModule.getLiveWeavingService().reweave("");
    }

    @Override
    @OnlyUsedByTests
    public void close() throws Exception {
        checkNotNull(embeddedAgentModule).close();
        // and unlock the agent directory
        checkNotNull(agentDirsLockingCloseable).close();
    }

    @Override
    @OnlyUsedByTests
    public void awaitClose() {}
}
