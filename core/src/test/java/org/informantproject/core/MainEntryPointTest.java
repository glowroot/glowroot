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
package org.informantproject.core;

import java.util.Collection;

import org.informantproject.core.util.Threads;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * @author Trask Stalnaker
 * @since 0.5
 */
public class MainEntryPointTest {

    private Collection<Thread> preExistingThreads;

    @Before
    public void before() {
        preExistingThreads = Threads.currentThreads();
        MainEntryPoint.start("ui.port:0");
    }

    @After
    public void after() throws Exception {
        Threads.preShutdownCheck(preExistingThreads);
        MainEntryPoint.shutdown();
        Threads.postShutdownCheck(preExistingThreads);
    }

    @Test
    public void testNoGuiceBindingErrorsEtc() {
        // all the work is done in before() and after()
    }
}
