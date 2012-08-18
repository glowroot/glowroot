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
        // first call constructor
        globalCollector.processMethod(ReferencedMethod.from(
                "org/informantproject/core/weaving/WeavingClassFileTransformer", "<init>",
                "([Lorg/informantproject/api/weaving/Mixin;"
                        + "[Lorg/informantproject/core/weaving/Advice;"
                        + "Lorg/informantproject/core/trace/WeavingMetricImpl;)V"));
        // then call transform()
        globalCollector.processMethod(ReferencedMethod.from(
                "org/informantproject/core/weaving/WeavingClassFileTransformer", "transform",
                "(Ljava/lang/ClassLoader;Ljava/lang/String;Ljava/lang/Class;"
                        + "Ljava/security/ProtectionDomain;[B)[B"));
        globalCollector.processOverrides();
        // these assertions just help for debugging, since it can be hard to see the differences in
        // the very large lists below in the "real" assertions
        assertThat(Sets.difference(Sets.newHashSet(globalCollector.usedTypes()),
                Sets.newHashSet(PreInitializeClasses.usedTypes()))).isEmpty();
        assertThat(Sets.difference(Sets.newHashSet(PreInitializeClasses.usedTypes()),
                Sets.newHashSet(globalCollector.usedTypes()))).isEmpty();

        // these are the real assertions
        assertThat(PreInitializeClasses.usedTypes()).isEqualTo(globalCollector.usedTypes());
    }
}
