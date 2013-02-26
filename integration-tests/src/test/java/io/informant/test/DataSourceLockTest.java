/**
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
package io.informant.test;

import static org.fest.assertions.api.Assertions.assertThat;
import io.informant.testkit.InformantContainer;
import io.informant.testkit.InformantContainer.StartupFailedException;

import org.junit.Test;

/**
 * @author Trask Stalnaker
 * @since 0.5
 */
public class DataSourceLockTest {

    @Test
    public void shouldShutdown() throws Exception {
        if (!InformantContainer.isExternalJvm()) {
            // this test is only relevant in external jvm since H2 transparently handles two
            // connections to the same file inside the same jvm with no problem
            // (tests are run in external jvm during mvn integration-test but not during mvn test)
            // not using org.junit.Assume which reports the test as ignored, since ignored tests
            // seem like something that needs to be revisited and 'un-ignored'
            return;
        }
        // given
        InformantContainer container = InformantContainer.create(0, true);
        boolean exception = false;
        try {
            InformantContainer.create(0, true, container.getDataDir());
        } catch (StartupFailedException e) {
            exception = true;
        }
        assertThat(exception).isTrue();
        // cleanup
        container.close();
    }
}
