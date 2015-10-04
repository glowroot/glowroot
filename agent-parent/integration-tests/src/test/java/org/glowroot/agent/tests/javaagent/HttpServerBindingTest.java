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
package org.glowroot.agent.tests.javaagent;

import java.net.ServerSocket;

import com.google.common.collect.ImmutableMap;
import org.junit.Test;

import org.glowroot.agent.it.harness.Container;
import org.glowroot.agent.it.harness.impl.LocalContainer;
import org.glowroot.agent.it.harness.impl.SpyingLogbackFilter;

public class HttpServerBindingTest {

    @Test
    public void shouldTryToBindToAlreadyBoundPort() throws Exception {
        ServerSocket serverSocket = new ServerSocket(0);
        Container container = new LocalContainer(null, true, serverSocket.getLocalPort(), false,
                ImmutableMap.of("ui.bind.address", "0.0.0.0"));
        container.close();
        serverSocket.close();
        if (SpyingLogbackFilter.active()) {
            SpyingLogbackFilter.clearMessages();
        }
    }

    @Test
    public void shouldTryToBindToBadAddress() throws Exception {
        Container container = new LocalContainer(null, true, 4000, false,
                ImmutableMap.of("ui.bind.address", "a.b.c.d"));
        container.close();
        if (SpyingLogbackFilter.active()) {
            SpyingLogbackFilter.clearMessages();
        }
    }
}
