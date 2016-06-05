/*
 * Copyright 2013-2016 the original author or authors.
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
package org.glowroot.tests;

import org.junit.runner.Description;
import org.junit.runner.Result;
import org.junit.runner.notification.RunListener;

public class SharedSetupRunListener extends RunListener {

    private static volatile boolean useSharedSetup;
    private static volatile WebDriverSetup sharedSetup;

    public static boolean useSharedSetup() {
        return useSharedSetup;
    }

    public static WebDriverSetup getSharedSetup() {
        return sharedSetup;
    }

    public static void setSharedSetup(WebDriverSetup setup) {
        sharedSetup = setup;
    }

    @Override
    public void testRunStarted(Description description) throws Exception {
        useSharedSetup = true;
    }

    @Override
    public void testRunFinished(Result result) throws Exception {
        if (sharedSetup != null) {
            sharedSetup.close(true);
            sharedSetup = null;
        }
    }
}
