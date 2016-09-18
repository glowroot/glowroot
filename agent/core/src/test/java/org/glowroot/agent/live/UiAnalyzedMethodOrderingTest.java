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
package org.glowroot.agent.live;

import java.net.URISyntaxException;
import java.util.List;

import com.google.common.collect.ImmutableList;
import org.junit.Test;

import org.glowroot.agent.live.ClasspathCache.UiAnalyzedMethod;
import org.glowroot.agent.live.LiveWeavingServiceImpl.UiAnalyzedMethodOrdering;
import org.glowroot.agent.weaving.AnalyzedWorld;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class UiAnalyzedMethodOrderingTest {

    @Test
    public void shouldReadOrderedMethods() throws URISyntaxException {
        // given
        AnalyzedWorld analyzedWorld = mock(AnalyzedWorld.class);
        when(analyzedWorld.getClassLoaders())
                .thenReturn(ImmutableList.of(UiAnalyzedMethodOrderingTest.class.getClassLoader()));
        ClasspathCache classpathCache = new ClasspathCache(analyzedWorld, null);
        List<UiAnalyzedMethod> methods = classpathCache.getAnalyzedMethods(A.class.getName());

        // when
        methods = new UiAnalyzedMethodOrdering().sortedCopy(methods);

        // then
        assertThat(methods.get(0).name()).isEqualTo("z");
        assertThat(methods.get(1).name()).isEqualTo("x");
        assertThat(methods.get(2).name()).isEqualTo("y");
        assertThat(methods.get(3).name()).isEqualTo("a");
        assertThat(methods.get(3).parameterTypes()).hasSize(0);
        assertThat(methods.get(4).name()).isEqualTo("a");
        assertThat(methods.get(4).parameterTypes()).hasSize(1);
        assertThat(methods.get(5).name()).isEqualTo("a");
        assertThat(methods.get(5).parameterTypes()).hasSize(2);
        assertThat(methods.get(6).name()).isEqualTo("b");
        assertThat(methods.get(7).name()).isEqualTo("c");
    }

    @SuppressWarnings("unused")
    public static class A {
        private void b() {}
        private void a() {}
        private void c() {}
        private void a(int one, int two) {}
        private void a(int one) {}
        public void z() {}
        void y() {}
        protected void x() {}
    }
}
