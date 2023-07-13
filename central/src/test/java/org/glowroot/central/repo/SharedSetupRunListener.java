/*
 * Copyright 2016 the original author or authors.
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
package org.glowroot.central.repo;

import org.junit.jupiter.api.extension.AfterAllCallback;
import org.junit.jupiter.api.extension.BeforeAllCallback;
import org.junit.jupiter.api.extension.ExtensionContext;

public class SharedSetupRunListener implements BeforeAllCallback, AfterAllCallback {

    private static volatile boolean shared;
    private static volatile boolean started;

    static void startCassandra() throws Exception {
        if (!shared) {
            CassandraWrapper.start();
            started = true;
        }
    }

    static void stopCassandra() throws Exception {
        if (!shared) {
            CassandraWrapper.stop();
            started = false;
        }
    }

    public static boolean isStarted() {
        return started;
    }

    @Override
    public void afterAll(ExtensionContext context) throws Exception {
        CassandraWrapper.stop();
    }

    @Override
    public void beforeAll(ExtensionContext context) throws Exception {
        CassandraWrapper.start();
        shared = true;
    }
}
