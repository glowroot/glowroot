/*
 * Copyright 2016 the original author or authors.
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
package org.glowroot.central;

import java.util.List;

import org.glowroot.common.live.LiveWeavingService;
import org.glowroot.wire.api.model.DownstreamServiceOuterClass.GlobalMeta;
import org.glowroot.wire.api.model.DownstreamServiceOuterClass.MethodSignature;

class LiveWeavingServiceImpl implements LiveWeavingService {

    private final DownstreamServiceImpl downstreamService;

    LiveWeavingServiceImpl(DownstreamServiceImpl downstreamService) {
        this.downstreamService = downstreamService;
    }

    @Override
    public GlobalMeta getGlobalMeta(String serverId) throws Exception {
        return downstreamService.globalMeta(serverId);
    }

    @Override
    public void preloadClasspathCache(String serverId) throws Exception {
        downstreamService.preloadClasspathCache(serverId);
    }

    @Override
    public List<String> getMatchingClassNames(String serverId, String partialClassName, int limit)
            throws Exception {
        return downstreamService.matchingClassNames(serverId, partialClassName, limit);
    }

    @Override
    public List<String> getMatchingMethodNames(String serverId, String className,
            String partialMethodName, int limit) throws Exception {
        return downstreamService.matchingMethodNames(serverId, className, partialMethodName, limit);
    }

    @Override
    public List<MethodSignature> getMethodSignatures(String serverId, String className,
            String methodName) throws Exception {
        return downstreamService.methodSignatures(serverId, className, methodName);
    }

    @Override
    public int reweave(String serverId) throws Exception {
        return downstreamService.reweave(serverId);
    }
}
