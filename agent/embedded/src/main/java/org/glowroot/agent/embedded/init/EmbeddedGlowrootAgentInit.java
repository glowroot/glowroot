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

import java.io.File;
import java.io.IOException;
import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.util.Map;

import org.checkerframework.checker.nullness.qual.MonotonicNonNull;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.glowroot.agent.impl.BytecodeServiceImpl.OnEnteringMain;
import org.glowroot.agent.init.AgentModule;
import org.glowroot.agent.init.GlowrootAgentInit;
import org.glowroot.agent.init.NettyWorkaround;
import org.glowroot.common.util.OnlyUsedByTests;

import static com.google.common.base.Preconditions.checkNotNull;

class EmbeddedGlowrootAgentInit implements GlowrootAgentInit {

    private static final Logger logger = LoggerFactory.getLogger(EmbeddedGlowrootAgentInit.class);

    private final File dataDir;
    private final boolean offline;

    EmbeddedGlowrootAgentInit(File dataDir, boolean offline) {
        this.dataDir = dataDir;
        this.offline = offline;
    }

    private @MonotonicNonNull EmbeddedAgentModule embeddedAgentModule;

    @Override
    public void init(@Nullable File pluginsDir, final File confDir,
            final @Nullable File sharedConfDir, File logDir, File tmpDir,
            final @Nullable File glowrootJarFile, final Map<String, String> properties,
            final @Nullable Instrumentation instrumentation,
            @Nullable ClassFileTransformer preCheckClassFileTransformer,
            final String glowrootVersion) throws Exception {
        embeddedAgentModule = new EmbeddedAgentModule(pluginsDir, confDir, sharedConfDir, logDir,
                tmpDir, instrumentation, preCheckClassFileTransformer, glowrootJarFile,
                glowrootVersion, offline);
        OnEnteringMain onEnteringMain = new OnEnteringMain() {
            @Override
            public void run() throws Exception {
                NettyWorkaround.run();
                // TODO report checker framework issue that occurs without checkNotNull
                checkNotNull(embeddedAgentModule);
                embeddedAgentModule.onEnteringMain(confDir, sharedConfDir, dataDir, glowrootJarFile,
                        properties, instrumentation, glowrootVersion);
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
            onEnteringMain.run();
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
    }

    @Override
    @OnlyUsedByTests
    public void awaitClose() {}
}
