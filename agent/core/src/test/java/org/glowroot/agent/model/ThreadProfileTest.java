/*
 * Copyright 2026 the original author or authors.
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
package org.glowroot.agent.model;

import org.junit.jupiter.api.Test;

import org.glowroot.wire.api.model.ProfileOuterClass.Profile;

import static org.assertj.core.api.Assertions.assertThat;

public class ThreadProfileTest {

    @Test
    public void shouldAcceptStackTraceWithoutThreadInfo() {
        ThreadProfile profile = new ThreadProfile(100);
        StackTraceElement[] stackTrace = new StackTraceElement[] {
                new StackTraceElement("com.example.App", "handle", "App.java", 10),
                new StackTraceElement("com.example.App", "main", "App.java", 5)
        };

        profile.addStackTrace(stackTrace, Thread.State.RUNNABLE);

        assertThat(profile.getSampleCount()).isEqualTo(1);
        Profile proto = profile.toProto();
        assertThat(proto.getNodeList()).isNotEmpty();
    }

    @Test
    public void shouldRespectSampleLimit() {
        ThreadProfile profile = new ThreadProfile(2);
        StackTraceElement[] stackTrace = new StackTraceElement[] {
                new StackTraceElement("com.example.App", "handle", "App.java", 10)
        };

        profile.addStackTrace(stackTrace, Thread.State.RUNNABLE);
        profile.addStackTrace(stackTrace, Thread.State.RUNNABLE);
        profile.addStackTrace(stackTrace, Thread.State.RUNNABLE);

        assertThat(profile.getSampleCount()).isEqualTo(2);
        assertThat(profile.isSampleLimitExceeded()).isTrue();
    }
}
