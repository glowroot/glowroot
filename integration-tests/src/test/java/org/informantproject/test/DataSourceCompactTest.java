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
package org.informantproject.test;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.Matchers.lessThan;
import static org.junit.Assert.assertThat;

import java.io.File;

import org.informantproject.testkit.AppUnderTest;
import org.informantproject.testkit.Configuration.CoreConfiguration;
import org.informantproject.testkit.InformantContainer;
import org.informantproject.testkit.RootSpanMarker;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

/**
 * @author Trask Stalnaker
 * @since 0.5
 */
public class DataSourceCompactTest {

    private static InformantContainer container;

    @BeforeClass
    public static void setUp() throws Exception {
        container = InformantContainer.create();
    }

    @AfterClass
    public static void tearDown() throws Exception {
        container.shutdown();
    }

    @Test
    public void shouldCompact() throws Exception {
        // given
        CoreConfiguration coreConfiguration = container.getInformant().getCoreConfiguration();
        coreConfiguration.setThresholdMillis(0);
        container.getInformant().updateCoreConfiguration(coreConfiguration);
        File dbFile = new File(container.getInformant().get("/misc/dbFile"));
        // when
        container.executeAppUnderTest(GenerateLotsOfTraces.class);
        long preCompactionDbSize = dbFile.length();
        container.getInformant().deleteAllTraces();
        // then
        assertThat(dbFile.length(), is(lessThan(preCompactionDbSize)));
    }

    public static class GenerateLotsOfTraces implements AppUnderTest, RootSpanMarker {
        public void executeApp() throws InterruptedException {
            for (int i = 0; i < 100; i++) {
                rootSpanMarker();
            }
        }
        public void rootSpanMarker() {}
    }
}
