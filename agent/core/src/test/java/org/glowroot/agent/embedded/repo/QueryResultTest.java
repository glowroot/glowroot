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
package org.glowroot.agent.embedded.repo;

import com.google.common.collect.ImmutableList;
import org.junit.Test;

import org.glowroot.common.model.Result;

import static org.assertj.core.api.Assertions.assertThat;

public class QueryResultTest {

    @Test
    public void testUnderLimit() {
        // given
        ImmutableList<String> records = ImmutableList.of("a");
        // when
        Result<String> queryResult = Result.create(records, 2);
        // then
        assertThat(queryResult.records()).hasSize(1);
        assertThat(queryResult.moreAvailable()).isFalse();
    }

    @Test
    public void testAtLimit() {
        // given
        ImmutableList<String> records = ImmutableList.of("a", "b");
        // when
        Result<String> queryResult = Result.create(records, 2);
        // then
        assertThat(queryResult.records()).hasSize(2);
        assertThat(queryResult.moreAvailable()).isFalse();
    }

    @Test
    public void testOverLimit() {
        // given
        ImmutableList<String> records = ImmutableList.of("a", "b", "c");
        // when
        Result<String> queryResult = Result.create(records, 2);
        // then
        assertThat(queryResult.records()).hasSize(2);
        assertThat(queryResult.moreAvailable()).isTrue();
    }
}
