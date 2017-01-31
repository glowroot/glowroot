/*
 * Copyright 2017 the original author or authors.
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
package org.glowroot.common.repo.util;

import org.junit.Test;

import static org.assertj.core.api.Assertions.assertThat;

public class CompilationsTest {

    @Test
    public void testClassNameParsing() {
        assertThat(Compilations.getPublicClassName(
                "public class A { public static void main(String[] args) {} } class B {}"))
                        .isEqualTo("A");
        assertThat(Compilations.getPublicClassName("public class X extends Object {}"))
                .isEqualTo("X");
        assertThat(Compilations.getPublicClassName(
                "public class X extends Object {} public class Y extends Object {}"))
                        .isEqualTo("X");
        assertThat(Compilations
                .getPublicClassName("class X extends Object {} public class Y extends Object {}"))
                        .isEqualTo("Y");
        assertThat(Compilations
                .getPublicClassName("class X extends Object {}\npublic class Y extends Object {}"))
                        .isEqualTo("Y");
        assertThat(Compilations
                .getPublicClassName("class X extends Object {}public class Y extends Object {}"))
                        .isEqualTo("Y");
        assertThat(Compilations.getPublicClassName("class X extends Object {}")).isEqualTo("");
    }

    @Test
    public void testNonDefaultPackageClassNameParsing() {
        assertThat(Compilations.getPublicClassName("package a.b; public class X {}"))
                .isEqualTo("a.b.X");
        assertThat(Compilations.getPublicClassName("  package a.b; public class X {}"))
                .isEqualTo("a.b.X");
        assertThat(Compilations.getPublicClassName("\n  package a.b; \n public class X {}"))
                .isEqualTo("a.b.X");
    }
}
