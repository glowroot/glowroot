/**
 * Copyright 2012 the original author or authors.
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
package io.informant.test;

import static org.fest.assertions.api.Assertions.assertThat;
import io.informant.testkit.AppUnderTest;
import io.informant.testkit.Config.CoreConfig;
import io.informant.testkit.InformantContainer;
import io.informant.testkit.Trace;
import io.informant.testkit.Trace.Span;
import io.informant.testkit.internal.TempDirs;

import java.io.File;

import org.junit.Test;

import com.google.common.io.Files;
import com.google.common.io.Resources;

/**
 * @author Trask Stalnaker
 * @since 0.5
 */
public class UpgradeTest {

    @Test
    public void shouldReadTraces() throws Exception {
        // given
        File dataDir = TempDirs.createTempDir("informant-test-datadir");
        Files.copy(Resources.newInputStreamSupplier(Resources
                .getResource("for-upgrade-test/informant.h2.db")),
                new File(dataDir, "informant.h2.db"));
        Files.copy(Resources.newInputStreamSupplier(Resources
                .getResource("for-upgrade-test/informant.rolling.db")),
                new File(dataDir, "informant.rolling.db"));
        InformantContainer container = InformantContainer.create(0, false, dataDir);
        // when
        Trace trace = container.getInformant().getLastTrace();
        // then
        assertThat(trace.getHeadline()).isEqualTo("Level One");
        assertThat(trace.getSpans()).hasSize(3);
        Span span1 = trace.getSpans().get(0);
        assertThat(span1.getMessage().getText()).isEqualTo("Level One");
        Span span2 = trace.getSpans().get(1);
        assertThat(span2.getMessage().getText()).isEqualTo("Level Two");
        Span span3 = trace.getSpans().get(2);
        assertThat(span3.getMessage().getText()).isEqualTo("Level Three");
        // cleanup
        container.close();
    }

    // create initial database for upgrade test
    public static void main(String... args) throws Exception {
        InformantContainer container = InformantContainer.create(0, false);
        container.getInformant().setStoreThresholdMillis(0);
        CoreConfig coreConfig = container.getInformant().getCoreConfig();
        // disable trace snapshot expiration so the test data won't expire
        coreConfig.setSnapshotExpirationHours(-1);
        container.getInformant().updateCoreConfig(coreConfig);
        container.executeAppUnderTest(ShouldGenerateTraceWithNestedSpans.class);
        container.closeWithoutDeletingDataDir();
        Files.copy(new File(container.getDataDir(), "informant.h2.db"),
                new File("src/test/resources/for-upgrade-test/informant.h2.db"));
        Files.copy(new File(container.getDataDir(), "informant.rolling.db"),
                new File("src/test/resources/for-upgrade-test/informant.rolling.db"));
        TempDirs.deleteRecursively(container.getDataDir());
    }

    public static class ShouldGenerateTraceWithNestedSpans implements AppUnderTest {
        public void executeApp() throws Exception {
            new LevelOne().call("a", "b");
        }
    }
}
