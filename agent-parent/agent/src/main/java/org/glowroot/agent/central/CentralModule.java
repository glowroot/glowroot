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
package org.glowroot.agent.central;

import java.io.IOException;
import java.net.InetAddress;
import java.util.Map;
import java.util.concurrent.ScheduledExecutorService;

import javax.annotation.Nullable;

import com.google.common.base.Strings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.glowroot.agent.config.ConfigService;
import org.glowroot.agent.config.PluginCache;
import org.glowroot.agent.init.ProcessInfoCreator;
import org.glowroot.common.live.LiveJvmService;
import org.glowroot.common.live.LiveWeavingService;
import org.glowroot.common.util.OnlyUsedByTests;
import org.glowroot.wire.api.Collector.AgentConfigUpdater;
import org.glowroot.wire.api.model.AgentConfigOuterClass.AgentConfig;

import static com.google.common.base.Preconditions.checkNotNull;

public class CentralModule {

    private static final Logger logger = LoggerFactory.getLogger(CentralModule.class);

    private final CentralConnection centralConnection;
    private final CentralCollectorImpl grpcCollector;
    private final DownstreamServiceObserver downstreamServiceObserver;

    public CentralModule(Map<String, String> properties, @Nullable String collectorHost,
            ConfigService configService, PluginCache pluginCache,
            LiveWeavingService liveWeavingService, LiveJvmService liveJvmService,
            ScheduledExecutorService scheduledExecutor, String glowrootVersion) throws Exception {

        String serverId = properties.get("glowroot.server.id");
        if (Strings.isNullOrEmpty(serverId)) {
            serverId = InetAddress.getLocalHost().getHostName();
        }
        String collectorPortStr = properties.get("glowroot.collector.port");
        if (Strings.isNullOrEmpty(collectorPortStr)) {
            collectorPortStr = System.getProperty("glowroot.collector.port");
        }
        int collectorPort;
        if (Strings.isNullOrEmpty(collectorPortStr)) {
            collectorPort = 80;
        } else {
            collectorPort = Integer.parseInt(collectorPortStr);
        }
        checkNotNull(collectorHost);

        centralConnection = new CentralConnection(collectorHost, collectorPort, scheduledExecutor);
        final ConfigUpdateService configUpdateService =
                new ConfigUpdateService(configService, pluginCache);
        grpcCollector = new CentralCollectorImpl(centralConnection, serverId);
        downstreamServiceObserver = new DownstreamServiceObserver(centralConnection,
                configUpdateService, liveJvmService, liveWeavingService, serverId);
        downstreamServiceObserver.connectAsync();
        grpcCollector.collectInit(ProcessInfoCreator.create(glowrootVersion),
                configService.getAgentConfig(), new AgentConfigUpdater() {
                    @Override
                    public void update(AgentConfig agentConfig) {
                        try {
                            configUpdateService.updateAgentConfig(agentConfig);
                        } catch (IOException e) {
                            logger.error(e.getMessage(), e);
                        }
                    }
                });
    }

    public CentralCollectorImpl getGrpcCollector() {
        return grpcCollector;
    }

    @OnlyUsedByTests
    public void close() throws InterruptedException {
        downstreamServiceObserver.close();
        centralConnection.close();
    }

    @OnlyUsedByTests
    public void awaitClose() throws InterruptedException {
        centralConnection.awaitClose();
    }
}
