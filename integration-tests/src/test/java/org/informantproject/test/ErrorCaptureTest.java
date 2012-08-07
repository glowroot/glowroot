/**
 * Copyright 2011-2012 the original author or authors.
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

import org.informantproject.test.LevelOne;
import org.informantproject.testkit.AppUnderTest;
import org.informantproject.testkit.InformantContainer;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

/**
 * @author Trask Stalnaker
 * @since 0.5
 */
public class ErrorCaptureTest {

    private static InformantContainer container;

    @BeforeClass
    public static void setUp() throws Exception {
        container = InformantContainer.create();
    }

    @AfterClass
    public static void tearDown() throws Exception {
        container.closeAndDeleteFiles();
    }

    @Test
    public void shouldReadTraces() throws Exception {
        // given
        container.getInformant().setThresholdMillis(10000);
        // when
        container.executeAppUnderTest(ShouldCaptureError.class);
        // then
        container.getInformant().getLastTrace();
    }

    public static class ShouldCaptureError implements AppUnderTest {
        public void executeApp() throws Exception {
            Exception expected = new Exception();
            try {
                new LevelOne(expected).call("a", "b");
            } catch (Exception e) {
                if (e != expected) {
                    // suppress expected exception
                    throw e;
                }
            }
        }
    }
}
