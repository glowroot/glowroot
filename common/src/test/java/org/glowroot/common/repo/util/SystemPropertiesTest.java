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

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import org.junit.Test;

import org.glowroot.common.util.SystemProperties;

import static org.assertj.core.api.Assertions.assertThat;

public class SystemPropertiesTest {

    @Test
    public void shouldMaskJvmArgs() {
        assertThat(SystemProperties.maskJvmArgs(ImmutableList.of("-Xmx1g", "-Dtest=one"),
                ImmutableList.<String>of())).containsExactly("-Xmx1g", "-Dtest=one");
        assertThat(SystemProperties.maskJvmArgs(ImmutableList.of("-Xmx1g", "-Dtest=one"),
                ImmutableList.<String>of("test"))).containsExactly("-Xmx1g", "-Dtest=****");
        assertThat(SystemProperties.maskJvmArgs(ImmutableList.of("-Xmx1g", "-Dtest=one"),
                ImmutableList.<String>of("t"))).containsExactly("-Xmx1g", "-Dtest=one");
        assertThat(SystemProperties.maskJvmArgs(ImmutableList.of("-Xmx1g", "-Dtest=one"),
                ImmutableList.<String>of("t*"))).containsExactly("-Xmx1g", "-Dtest=****");
        assertThat(SystemProperties.maskJvmArgs(ImmutableList.of("-Xmx1g", "-Dtest=one"),
                ImmutableList.<String>of("e*"))).containsExactly("-Xmx1g", "-Dtest=one");
        assertThat(SystemProperties.maskJvmArgs(ImmutableList.of("-Xmx1g", "-Dtest=one"),
                ImmutableList.<String>of("*t*"))).containsExactly("-Xmx1g", "-Dtest=****");
        assertThat(SystemProperties.maskJvmArgs(ImmutableList.of("-Xmx1g", "-Dtest=one"),
                ImmutableList.<String>of("*x*"))).containsExactly("-Xmx1g", "-Dtest=one");
        assertThat(SystemProperties.maskJvmArgs(ImmutableList.of("-Xmx1g", "-DtEst=one"),
                ImmutableList.<String>of("teSt"))).containsExactly("-Xmx1g", "-DtEst=****");
    }

    @Test
    public void shouldMaskSystemProperties() {
        assertThat(SystemProperties
                .maskSystemProperties(ImmutableMap.of("test", "one"), ImmutableList.<String>of())
                .get("test")).isEqualTo("one");
        assertThat(SystemProperties.maskSystemProperties(ImmutableMap.of("test", "one"),
                ImmutableList.<String>of("test")).get("test")).isEqualTo("****");
        assertThat(SystemProperties
                .maskSystemProperties(ImmutableMap.of("test", "one"), ImmutableList.<String>of("t"))
                .get("test")).isEqualTo("one");
        assertThat(SystemProperties.maskSystemProperties(ImmutableMap.of("test", "one"),
                ImmutableList.<String>of("t*")).get("test")).isEqualTo("****");
        assertThat(SystemProperties.maskSystemProperties(ImmutableMap.of("test", "one"),
                ImmutableList.<String>of("e*")).get("test")).isEqualTo("one");
        assertThat(SystemProperties.maskSystemProperties(ImmutableMap.of("test", "one"),
                ImmutableList.<String>of("*t*")).get("test")).isEqualTo("****");
        assertThat(SystemProperties.maskSystemProperties(ImmutableMap.of("test", "one"),
                ImmutableList.<String>of("*x*")).get("test")).isEqualTo("one");
        assertThat(SystemProperties.maskSystemProperties(ImmutableMap.of("tEst", "one"),
                ImmutableList.<String>of("teSt")).get("tEst")).isEqualTo("****");
    }
}
