/*
 * Copyright 2015-2016 the original author or authors.
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
package org.glowroot.agent.plugin.cassandra;

import org.glowroot.agent.it.harness.Container;
import org.glowroot.agent.it.harness.Containers;
import org.junit.jupiter.api.extension.BeforeAllCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.opentest4j.TestAbortedException;

public class CassandraExtension implements BeforeAllCallback {

    private static volatile Container sharedContainer;

    public static Container getContainer() {
        return sharedContainer;
    }

    @Override
    public void beforeAll(ExtensionContext extensionContext) {
        CassandraStore store = (CassandraStore) getStore(extensionContext).getOrComputeIfAbsent("cassandra", (v) -> new CassandraStore());
        sharedContainer = store.getContainer();
    }

    private ExtensionContext.Store getStore(ExtensionContext context) {
        // we want the same cassandra instance used for all test in the same test suite, so we use
        // context.getRoot()
        return context.getRoot().getStore(ExtensionContext.Namespace.create(CassandraExtension.class));
    }

    static class CassandraStore implements ExtensionContext.Store.CloseableResource {
        private final Container container;

        public CassandraStore() {
            try {
                startCassandra();
                this.container = Containers.create();
            } catch (TestAbortedException te) {
                throw te;
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }

        public Container getContainer() {
            return container;
        }

        private void startCassandra() throws Exception {
            CassandraWrapper.start();
            // need to trigger and clear known warning message in static initializer
            try {
                Class.forName("com.datastax.driver.core.NettyUtil");
            } catch (ClassNotFoundException e) {
                // old versions of datastax driver don't have this class
            }
        }

        @Override
        public void close() throws Throwable {
            this.container.close();
            CassandraWrapper.stop();
        }
    }
}
