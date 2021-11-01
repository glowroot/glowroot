/*
 * Copyright 2016-2018 the original author or authors.
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
package org.glowroot.agent.model;

import java.util.Map;

import com.google.common.collect.Maps;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

public class QueryDataMapTest {

    @Test
    public void testBucketCollision() {
        // given
        QueryDataMap map = new QueryDataMap("");
        Map<String, SyncQueryData> uniqueQueries = Maps.newHashMap();
        for (int i = 0; i < 100; i++) {
            uniqueQueries.put("query-" + i, mock(SyncQueryData.class));
        }
        // when
        for (Map.Entry<String, SyncQueryData> entry : uniqueQueries.entrySet()) {
            map.put(entry.getKey(), entry.getValue());
        }
        // then
        for (Map.Entry<String, SyncQueryData> entry : uniqueQueries.entrySet()) {
            assertThat(map.get(new String(entry.getKey()))).isEqualTo(entry.getValue());
        }
    }
}
