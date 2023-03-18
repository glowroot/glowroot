/*
 * Copyright 2017-2018 the original author or authors.
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
package org.glowroot.agent.plugin.elasticsearch;

import com.google.common.collect.ImmutableList;
import org.glowroot.agent.it.harness.Container;
import org.glowroot.agent.it.harness.Containers;
import org.glowroot.agent.it.harness.impl.JavaagentContainer;
import org.junit.jupiter.api.extension.BeforeAllCallback;
import org.junit.jupiter.api.extension.ExtensionContext;

public class ElasticsearchExtension implements BeforeAllCallback {

    private static volatile Container sharedContainer;

    public static Container getContainer() {
        return sharedContainer;
    }

    @Override
    public void beforeAll(ExtensionContext context) throws Exception {
        assumeJdkLessThan18();
        ElasticsearchStore store = (ElasticsearchStore) getStore(context).getOrComputeIfAbsent("cassandra", (v) -> new ElasticsearchStore());
        sharedContainer = store.getContainer();
    }

    private ExtensionContext.Store getStore(ExtensionContext context) {
        // we want the same cassandra instance used for all test in the same test suite, so we use
        // context.getRoot()
        return context.getRoot().getStore(ExtensionContext.Namespace.create(ElasticsearchExtension.class));
    }

    private static void assumeJdkLessThan18() {
        String javaVersion = StandardSystemProperty.JAVA_VERSION.value();

        int majorVersion = getJavaMajorVersion(javaVersion);
        boolean javaVersionOk = majorVersion < 18;

        String message = "Elasticsearch 6.x requires a SecurityManager and thus is not compatible with java 18+,"
                + " but this test is running under Java " + javaVersion + ".";

        Assumptions.assumeTrue(javaVersionOk, message);
    }

    private static int getJavaMajorVersion(String javaVersion) {
        if (javaVersion == null) {
            return -1;
        }
        String[] versionElements = javaVersion.split("\\.");
        int version = Integer.parseInt(versionElements[0]);
        if (version == 1) {
            return Integer.parseInt(versionElements[1]);
        }
        return version;
    }

    static class ElasticsearchStore implements ExtensionContext.Store.CloseableResource {
        private final Container container;

        public ElasticsearchStore() {
            try {
                startElasticsearch();
                this.container = createContainer();
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }

        private Container createContainer() throws Exception {
            if (Containers.useJavaagent()) {
                // this is needed to avoid exception
                // see org.elasticsearch.transport.netty4.Netty4Utils.setAvailableProcessors()
                return JavaagentContainer.createWithExtraJvmArgs(
                        ImmutableList.of("-Des.set.netty.runtime.available.processors=false"));
            } else {
                return Containers.create();
            }
        }

        public Container getContainer() {
            return container;
        }

        private void startElasticsearch() throws Exception {
            ElasticsearchWrapper.start();
        }

        @Override
        public void close() throws Throwable {
            this.container.close();
            ElasticsearchWrapper.stop();
        }
    }
}
