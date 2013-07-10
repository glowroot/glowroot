/*
 * Copyright 2013 the original author or authors.
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

import java.io.IOException;
import java.util.List;

import org.junit.Test;

import static org.fest.assertions.api.Assertions.assertThat;

/**
 * @author Trask Stalnaker
 * @since 0.5
 */
public class ParsedTypeCacheTest {

    @Test
    public void shouldNotMatchNativeMethods() throws ClassNotFoundException, IOException {
        // given
        ParsedTypeCache parsedTypeCache = new ParsedTypeCache();
        parsedTypeCache.getParsedType(Dummy.class.getName(),
                ParsedTypeCacheTest.class.getClassLoader());
        // when
        List<String> names = parsedTypeCache.getMatchingMethodNames(Dummy.class.getName(), "do", 5);
        // then
        assertThat(names).containsOnly("doAbstract", "doSomething");
    }

    @Test
    public void shouldNotIncludeSynchronizedOrFinalMethodModifiers() throws ClassNotFoundException,
            IOException {
        // given
        ParsedTypeCache parsedTypeCache = new ParsedTypeCache();
        parsedTypeCache.getParsedType(Dummy.class.getName(),
                ParsedTypeCacheTest.class.getClassLoader());
        // when
        List<ParsedMethod> parsedMethods =
                parsedTypeCache.getMatchingParsedMethods(Dummy.class.getName(), "doSomething");
        // then
        assertThat(parsedMethods).hasSize(1);
        assertThat(parsedMethods.get(0).getModifiers()).isZero();
    }

    private abstract static class Dummy {
        native void doNative();
        abstract void doAbstract();
        final synchronized void doSomething() {}
    }
}
