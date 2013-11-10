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
package io.informant.local.ui;

import java.net.URISyntaxException;

import com.google.common.collect.ImmutableList;
import org.junit.Test;

import io.informant.weaving.ParsedTypeCache;

import static org.fest.assertions.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * @author Trask Stalnaker
 * @since 0.5
 */
public class ClasspathCacheTest {

    @Test
    public void shouldRead() throws URISyntaxException {
        ParsedTypeCache parsedTypeCache = mock(ParsedTypeCache.class);
        when(parsedTypeCache.getClassLoaders())
                .thenReturn(ImmutableList.of(ClasspathCacheTest.class.getClassLoader()));
        ClasspathCache classpathCache = new ClasspathCache(parsedTypeCache);
        assertThat(classpathCache.getMatchingTypeNames("lang.stringutils", 10))
                .contains("org.apache.commons.lang.StringUtils");
    }
}
