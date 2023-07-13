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

import org.glowroot.agent.it.harness.Container;
import org.glowroot.agent.it.harness.Containers;
import org.junit.jupiter.api.extension.BeforeAllCallback;
import org.junit.jupiter.api.extension.ExtensionContext;

public class KafkaExtension implements BeforeAllCallback {

    private static volatile Container sharedContainer;

    public static Container getContainer() {
        return sharedContainer;
    }


    @Override
    public void beforeAll(ExtensionContext context) throws Exception {
        KafkaStore store = (KafkaStore) getStore(context).getOrComputeIfAbsent("kafka", (v) -> new KafkaStore());
        sharedContainer = store.getContainer();
    }


    private ExtensionContext.Store getStore(ExtensionContext context) {
        // we want the same kafka instance used for all test in the same test suite, so we use
        // context.getRoot()
        return context.getRoot().getStore(ExtensionContext.Namespace.create(KafkaExtension.class));
    }

    static class KafkaStore implements ExtensionContext.Store.CloseableResource {
        private final Container container;

        public KafkaStore() {
            try {
                startKafka();
                this.container = Containers.create();
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }

        public Container getContainer() {
            return container;
        }

        private void startKafka() throws Exception {
            KafkaWrapper.start();
        }

        @Override
        public void close() throws Throwable {
            this.container.close();
            KafkaWrapper.stop();
        }
    }
}
