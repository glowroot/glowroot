/*
 * Copyright 2013-2015 the original author or authors.
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
package org.glowroot.agent.init.fat;

import java.io.File;
import java.lang.instrument.Instrumentation;
import java.util.Map;
import java.util.concurrent.Callable;

import javax.annotation.Nullable;

import org.checkerframework.checker.nullness.qual.MonotonicNonNull;

import org.glowroot.agent.init.AgentModule;
import org.glowroot.agent.init.GlowrootAgentInit;
import org.glowroot.agent.init.NettyWorkaround;
import org.glowroot.common.util.OnlyUsedByTests;

import static com.google.common.base.Preconditions.checkNotNull;

public class GlowrootFatAgentInit implements GlowrootAgentInit {

    private @MonotonicNonNull FatAgentModule fatAgentModule;

    @Override
    public void init(File baseDir, @Nullable String collectorHost, Map<String, String> properties,
            @Nullable Instrumentation instrumentation, @Nullable File glowrootJarFile,
            String glowrootVersion, boolean jbossModules, boolean viewerMode) throws Exception {

        fatAgentModule = new FatAgentModule(baseDir, properties, instrumentation, glowrootJarFile,
                glowrootVersion, jbossModules, viewerMode);
        NettyWorkaround.run(instrumentation, new Callable</*@Nullable*/Void>() {
            @Override
            public @Nullable Void call() throws Exception {
                checkNotNull(fatAgentModule);
                fatAgentModule.initEmbeddedServer();
                return null;
            }
        });
    }

    @OnlyUsedByTests
    public int getUiPort() throws InterruptedException {
        return checkNotNull(fatAgentModule).getUiModule().getPort();
    }

    @Override
    @OnlyUsedByTests
    public AgentModule getAgentModule() {
        return checkNotNull(fatAgentModule).getAgentModule();
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
