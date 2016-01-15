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
package org.glowroot.common.live;

import java.util.List;

import org.glowroot.wire.api.model.DownstreamServiceOuterClass.GlobalMeta;
import org.glowroot.wire.api.model.DownstreamServiceOuterClass.MethodSignature;

public interface LiveWeavingService {

    GlobalMeta getGlobalMeta(String serverId) throws Exception;

    void preloadClasspathCache(String serverId) throws Exception;

    List<String> getMatchingClassNames(String serverId, String partialClassName, int limit)
            throws Exception;

    List<String> getMatchingMethodNames(String serverId, String className, String partialMethodName,
            int limit) throws Exception;

    List<MethodSignature> getMethodSignatures(String serverId, String className, String methodName)
            throws Exception;

    int reweave(String serverId) throws Exception;
}
