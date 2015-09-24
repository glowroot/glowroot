/*
 * Copyright 2014-2015 the original author or authors.
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
package org.glowroot.server.simplerepo;

import java.io.IOException;
import java.util.List;

import com.google.common.collect.Sets;
import org.junit.Test;

import org.glowroot.agent.weaving.preinit.GlobalCollector;
import org.glowroot.agent.weaving.preinit.ReferencedMethod;

import static org.assertj.core.api.Assertions.assertThat;

public class PreInitializeStorageShutdownClassesTest {

    @Test
    public void shouldCheckHardcodedListAgainstReality() throws IOException {
        GlobalCollector globalCollector = new GlobalCollector();
        globalCollector.registerClass("org/h2/jdbc/JdbcConnection");
        // "call" DataSource$ShutdownHookThread.run() and CappedDatabase$ShutdownHookThread.run()
        // because class loading during jvm shutdown throws exception
        globalCollector.processMethodFailIfNotFound(ReferencedMethod.from(
                "org/glowroot/server/simplerepo/util/ConnectionPool$ShutdownHookThread", "run",
                "()V"));
        globalCollector.processMethodFailIfNotFound(ReferencedMethod.from(
                "org/glowroot/server/simplerepo/util/CappedDatabase$ShutdownHookThread", "run",
                "()V"));
        globalCollector.processOverrides();
        // these assertions just help for debugging, since it can be hard to see the differences in
        // the very large lists below in the "real" assertion
        List<String> globalCollectorUsedTypes = globalCollector.usedInternalNames();
        globalCollectorUsedTypes.removeAll(PreInitializeStorageShutdownClasses.maybeUsedTypes());
        assertThat(Sets.difference(Sets.newHashSet(globalCollectorUsedTypes),
                Sets.newHashSet(PreInitializeStorageShutdownClasses.usedTypes()))).isEmpty();
        assertThat(Sets.difference(Sets.newHashSet(PreInitializeStorageShutdownClasses.usedTypes()),
                Sets.newHashSet(globalCollectorUsedTypes))).isEmpty();

        // this is the real assertion
        assertThat(PreInitializeStorageShutdownClasses.usedTypes())
                .isEqualTo(globalCollectorUsedTypes);
    }
}
