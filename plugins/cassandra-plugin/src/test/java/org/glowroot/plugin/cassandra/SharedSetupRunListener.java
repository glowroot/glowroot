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
package org.glowroot.plugin.cassandra;

import org.junit.runner.Description;
import org.junit.runner.Result;
import org.junit.runner.notification.RunListener;

import org.glowroot.Containers;
import org.glowroot.container.Container;

public class SharedSetupRunListener extends RunListener {

    private static volatile Container sharedContainer;

    public static Container getContainer() throws Exception {
        if (sharedContainer == null) {
            CassandraWrapper.start();
            Container container = Containers.getSharedContainer();
            TempWorkaround.applyWorkaround(container);
            return container;
        }
        return sharedContainer;
    }

    public static void close(Container container) throws Exception {
        container.close();
        if (sharedContainer == null) {
            CassandraWrapper.stop();
        }
    }

    @Override
    public void testRunStarted(Description description) throws Exception {
        CassandraWrapper.start();
        sharedContainer = Containers.getSharedContainer();
        TempWorkaround.applyWorkaround(sharedContainer);
    }

    @Override
    public void testRunFinished(Result result) throws Exception {
        sharedContainer.close();
        CassandraWrapper.stop();
    }
}
