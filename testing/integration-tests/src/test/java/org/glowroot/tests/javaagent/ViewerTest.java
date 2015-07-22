/*
 * Copyright 2015 the original author or authors.
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
package org.glowroot.tests.javaagent;

import java.io.File;
import java.util.List;

import com.google.common.collect.ImmutableList;
import com.google.common.io.Files;
import com.google.common.io.Resources;
import org.junit.Test;

import org.glowroot.container.Container;
import org.glowroot.container.TempDirs;
import org.glowroot.container.impl.JavaagentContainer;
import org.glowroot.container.trace.Trace;
import org.glowroot.container.trace.TraceEntry;

import static org.assertj.core.api.Assertions.assertThat;

public class ViewerTest {

    @Test
    public void shouldReadTraces() throws Exception {
        // given
        File baseDir = TempDirs.createTempDir("glowroot-test-basedir");
        File dataDir = new File(baseDir, "data");
        dataDir.mkdir();
        Resources.asByteSource(Resources.getResource("for-upgrade-test/config.json"))
                .copyTo(Files.asByteSink(new File(baseDir, "config.json")));
        Resources.asByteSource(Resources.getResource("for-upgrade-test/data/data.h2.db"))
                .copyTo(Files.asByteSink(new File(dataDir, "data.h2.db")));
        for (int i = 0; i < 3; i++) {
            String filename = "aggregate-detail-rollup-" + i + ".capped.db";
            Resources.asByteSource(Resources.getResource("for-upgrade-test/data/" + filename))
                    .copyTo(Files.asByteSink(new File(dataDir, filename)));
        }
        Resources.asByteSource(
                Resources.getResource("for-upgrade-test/data/trace-detail.capped.db"))
                .copyTo(Files.asByteSink(new File(dataDir, "trace-detail.capped.db")));
        Container container = new JavaagentContainer(baseDir, true, 0, false, false, true,
                ImmutableList.<String>of());
        // when
        Trace trace = container.getTraceService().getLastTrace();
        List<TraceEntry> entries = container.getTraceService().getEntries(trace.getId());
        // then
        try {
            assertThat(trace.getHeadline()).isEqualTo("Level One");
            assertThat(trace.getTransactionName()).isEqualTo("basic test");
            assertThat(entries).hasSize(3);
            TraceEntry entry2 = entries.get(0);
            assertThat(entry2.getMessage().getText()).isEqualTo("Level Two");
            TraceEntry entry3 = entries.get(1);
            assertThat(entry3.getMessage().getText()).isEqualTo("Level Three");
            TraceEntry entry4 = entries.get(2);
            assertThat(entry4.getMessage().getText()).isEqualTo("Level Four: axy, bxy");
        } finally {
            // cleanup
            container.checkAndReset();
            container.close();
            TempDirs.deleteRecursively(baseDir);
        }
    }
}
