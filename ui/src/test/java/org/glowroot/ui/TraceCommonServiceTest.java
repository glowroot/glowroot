/*
 * Copyright 2016 the original author or authors.
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

import java.util.List;

import com.google.common.collect.Lists;
import org.junit.Test;

import org.glowroot.wire.api.model.TraceOuterClass.Trace;

import static org.assertj.core.api.Assertions.assertThat;

public class TraceCommonServiceTest {

    @Test
    public void test() throws Exception {
        // given
        List<Trace.Entry> entries = Lists.newArrayList();
        entries.add(Trace.Entry.newBuilder().setDepth(0).build());
        entries.add(Trace.Entry.newBuilder().setDepth(1).build());
        // when
        String json = TraceCommonService.entriesToJson(entries);
        // then
        assertThat(json).isEqualTo("[{\"startOffsetNanos\":0,\"durationNanos\":0,\"message\":\"\","
                + "\"childEntries\":[{\"startOffsetNanos\":0,\"durationNanos\":0,\"message\":\"\"}]"
                + "}]");
    }
}
