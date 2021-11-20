/*
 * Copyright 2018 the original author or authors.
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
package org.glowroot.agent.tests.javaagent;

import java.util.Iterator;

import org.junit.jupiter.api.*;

import org.glowroot.agent.it.harness.AppUnderTest;
import org.glowroot.agent.it.harness.Container;
import org.glowroot.agent.it.harness.TransactionMarker;
import org.glowroot.agent.it.harness.impl.JavaagentContainer;
import org.glowroot.wire.api.model.TraceOuterClass.Trace;

import static org.assertj.core.api.Assertions.assertThat;

public class LambdaWeavingIT {

    protected static Container container;

    @BeforeAll
    public static void setUp() throws Exception {
        container = JavaagentContainer.create();
    }

    @AfterAll
    public static void tearDown() throws Exception {
        container.close();
    }

    @AfterEach
    public void afterEachTest() throws Exception {
        container.checkAndReset();
    }

    // works on Java 8, fails on Java 9+ due to lambda classes no longer being passed to
    // ClassFileTransformer :-(
    @Test
    @Disabled
    public void shouldExerciseBootstrapWeaving() throws Exception {
        // when
        Trace trace = container.execute(ShouldExerciseLambdaWeaving.class);

        // then
        Iterator<Trace.Entry> i = trace.getEntryList().iterator();

        Trace.Entry entry = i.next();
        assertThat(entry.getDepth()).isEqualTo(0);
        assertThat(entry.getMessage()).isEqualTo("lambda: x => xy");

        assertThat(i.hasNext()).isFalse();
    }

    public static class ShouldExerciseLambdaWeaving implements AppUnderTest, TransactionMarker {
        @Override
        public void executeApp() {
            transactionMarker();
        }
        @Override
        public void transactionMarker() {
            apply(x -> x + "y");
        }
        private void apply(Lambda lambda) {
            lambda.execute("x");
        }
    }

    public interface Lambda {
        String execute(String x);
    }
}
