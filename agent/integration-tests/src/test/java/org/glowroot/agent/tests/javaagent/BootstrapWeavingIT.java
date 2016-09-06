/*
 * Copyright 2016 the original author or authors.
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

import java.beans.BeanDescriptor;

import org.junit.After;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import org.glowroot.agent.it.harness.AppUnderTest;
import org.glowroot.agent.it.harness.Container;
import org.glowroot.agent.it.harness.Containers;
import org.glowroot.agent.it.harness.TransactionMarker;
import org.glowroot.wire.api.model.TraceOuterClass.Trace;

import static org.assertj.core.api.Assertions.assertThat;

public class BootstrapWeavingIT {

    protected static Container container;

    @BeforeClass
    public static void setUp() throws Exception {
        container = Containers.createJavaagent();
    }

    @AfterClass
    public static void tearDown() throws Exception {
        container.close();
    }

    @After
    public void afterEachTest() throws Exception {
        container.checkAndReset();
    }

    @Test
    public void shouldExerciseBootstrapWeaving() throws Exception {
        Trace trace = container.execute(ShouldExerciseBootstrapWeaving.class);
        assertThat(trace.getEntryCount()).isEqualTo(2);
        assertThat(trace.getEntry(0).getMessage()).isEqualTo("java.beans.BeanDescriptor");
        assertThat(trace.getEntry(1).getMessage()).isEqualTo("getCustomizerClass");
    }

    public static class ShouldExerciseBootstrapWeaving implements AppUnderTest, TransactionMarker {
        @Override
        public void executeApp() {
            transactionMarker();
        }
        @Override
        public void transactionMarker() {
            BeanDescriptor descriptor = new BeanDescriptor(A.class, B.class);
            descriptor.getBeanClass();
            descriptor.getCustomizerClass();
        }
    }

    public static class A {}

    public static class B {}
}
