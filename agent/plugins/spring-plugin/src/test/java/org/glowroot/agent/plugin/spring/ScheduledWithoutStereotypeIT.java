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
package org.glowroot.agent.plugin.spring;

import java.lang.reflect.Method;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.support.ScheduledMethodRunnable;

import org.glowroot.agent.it.harness.AppUnderTest;
import org.glowroot.agent.it.harness.Container;
import org.glowroot.agent.it.harness.Containers;
import org.glowroot.wire.api.model.TraceOuterClass.Trace;

import static org.assertj.core.api.Assertions.assertThat;

// Spring invokes @Scheduled via ScheduledMethodRunnable — covers @Bean beans without stereotypes (#955).
public class ScheduledWithoutStereotypeIT {

    private static Container container;

    @BeforeAll
    public static void setUp() throws Exception {
        container = Containers.create();
    }

    @AfterAll
    public static void tearDown() throws Exception {
        container.close();
    }

    @AfterEach
    public void afterEachTest() throws Exception {
        container.checkAndReset();
    }

    @Test
    public void shouldCaptureScheduledWithoutComponent() throws Exception {
        Trace trace = container.execute(CallScheduledWithoutStereotype.class);
        assertThat(trace.getHeader().getTransactionType()).isEqualTo("Background");
        assertThat(trace.getHeader().getTransactionName())
                .isEqualTo("Spring scheduled: FooService#foo");
    }

    public static class CallScheduledWithoutStereotype implements AppUnderTest {
        @Override
        public void executeApp() throws Exception {
            Method method = FooService.class.getMethod("foo");
            new ScheduledMethodRunnable(new FooService(), method).run();
        }
    }

    // no @Component / @Service — same as a @Bean-registered type
    public static class FooService {
        @Scheduled(fixedDelay = Long.MAX_VALUE)
        public void foo() {}
    }
}
