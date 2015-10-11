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
package org.glowroot.agent.it.harness;

import javax.annotation.Nullable;

import org.junit.runner.Description;
import org.junit.runner.Result;
import org.junit.runner.notification.RunListener;

public class SharedContainerRunListener extends RunListener {

    private static volatile boolean useSharedContainer;
    private static volatile @Nullable Container sharedLocalContainer;
    private static volatile @Nullable Container sharedJavaagentContainer;

    static boolean useSharedContainer() {
        return useSharedContainer;
    }

    static @Nullable Container getSharedLocalContainer() {
        return sharedLocalContainer;
    }

    static @Nullable Container getSharedJavaagentContainer() {
        return sharedJavaagentContainer;
    }

    static void setSharedLocalContainer(Container container) {
        sharedLocalContainer = container;
    }

    static void setSharedJavaagentContainer(Container container) {
        sharedJavaagentContainer = container;
    }

    @Override
    public void testRunStarted(Description description) throws Exception {
        useSharedContainer = true;
    }

    @Override
    public void testRunFinished(Result result) throws Exception {
        // need to shut down javaagent container first, otherwise local container close() will fail
        // on Threads.preShutdownCheck() since javaagent container creates local threads to manage
        // the external jvm
        if (sharedJavaagentContainer != null) {
            sharedJavaagentContainer.close(true);
            sharedJavaagentContainer = null;
        }
        if (sharedLocalContainer != null) {
            sharedLocalContainer.close(true);
            sharedLocalContainer = null;
        }
    }
}
