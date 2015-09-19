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
            String filename = "rollup-" + i + "-detail.capped.db";
            Resources.asByteSource(Resources.getResource("for-upgrade-test/data/" + filename))
                    .copyTo(Files.asByteSink(new File(dataDir, filename)));
        }
        Resources
                .asByteSource(Resources.getResource("for-upgrade-test/data/trace-detail.capped.db"))
                .copyTo(Files.asByteSink(new File(dataDir, "trace-detail.capped.db")));
        Container container = new JavaagentContainer(baseDir, true, 0, false, false, true,
                ImmutableList.<String>of());
        // when
        Trace.Header header = container.getTraceService().getLastHeader();
        List<Trace.Entry> entries = container.getTraceService().getEntries(header.id());
        // then
        try {
            assertThat(header.headline()).isEqualTo("Level One");
            assertThat(header.transactionName()).isEqualTo("basic test");
            assertThat(entries).hasSize(1);
            Trace.Entry entry2 = entries.get(0);
            assertThat(entry2.message()).isEqualTo("Level Two");
            List<Trace.Entry> childEntries2 = entry2.childEntries();
            assertThat(childEntries2).hasSize(1);
            Trace.Entry entry3 = childEntries2.get(0);
            assertThat(entry3.message()).isEqualTo("Level Three");
            List<Trace.Entry> childEntries3 = entry3.childEntries();
            assertThat(childEntries3).hasSize(1);
            Trace.Entry entry4 = childEntries3.get(0);
            assertThat(entry4.message()).isEqualTo("Level Four: axy, bxy");
        } finally {
            // cleanup
            container.checkAndReset();
            container.close();
            TempDirs.deleteRecursively(baseDir);
        }
    }
}
