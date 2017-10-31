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
package org.glowroot.agent.plugin.servlet;

import java.util.List;
import java.util.regex.Pattern;

import com.google.common.collect.ImmutableList;
import org.junit.Test;

import static org.assertj.core.api.Assertions.assertThat;

public class ServletMessageSupplierTest {

    @Test
    public void shouldMask() {
        // given
        List<Pattern> maskPatterns = ImmutableList.of(Pattern.compile(".*password.*"));
        String requestQueryString = "test=one&xpasswordy=maskme&test=two";
        // when
        String maskRequestQueryString =
                ServletMessageSupplier.maskRequestQueryString(requestQueryString, maskPatterns);
        // then
        assertThat(maskRequestQueryString).isEqualTo("test=one&xpasswordy=****&test=two");
    }

    @Test
    public void shouldNotMask() {
        // given
        List<Pattern> maskPatterns = ImmutableList.of(Pattern.compile(".*password.*"));
        String requestQueryString = "test=one&xpassworry=nomask&test=two";
        // when
        String maskRequestQueryString =
                ServletMessageSupplier.maskRequestQueryString(requestQueryString, maskPatterns);
        // then
        assertThat(maskRequestQueryString).isEqualTo("test=one&xpassworry=nomask&test=two");
    }

    @Test
    public void shouldMaskStrange() {
        // given
        List<Pattern> maskPatterns = ImmutableList.of(Pattern.compile(".*password.*"));
        String requestQueryString = "test=one&&&===&=&xpasswordy=mask=me&&&==&test=two";
        // when
        String maskRequestQueryString =
                ServletMessageSupplier.maskRequestQueryString(requestQueryString, maskPatterns);
        // then
        assertThat(maskRequestQueryString)
                .isEqualTo("test=one&&&===&=&xpasswordy=****&&&==&test=two");
    }

    @Test
    public void shouldNotMaskStrange() {
        // given
        List<Pattern> maskPatterns = ImmutableList.of(Pattern.compile(".*password.*"));
        String requestQueryString = "test=one&&&===&=&xpassworry=no=mask&&&==&test=two";
        // when
        String maskRequestQueryString =
                ServletMessageSupplier.maskRequestQueryString(requestQueryString, maskPatterns);
        // then
        assertThat(maskRequestQueryString)
                .isEqualTo("test=one&&&===&=&xpassworry=no=mask&&&==&test=two");
    }
}
