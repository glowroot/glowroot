/*
 * Copyright 2019-2023 the original author or authors.
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
package org.glowroot.agent.bytecode.api;

// this is for bytecode helpers that may still be called even when Glowroot fails to start
public class BytecodeSafe {

    private BytecodeSafe() {}

    // this call is woven into ManagementFactory.getPlatformMBeanServer() very early, and startup
    // could still fail due to many valid reasons, which shouldn't cause the monitored application
    // to fail
    public static void exitingGetPlatformMBeanServer() {
        if (!BytecodeServiceHolder.isGlowrootFailedToStart()) {
            BytecodeServiceHolder.get().exitingGetPlatformMBeanServer();
        }
    }
}
