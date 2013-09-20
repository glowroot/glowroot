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

import java.io.IOException;
import java.util.List;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.common.collect.ImmutableList;
import org.junit.Test;

import io.informant.common.ObjectMappers;
import io.informant.weaving.ParsedTypeCache;

import static org.fest.assertions.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * @author Trask Stalnaker
 * @since 0.5
 */
public class PointcutConfigJsonServiceTest {

    private static final ObjectMapper mapper = ObjectMappers.create();

    @Test
    public void shouldReturnMatchingMethodNames() throws IOException {
        // given
        String typeName = "Abc";
        String partialMethodName = "m";
        int limit = 10;
        ImmutableList<String> matchingMethodNames = ImmutableList.of("mmm", "mno");
        ParsedTypeCache parsedTypeCache = mock(ParsedTypeCache.class);
        when(parsedTypeCache.getMatchingMethodNames(typeName, partialMethodName, limit))
                .thenReturn(matchingMethodNames);
        AdhocPointcutConfigJsonService jsonService =
                new AdhocPointcutConfigJsonService(parsedTypeCache);
        ObjectNode node = mapper.createObjectNode();
        node.put("typeName", typeName);
        node.put("partialMethodName", partialMethodName);
        node.put("limit", limit);
        // when
        String content = jsonService.getMatchingMethodNames(node.toString());
        // then
        assertThat(mapper.readValue(content, new TypeReference<List<String>>() {}))
                .isEqualTo(matchingMethodNames);
    }
}
