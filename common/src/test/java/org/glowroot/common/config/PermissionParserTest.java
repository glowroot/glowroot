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
package org.glowroot.common.config;

import org.junit.Test;

import static org.assertj.core.api.Assertions.assertThat;

public class PermissionParserTest {

    @Test
    public void testWildcard() throws Exception {
        // given
        PermissionParser parser = new PermissionParser("agent:*:transaction");
        // when
        parser.parse();
        // then
        assertThat(parser.getAgentRollupIds()).containsExactly("*");
        assertThat(parser.getPermission()).isEqualTo("agent:transaction");
    }

    @Test
    public void testOneAgentId() throws Exception {
        // given
        PermissionParser parser = new PermissionParser("agent:abc:transaction");
        // when
        parser.parse();
        // then
        assertThat(parser.getAgentRollupIds()).containsExactly("abc");
        assertThat(parser.getPermission()).isEqualTo("agent:transaction");
    }

    @Test
    public void testTwoAgentIds() throws Exception {
        // given
        PermissionParser parser = new PermissionParser("agent:abc,mno:transaction");
        // when
        parser.parse();
        // then
        assertThat(parser.getAgentRollupIds()).containsExactly("abc", "mno");
        assertThat(parser.getPermission()).isEqualTo("agent:transaction");
    }

    @Test
    public void testThreeAgentIds() throws Exception {
        // given
        PermissionParser parser = new PermissionParser("agent:abc,mno,xyz:transaction");
        // when
        parser.parse();
        // then
        assertThat(parser.getAgentRollupIds()).containsExactly("abc", "mno", "xyz");
        assertThat(parser.getPermission()).isEqualTo("agent:transaction");
    }

    @Test
    public void testWithQuotes() throws Exception {
        // given
        PermissionParser parser = new PermissionParser("agent:abc,\"m,n:o\",xyz:transaction");
        // when
        parser.parse();
        // then
        assertThat(parser.getAgentRollupIds()).containsExactly("abc", "m,n:o", "xyz");
        assertThat(parser.getPermission()).isEqualTo("agent:transaction");
    }

    @Test
    public void testWithQuotesAndEscapes() throws Exception {
        // given
        PermissionParser parser = new PermissionParser("agent:abc,\"m\\\"n\\\\o\",xyz:transaction");
        // when
        parser.parse();
        // then
        assertThat(parser.getAgentRollupIds()).containsExactly("abc", "m\"n\\o", "xyz");
        assertThat(parser.getPermission()).isEqualTo("agent:transaction");
    }

    @Test
    public void testNoAgentId() throws Exception {
        // given
        PermissionParser parser = new PermissionParser("agent::transaction");
        // when
        parser.parse();
        // then
        assertThat(parser.getAgentRollupIds()).isEmpty();
        assertThat(parser.getPermission()).isEqualTo("agent:transaction");
    }

    @Test
    public void testAnotherNoAgentId() throws Exception {
        // given
        PermissionParser parser = new PermissionParser("agent:,:transaction");
        // when
        parser.parse();
        // then
        assertThat(parser.getAgentRollupIds()).isEmpty();
        assertThat(parser.getPermission()).isEqualTo("agent:transaction");
    }

    @Test
    public void testNoPermission() throws Exception {
        // given
        PermissionParser parser = new PermissionParser("agent:*");
        // when
        parser.parse();
        // then
        assertThat(parser.getAgentRollupIds()).containsExactly("*");
        assertThat(parser.getPermission()).isEqualTo("agent");
    }

    @Test
    public void testNoPermissionWithTrailingColon() throws Exception {
        // given
        PermissionParser parser = new PermissionParser("agent:*:");
        // when
        parser.parse();
        // then
        assertThat(parser.getAgentRollupIds()).containsExactly("*");
        assertThat(parser.getPermission()).isEqualTo("agent");
    }

    @Test
    public void testNoAgentIdAndNoPermission() throws Exception {
        // given
        PermissionParser parser = new PermissionParser("agent");
        // when
        parser.parse();
        // then
        assertThat(parser.getAgentRollupIds()).isEmpty();
        assertThat(parser.getPermission()).isEqualTo("agent");
    }

    @Test
    public void testNoAgentIdAndNoPermissionWithTrailingColon() throws Exception {
        // given
        PermissionParser parser = new PermissionParser("agent:");
        // when
        parser.parse();
        // then
        assertThat(parser.getAgentRollupIds()).isEmpty();
        assertThat(parser.getPermission()).isEqualTo("agent");
    }
}
