/*
 * Copyright 2016-2017 the original author or authors.
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
package org.glowroot.agent.plugin.jaxrs;

import org.junit.Test;

import static org.assertj.core.api.Assertions.assertThat;

public class ResourceMethodMetaTest {

    @Test
    public void should() {
        assertThat(ResourceMethodMeta.combine(null, null)).isEqualTo("/");
        assertThat(ResourceMethodMeta.combine("", null)).isEqualTo("/");
        assertThat(ResourceMethodMeta.combine(null, "")).isEqualTo("/");
        assertThat(ResourceMethodMeta.combine("", "")).isEqualTo("/");
        assertThat(ResourceMethodMeta.combine("/abc", "/xyz")).isEqualTo("/abc/xyz");
        assertThat(ResourceMethodMeta.combine("/abc", "xyz")).isEqualTo("/abc/xyz");
        assertThat(ResourceMethodMeta.combine("abc", "/xyz")).isEqualTo("/abc/xyz");
        assertThat(ResourceMethodMeta.combine("abc", "xyz")).isEqualTo("/abc/xyz");
        assertThat(ResourceMethodMeta.combine("abc", null)).isEqualTo("/abc");
        assertThat(ResourceMethodMeta.combine(null, "xyz")).isEqualTo("/xyz");
        assertThat(ResourceMethodMeta.combine("abc", "")).isEqualTo("/abc");
        assertThat(ResourceMethodMeta.combine("", "xyz")).isEqualTo("/xyz");
        assertThat(ResourceMethodMeta.combine("/abc", "")).isEqualTo("/abc");
        assertThat(ResourceMethodMeta.combine("", "/xyz")).isEqualTo("/xyz");
    }
}
