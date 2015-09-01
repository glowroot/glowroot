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
package org.glowroot.server.repo;

import org.glowroot.common.model.MutableProfileNode;

public class ProfileCollector {

    private final MutableProfileNode syntheticRootNode =
            MutableProfileNode.createSyntheticRootNode();
    private long lastCaptureTime;

    public void mergeSyntheticRootNode(MutableProfileNode syntheticRootNode) {
        this.syntheticRootNode.mergeMatchedNode(syntheticRootNode);
    }

    public void updateLastCaptureTime(long captureTime) {
        lastCaptureTime = Math.max(lastCaptureTime, captureTime);
    }

    public long getLastCaptureTime() {
        return lastCaptureTime;
    }

    public MutableProfileNode getSyntheticRootNode() {
        return syntheticRootNode;
    }
}
