/*
 * Copyright 2012-2017 the original author or authors.
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
package org.glowroot.ui;

import org.junit.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

public class TraceJsonServiceTest {

    @Test
    public void test() throws Exception {
        // given
        TraceCommonService traceCommonService = mock(TraceCommonService.class);
        TraceJsonService traceJsonService = new TraceJsonService(traceCommonService);
        // when
        String json = traceJsonService.getHeader("",
                ImmutableHeaderRequest.builder()
                        .traceId("1234")
                        .build());
        // then
        assertThat(json).isEqualTo("{\"expired\":true}");
    }
}
