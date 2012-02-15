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
package org.informantproject.local.trace;

import java.io.IOException;
import java.lang.Thread.State;

import org.informantproject.stack.MergedStackTree;
import org.jukito.JukitoRunner;
import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * @author Trask Stalnaker
 * @since 0.5
 */
@RunWith(JukitoRunner.class)
public class TraceSinkLocalTest {

    @Test
    public void shouldStoreVeryLargeMergedStackTrace() throws IOException {
        // given
        MergedStackTree mergedStackTree = new MergedStackTree();
        // StackOverflowError was previously occurring somewhere around 1300 stack trace elements
        // using a 1mb thread stack size so testing with 10,000 here just to be sure
        StackTraceElement[] stackTraceElements = new StackTraceElement[10000];
        for (int i = 0; i < stackTraceElements.length; i++) {
            stackTraceElements[i] = new StackTraceElement(TraceSinkLocalTest.class.getName(),
                    "method" + i, "TraceDaoTest.java", 100 + 10 * i);
        }
        mergedStackTree.addToStackTree(stackTraceElements, State.RUNNABLE);
        // when
        TraceSinkLocal.buildMergedStackTree(mergedStackTree);
        // then don't blow up with StackOverflowError
    }
}
