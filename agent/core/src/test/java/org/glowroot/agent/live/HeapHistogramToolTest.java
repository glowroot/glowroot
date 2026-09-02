/*
 * Copyright 2026 the original author or authors.
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
package org.glowroot.agent.live;

import org.junit.jupiter.api.Test;

import org.glowroot.wire.api.model.DownstreamServiceOuterClass.HeapHistogram;

import static org.assertj.core.api.Assertions.assertThat;

public class HeapHistogramToolTest {

    @Test
    public void shouldParseOpenJ9StyleHistogram() throws Exception {
        // OpenJ9 attach heapHisto / jcmd GC.class_histogram column layout
        String text = ""
                + " num object count total size class name\n"
                + "-------------------------------------------------\n"
                + " 1 2 96 java.lang.String\n"
                + " 2 1 40 [C\n"
                + "Total 3 136\n";

        HeapHistogram histogram = HeapHistogramTool.parseHistogramText(text);

        assertThat(histogram.getClassInfoCount()).isEqualTo(2);
        assertThat(histogram.getClassInfo(0).getClassName()).isEqualTo("java.lang.String");
        assertThat(histogram.getClassInfo(0).getCount()).isEqualTo(2);
        assertThat(histogram.getClassInfo(0).getBytes()).isEqualTo(96);
        assertThat(histogram.getClassInfo(1).getClassName()).isEqualTo("char[]");
        assertThat(histogram.getClassInfo(1).getCount()).isEqualTo(1);
        assertThat(histogram.getClassInfo(1).getBytes()).isEqualTo(40);
    }
}
