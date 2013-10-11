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
package io.informant.api;

import org.junit.Test;

import static org.fest.assertions.api.Assertions.assertThat;

/**
 * @author Trask Stalnaker
 * @since 0.5
 */
public class UnresolvedMethodTest {

    @Test
    public void shouldCallPublicMethod() {
        UnresolvedMethod getPublicMethod =
                UnresolvedMethod.from(SomeObject.class.getName(), "getPublic");
        assertThat(getPublicMethod.invokeStatic(UnresolvedMethodTest.class.getClassLoader(), ""))
                .isEqualTo("public");
    }

    @Test
    public void shouldCallPrivateMethod() {
        UnresolvedMethod getPrivateMethod =
                UnresolvedMethod.from(SomeObject.class.getName(), "getPrivate");
        assertThat(getPrivateMethod.invokeStatic(UnresolvedMethodTest.class.getClassLoader(), ""))
                .isEqualTo("private");
    }

    @SuppressWarnings("unused")
    private static class SomeObject {

        public static String getPublic() {
            return "public";
        }

        private static String getPrivate() {
            return "private";
        }
    }
}
