/*
 * Copyright 2015 the original author or authors.
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
package org.glowroot.weaving;

import java.io.File;
import java.net.URL;

import com.google.common.base.Splitter;
import com.google.common.base.StandardSystemProperty;
import com.google.common.collect.ImmutableList;
import org.junit.Test;

import static org.assertj.core.api.Assertions.assertThat;

public class ExtraBootResourceFinderTest {

    @Test
    public void testFound() {
        // given
        File guavaJarFile = getJarFile("guava");
        ExtraBootResourceFinder finder =
                new ExtraBootResourceFinder(ImmutableList.of(guavaJarFile));
        // when
        URL url = finder.findResource("com/google/common/base/Strings.class");
        // then
        assertThat(url).isNotNull();
    }

    @Test
    public void testNotFound() {
        // given
        File guavaJarFile = getJarFile("guava");
        ExtraBootResourceFinder finder =
                new ExtraBootResourceFinder(ImmutableList.of(guavaJarFile));
        // when
        URL url = finder.findResource("com/google/common/base/XyzAbc.class");
        // then
        assertThat(url).isNull();
    }

    @Test
    public void testInvalidJarPath() {
        // given
        ExtraBootResourceFinder finder =
                new ExtraBootResourceFinder(ImmutableList.of(new File("xyzabc.jar")));
        // when
        URL url = finder.findResource("com/google/common/base/Strings.class");
        // then
        assertThat(url).isNull();
    }

    private File getJarFile(String name) {
        String classpath = StandardSystemProperty.JAVA_CLASS_PATH.value();
        for (String path : Splitter.on(File.pathSeparator).split(classpath)) {
            if (path.contains(name)) {
                return new File(path);
            }
        }
        throw new AssertionError();
    }
}
