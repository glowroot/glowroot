/**
 * Copyright 2012 the original author or authors.
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
package org.informantproject.core.weaving;

import static org.fest.assertions.api.Assertions.assertThat;

import java.io.IOException;
import java.util.List;

import org.informantproject.core.weaving.preinit.GlobalCollector;
import org.informantproject.core.weaving.preinit.ReferencedMethod;
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
        // "call" LoggerFactoryImpl.getLogger() because it is called from LoggerFactory via
        // reflection and so it is not otherwise found by the global collector
        //
        // intentionally not forcing the propagation from LoggerFactoryImpl to
        // LogMessageSinkLocal.onLogMessage() since LogMessageSinkLocal is mostly executed inside of
        // a separate thread, and including it would propagate to lots of unnecessary H2 classes
        globalCollector.processMethodFailIfNotFound(ReferencedMethod.from(
                "org/informantproject/core/LoggerFactoryImpl", "getLogger",
                "(Ljava/lang/String;)Lorg/informantproject/api/Logger;"));

        // "call" WeavingClassFileTransformer constructor to capture its lazy loading weavers
        // structure
        globalCollector.processMethodFailIfNotFound(ReferencedMethod.from(
                "org/informantproject/core/weaving/WeavingClassFileTransformer", "<init>",
                "([Lorg/informantproject/api/weaving/Mixin;"
                        + "[Lorg/informantproject/core/weaving/Advice;"
                        + "Lorg/informantproject/core/trace/WeavingMetricImpl;)V"));
        // "call" WeavingClassFileTransformer.transform()
        globalCollector.processMethodFailIfNotFound(ReferencedMethod.from(
                "org/informantproject/core/weaving/WeavingClassFileTransformer", "transform",
                "(Ljava/lang/ClassLoader;Ljava/lang/String;Ljava/lang/Class;"
                        + "Ljava/security/ProtectionDomain;[B)[B"));
        globalCollector.processOverrides();
        // these assertions just help for debugging, since it can be hard to see the differences in
        // the very large lists below in the "real" assertions
        List<String> globalCollectorUsedTypes = globalCollector.usedTypes();
        globalCollectorUsedTypes.removeAll(PreInitializeClasses.maybeUsedTypes());
        assertThat(Sets.difference(Sets.newHashSet(globalCollectorUsedTypes),
                Sets.newHashSet(PreInitializeClasses.usedTypes()))).isEmpty();
        assertThat(Sets.difference(Sets.newHashSet(PreInitializeClasses.usedTypes()),
                Sets.newHashSet(globalCollectorUsedTypes))).isEmpty();

        // these are the real assertions
        assertThat(PreInitializeClasses.usedTypes()).isEqualTo(globalCollectorUsedTypes);
    }
}
