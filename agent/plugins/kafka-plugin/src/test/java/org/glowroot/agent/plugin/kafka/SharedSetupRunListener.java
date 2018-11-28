/*
 * Copyright 2018 the original author or authors.
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
package org.glowroot.agent.plugin.kafka;

import org.junit.runner.Description;
import org.junit.runner.Result;
import org.junit.runner.notification.RunListener;

import org.glowroot.agent.it.harness.Container;
import org.glowroot.agent.it.harness.Containers;

public class SharedSetupRunListener extends RunListener {

    private static volatile Container sharedContainer;

    public static Container getContainer() throws Exception {
        if (sharedContainer == null) {
            startKafka();
            Container container = Containers.create();
            return container;
        }
        return sharedContainer;
    }

    public static void close(Container container) throws Exception {
        if (sharedContainer == null) {
            container.close();
            KafkaWrapper.stop();
        }
    }

    @Override
    public void testRunStarted(Description description) throws Exception {
        startKafka();
        sharedContainer = Containers.create();
    }

    @Override
    public void testRunFinished(Result result) throws Exception {
        sharedContainer.close();
        KafkaWrapper.stop();
    }

    private static void startKafka() throws Exception {
        KafkaWrapper.start();
    }
}
