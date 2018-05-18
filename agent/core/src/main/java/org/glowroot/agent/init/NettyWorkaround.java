/*
 * Copyright 2015-2018 the original author or authors.
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
package org.glowroot.agent.init;

import io.netty.util.internal.PlatformDependent;

import org.glowroot.agent.util.JavaVersion;

public class NettyWorkaround {

    private NettyWorkaround() {}

    public static void run() {
        if (JavaVersion.isIbmJvm()) {
            // WebSphere crashes on startup without this workaround (at least when pointing to
            // glowroot central and using WebSphere 8.5.5.11)
            String prior = System.getProperty("io.netty.noUnsafe");
            System.setProperty("io.netty.noUnsafe", "true");
            try {
                if (PlatformDependent.hasUnsafe()) {
                    throw new IllegalStateException("Netty property to disable usage of UNSAFE was"
                            + " not set early enough, please report to the Glowroot project");
                }
            } finally {
                if (prior == null) {
                    System.clearProperty("io.netty.noUnsafe");
                } else {
                    System.setProperty("io.netty.noUnsafe", prior);
                }
            }
        }
    }
}
