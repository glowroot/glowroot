/*
 * Copyright 2013-2017 the original author or authors.
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
import java.lang.instrument.Instrumentation;
import java.util.Map;
import java.util.concurrent.Executors;

import javax.annotation.Nullable;

import org.checkerframework.checker.nullness.qual.MonotonicNonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.glowroot.agent.collector.Collector;
import org.glowroot.agent.init.AgentModule;
import org.glowroot.agent.init.GlowrootAgentInit;
import org.glowroot.agent.init.NettyWorkaround;
import org.glowroot.agent.init.NettyWorkaround.NettyInit;
import org.glowroot.common.util.OnlyUsedByTests;

import static com.google.common.base.Preconditions.checkNotNull;

public class GlowrootFatAgentInit implements GlowrootAgentInit {

    private static final Logger logger = LoggerFactory.getLogger(GlowrootFatAgentInit.class);

    private @MonotonicNonNull EmbeddedAgentModule fatAgentModule;

    @Override
    public void init(File glowrootDir, File agentDir, @Nullable String collectorAddress,
            @Nullable Collector customCollector, Map<String, String> properties,
            @Nullable Instrumentation instrumentation, String glowrootVersion, boolean offline)
            throws Exception {

        fatAgentModule = new EmbeddedAgentModule(glowrootDir, agentDir, properties, instrumentation,
                glowrootVersion, offline);
        NettyWorkaround.run(instrumentation, new NettyInit() {
            @Override
            public void execute(boolean newThread) throws Exception {
                checkNotNull(fatAgentModule);
                if (fatAgentModule.isSimpleRepoModuleReady()) {
                    // prefer to run in same thread
                    fatAgentModule.initEmbeddedServer();
                    return;
                }
                // needs to finish initializing
                if (newThread) {
                    // prefer to run in same thread
                    fatAgentModule.waitForSimpleRepoModule();
                    fatAgentModule.initEmbeddedServer();
                    return;
                }
                Executors.newSingleThreadExecutor().execute(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            // TODO report checker framework issue that occurs without checkNotNull
                            checkNotNull(fatAgentModule);
                            fatAgentModule.waitForSimpleRepoModule();
                            fatAgentModule.initEmbeddedServer();
                        } catch (Exception e) {
                            logger.error(e.getMessage(), e);
                        }
                    }
                });
            }
        });
    }

    @OnlyUsedByTests
    public int getUiPort() throws InterruptedException {
        return checkNotNull(fatAgentModule).getUiModule().getPort();
    }

    @Override
    @OnlyUsedByTests
    public void setSlowThresholdToZero() throws IOException {
        EmbeddedAgentModule fatAgentModule = checkNotNull(this.fatAgentModule);
        AgentModule agentModule = fatAgentModule.getAgentModule();
        agentModule.getConfigService().setSlowThresholdToZero();
    }

    @Override
    @OnlyUsedByTests
    public void resetConfig() throws Exception {
        EmbeddedAgentModule fatAgentModule = checkNotNull(this.fatAgentModule);
        AgentModule agentModule = fatAgentModule.getAgentModule();
        agentModule.getConfigService().resetConfig();
        ((ConfigRepositoryImpl) fatAgentModule.getSimpleRepoModule().getConfigRepository())
                .resetAdminConfig();
        agentModule.getLiveWeavingService().reweave("");
    }

    @Override
    @OnlyUsedByTests
    public void close() throws Exception {
        checkNotNull(fatAgentModule).close();
    }

    @Override
    @OnlyUsedByTests
    public void awaitClose() {}
}
