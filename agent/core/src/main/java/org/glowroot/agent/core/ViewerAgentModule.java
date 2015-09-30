/*
 * Copyright 2015 the original author or authors.
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
package org.glowroot.agent.core;

import java.io.File;
import java.util.List;

import javax.annotation.Nullable;

import org.glowroot.agent.core.config.ConfigService;
import org.glowroot.agent.core.config.PluginCache;
import org.glowroot.agent.core.live.LiveJvmServiceImpl;
import org.glowroot.agent.core.util.LazyPlatformMBeanServer;
import org.glowroot.agent.core.util.OptionalService;
import org.glowroot.agent.core.util.ThreadAllocatedBytes;
import org.glowroot.common.config.PluginDescriptor;
import org.glowroot.common.live.LiveJvmService;

public class ViewerAgentModule {

    private final PluginCache pluginCache;
    private final ConfigService configService;
    private final LazyPlatformMBeanServer lazyPlatformMBeanServer;
    private final LiveJvmService liveJvmService;

    public ViewerAgentModule(File baseDir, @Nullable File glowrootJarFile) throws Exception {
        pluginCache = PluginCache.create(glowrootJarFile, true);
        configService = ConfigService.create(baseDir, pluginCache.pluginDescriptors());
        lazyPlatformMBeanServer = new LazyPlatformMBeanServer(false);
        OptionalService<ThreadAllocatedBytes> threadAllocatedBytes = ThreadAllocatedBytes.create();
        liveJvmService = new LiveJvmServiceImpl(lazyPlatformMBeanServer,
                threadAllocatedBytes.getAvailability());
    }

    public ConfigService getConfigService() {
        return configService;
    }

    public LazyPlatformMBeanServer getLazyPlatformMBeanServer() {
        return lazyPlatformMBeanServer;
    }

    public LiveJvmService getLiveJvmService() {
        return liveJvmService;
    }

    public List<PluginDescriptor> getPluginDescriptors() {
        return pluginCache.pluginDescriptors();
    }
}
