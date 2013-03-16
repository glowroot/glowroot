/**
 * Copyright 2012-2013 the original author or authors.
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
package io.informant.weaving;

import static org.fest.assertions.api.Assertions.assertThat;
import io.informant.weaving.preinit.GlobalCollector;
import io.informant.weaving.preinit.ReferencedMethod;

import java.io.IOException;
import java.util.List;

import org.junit.Test;

import com.google.common.collect.Sets;

/**
 * @author Trask Stalnaker
 * @since 0.5
 */
public class PreInitializeClassesTest {

    @Test
    public void shouldCheckHardcodedListAgainstReality() throws IOException {
        GlobalCollector globalCollector = new GlobalCollector();
        // register WeavingMetricImpl since the WeavingClassFileTransformer constructor accepts the
        // WeavingMetric interface and so WeavingMetricImpl would otherwise co unseen
        globalCollector.registerType("io/informant/core/trace/WeavingMetricNameImpl");
        // "call" WeavingClassFileTransformer constructor to capture its lazy loading weavers
        // structure
        globalCollector.processMethodFailIfNotFound(ReferencedMethod.from(
                "io/informant/weaving/WeavingClassFileTransformer", "<init>",
                "([Lio/informant/weaving/MixinType;[Lio/informant/weaving/Advice;"
                        + "Lio/informant/weaving/ParsedTypeCache;"
                        + "Lio/informant/weaving/WeavingMetric;)V"));
        // "call" WeavingClassFileTransformer.transform()
        globalCollector.processMethodFailIfNotFound(ReferencedMethod.from(
                "io/informant/weaving/WeavingClassFileTransformer", "transform",
                "(Ljava/lang/ClassLoader;Ljava/lang/String;Ljava/lang/Class;"
                        + "Ljava/security/ProtectionDomain;[B)[B"));
        globalCollector.processOverrides();
        // these assertions just help for debugging, since it can be hard to see the differences in
        // the very large lists below in the "real" assertion
        List<String> globalCollectorUsedTypes = globalCollector.usedTypes();
        globalCollectorUsedTypes.removeAll(PreInitializeClasses.maybeUsedTypes());
        assertThat(Sets.difference(Sets.newHashSet(globalCollectorUsedTypes),
                Sets.newHashSet(PreInitializeClasses.usedTypes()))).isEmpty();
        assertThat(Sets.difference(Sets.newHashSet(PreInitializeClasses.usedTypes()),
                Sets.newHashSet(globalCollectorUsedTypes))).isEmpty();

        // this is the real assertion
        assertThat(PreInitializeClasses.usedTypes()).isEqualTo(globalCollectorUsedTypes);
    }
}
