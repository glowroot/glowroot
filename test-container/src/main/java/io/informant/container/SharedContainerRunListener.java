/*
 * Copyright 2013 the original author or authors.
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
package io.informant.container;

import checkers.nullness.quals.Nullable;
import org.junit.runner.Description;
import org.junit.runner.Result;
import org.junit.runner.notification.RunListener;

/**
 * @author Trask Stalnaker
 * @since 0.5
 */
public class SharedContainerRunListener extends RunListener {

    private static volatile boolean useSharedContainer;
    @Nullable
    private static volatile Container sharedContainer;

    public static boolean useSharedContainer() {
        return useSharedContainer;
    }

    @Nullable
    public static Container getSharedContainer() {
        return sharedContainer;
    }

    public static void setSharedContainer(Container container) {
        sharedContainer = container;
    }

    @Override
    public void testRunStarted(Description description) throws Exception {
        useSharedContainer = true;
    }

    @Override
    public void testRunFinished(Result result) throws Exception {
        if (sharedContainer != null) {
            sharedContainer.close(true);
            sharedContainer = null;
        }
    }
}
