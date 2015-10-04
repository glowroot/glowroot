/*
 * Copyright 2012-2015 the original author or authors.
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
package org.glowroot.agent.weaving;

import java.io.IOException;
import java.util.List;

import com.google.common.collect.Sets;
import org.junit.Test;

import static org.assertj.core.api.Assertions.assertThat;

// this test (and associated helper classes) are in glowroot-fat-agent-core so that
// PreInitializeStorageShutdownClassesTest can share the helper classes
public class PreInitializeWeavingClassesTest {

    @Test
    public void shouldCheckHardcodedListAgainstReality() throws IOException {
        GlobalCollector globalCollector = new GlobalCollector();
        // register WeavingTimerServiceImpl since the WeavingClassFileTransformer constructor
        // accepts the WeavingTimerService interface and so WeavingTimerServiceImpl would otherwise
        // go unseen
        globalCollector.registerClass("org/glowroot/agent/impl/WeavingTimerServiceImpl");
        globalCollector.registerClass("org/glowroot/agent/weaving/ImmutableAdvice");
        // "call" AnalyzedWorld constructor to capture types used by LoadingCache
        // (so these types will be in the list of possible subtypes later on)
        globalCollector.processMethodFailIfNotFound(
                ReferencedMethod.from("org/glowroot/agent/weaving/AnalyzedWorld", "<init>",
                        "(L" + getGuavaSupplierInternalName() + ";Ljava/util/List;Ljava/util/List;"
                                + "Lorg/glowroot/agent/weaving/ExtraBootResourceFinder;)V"));
        // "call" WeavingClassFileTransformer constructor
        globalCollector.processMethodFailIfNotFound(ReferencedMethod.from(
                "org/glowroot/agent/weaving/WeavingClassFileTransformer", "<init>",
                "(Ljava/util/List;Ljava/util/List;L" + getGuavaSupplierInternalName() + ";"
                        + "Lorg/glowroot/agent/weaving/AnalyzedWorld;"
                        + "Lorg/glowroot/agent/weaving/WeavingTimerService;Z)V"));
        // "call" WeavingClassFileTransformer.transform()
        globalCollector.processMethodFailIfNotFound(
                ReferencedMethod.from("org/glowroot/agent/weaving/WeavingClassFileTransformer",
                        "transform", "(Ljava/lang/ClassLoader;Ljava/lang/String;Ljava/lang/Class;"
                                + "Ljava/security/ProtectionDomain;[B)[B"));
        globalCollector.processOverrides();
        // these assertions just help for debugging, since it can be hard to see the differences in
        // the very large lists below in the "real" assertion
        List<String> globalCollectorUsedTypes = globalCollector.usedInternalNames();
        globalCollectorUsedTypes.removeAll(PreInitializeWeavingClasses.maybeUsedTypes());
        assertThat(Sets.difference(Sets.newHashSet(globalCollectorUsedTypes),
                Sets.newHashSet(PreInitializeWeavingClasses.usedTypes()))).isEmpty();
        assertThat(Sets.difference(Sets.newHashSet(PreInitializeWeavingClasses.usedTypes()),
                Sets.newHashSet(globalCollectorUsedTypes))).isEmpty();
        assertThat(PreInitializeWeavingClasses.usedTypes()).hasSameSizeAs(globalCollectorUsedTypes);
    }

    private static String getGuavaSupplierInternalName() {
        try {
            Class.forName("org.glowroot.agent.shaded.google.common.base.Supplier");
            return "org/glowroot/agent/shaded/google/common/base/Supplier";
        } catch (ClassNotFoundException e) {
            return "com/google/common/base/Supplier";
        }
    }
}
