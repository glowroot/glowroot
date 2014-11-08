/*
 * Copyright 2013-2014 the original author or authors.
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
package org.glowroot.container;

import org.checkerframework.checker.nullness.qual.Nullable;
import org.junit.runner.Description;
import org.junit.runner.Result;
import org.junit.runner.notification.RunListener;

public class SharedContainerRunListener extends RunListener {

    private static volatile boolean useSharedContainer;
    @Nullable
    private static volatile Container sharedLocalContainer;
    @Nullable
    private static volatile Container sharedJavaagentContainer;

    public static boolean useSharedContainer() {
        return useSharedContainer;
    }

    @Nullable
    public static Container getSharedLocalContainer() {
        return sharedLocalContainer;
    }

    @Nullable
    public static Container getSharedJavaagentContainer() {
        return sharedJavaagentContainer;
    }

    public static void setSharedLocalContainer(Container container) {
        sharedLocalContainer = container;
    }

    public static void setSharedJavaagentContainer(Container container) {
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
