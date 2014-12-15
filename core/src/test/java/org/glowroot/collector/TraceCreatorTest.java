/*
 * Copyright 2011-2014 the original author or authors.
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
package org.glowroot.collector;

import java.io.IOException;
import java.lang.Thread.State;
import java.util.Arrays;

import com.google.common.io.CharSource;
import org.junit.Test;

import org.glowroot.transaction.model.Profile;

import static org.assertj.core.api.Assertions.assertThat;

public class TraceCreatorTest {

    @Test
    public void shouldStoreVeryLargeProfile() throws IOException {
        // given
        Profile profile = new Profile();
        // StackOverflowError was previously occurring somewhere around 1300 stack trace elements
        // using a 1mb thread stack size so testing with 10,000 here just to be sure
        StackTraceElement[] stackTrace = new StackTraceElement[10000];
        for (int i = 0; i < stackTrace.length; i++) {
            stackTrace[i] = new StackTraceElement(TraceCreatorTest.class.getName(),
                    "method" + i, TraceCreatorTest.class.getName() + ".java", 100 + 10 * i);
        }
        profile.addToStackTree(Profile.stripSyntheticMetricMethods(Arrays.asList(stackTrace)),
                State.RUNNABLE);
        // when
        CharSource profileCharSource =
                ProfileCharSourceCreator.createProfileCharSource(profile);
        assertThat(profileCharSource).isNotNull();
        // then don't blow up with StackOverflowError
        // (and an extra verification just to make sure the test was valid)
        assertThat(profileCharSource.read().length()).isGreaterThan(1000000);
    }
}
