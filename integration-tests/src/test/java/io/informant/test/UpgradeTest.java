/**
 * Copyright 2012-2013 the original author or authors.
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
import io.informant.Containers;
import io.informant.container.AppUnderTest;
import io.informant.container.Container;
import io.informant.container.TempDirs;
import io.informant.container.config.GeneralConfig;
import io.informant.container.local.LocalContainer;
import io.informant.container.trace.Span;
import io.informant.container.trace.Trace;

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
        Resources.asByteSource(Resources.getResource("for-upgrade-test/config.json"))
                .copyTo(Files.asByteSink(new File(dataDir, "config.json")));
        Resources.asByteSource(Resources.getResource("for-upgrade-test/informant.h2.db"))
                .copyTo(Files.asByteSink(new File(dataDir, "informant.h2.db")));
        Resources.asByteSource(Resources.getResource("for-upgrade-test/informant.rolling.db"))
                .copyTo(Files.asByteSink(new File(dataDir, "informant.rolling.db")));
        Container container = Containers.create(dataDir, 0, true);
        // when
        Trace trace = container.getTraceService().getLastTrace();
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
        File dataDir = TempDirs.createTempDir("informant-test-datadir");
        Container container = LocalContainer.createWithFileDb(dataDir);
        container.getConfigService().setStoreThresholdMillis(0);
        GeneralConfig generalConfig = container.getConfigService().getGeneralConfig();
        // disable trace snapshot expiration so the test data won't expire
        generalConfig.setSnapshotExpirationHours(-1);
        container.getConfigService().updateGeneralConfig(generalConfig);
        container.executeAppUnderTest(ShouldGenerateTraceWithNestedSpans.class);
        container.close();
        Files.copy(new File(dataDir, "config.json"),
                new File("src/test/resources/for-upgrade-test/config.json"));
        Files.copy(new File(dataDir, "informant.h2.db"),
                new File("src/test/resources/for-upgrade-test/informant.h2.db"));
        Files.copy(new File(dataDir, "informant.rolling.db"),
                new File("src/test/resources/for-upgrade-test/informant.rolling.db"));
        TempDirs.deleteRecursively(dataDir);
    }

    public static class ShouldGenerateTraceWithNestedSpans implements AppUnderTest {
        public void executeApp() throws Exception {
            new LevelOne().call("a", "b");
        }
    }
}
