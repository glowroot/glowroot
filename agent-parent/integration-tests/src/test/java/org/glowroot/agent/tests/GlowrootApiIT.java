/*
 * Copyright 2015 the original author or authors.
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
package org.glowroot.agent.tests;

import org.junit.After;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import org.glowroot.agent.api.Glowroot;
import org.glowroot.agent.it.harness.AppUnderTest;
import org.glowroot.agent.it.harness.Container;
import org.glowroot.agent.it.harness.Containers;
import org.glowroot.agent.it.harness.TransactionMarker;
import org.glowroot.wire.api.model.TraceOuterClass.Trace;

import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static org.assertj.core.api.Assertions.assertThat;

public class GlowrootApiIT {

    private static Container container;

    @BeforeClass
    public static void setUp() throws Exception {
        container = Containers.getSharedContainer();
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
    public void shouldSetTransactionType() throws Exception {
        // given
        // when
        Trace trace = container.execute(SetTransactionType.class);
        // then
        assertThat(trace.getHeader().getTransactionType()).isEqualTo("a type");
    }

    @Test
    public void shouldSetTransactionName() throws Exception {
        // given
        // when
        Trace trace = container.execute(SetTransactionName.class);
        // then
        assertThat(trace.getHeader().getTransactionName()).isEqualTo("a name");
    }

    @Test
    public void shouldSetTransactionErrorWithThrowable() throws Exception {
        // given
        // when
        Trace trace = container.execute(SetTransactionErrorWithThrowable.class);
        // then
        assertThat(trace.getHeader().getError().getMessage()).isEqualTo("abc");
        assertThat(trace.getHeader().getError().getException().getDisplay())
                .isEqualTo("java.lang.IllegalStateException: abc");
    }

    @Test
    public void shouldSetTransactionErrorWithMessage() throws Exception {
        // given
        // when
        Trace trace = container.execute(SetTransactionErrorWithMessage.class);
        // then
        assertThat(trace.getHeader().getError().getMessage()).isEqualTo("xyz");
        assertThat(trace.getHeader().getError().hasException()).isFalse();
    }

    @Test
    public void shouldSetTransactionErrorWithMessageAndThrowable() throws Exception {
        // given
        // when
        Trace trace = container.execute(SetTransactionErrorWithMessageAndThrowable.class);
        // then
        assertThat(trace.getHeader().getError().getMessage()).isEqualTo("efg");
        assertThat(trace.getHeader().getError().getException().getDisplay())
                .isEqualTo("java.lang.IllegalStateException: tuv");
    }

    @Test
    public void shouldSetTransactionUser() throws Exception {
        // given
        // when
        Trace trace = container.execute(SetTransactionUser.class);
        // then
        assertThat(trace.getHeader().getUser()).isEqualTo("a user");
    }

    @Test
    public void shouldAddTransactionAttribute() throws Exception {
        // given
        // when
        Trace trace = container.execute(AddTransactionAttribute.class);
        // then
        assertThat(trace.getHeader().getAttributeList().size()).isEqualTo(1);
        Trace.Attribute attribute = trace.getHeader().getAttributeList().get(0);
        assertThat(attribute.getName()).isEqualTo("an attr");
        assertThat(attribute.getValueList()).containsExactly("a val");
    }

    @Test
    public void shouldSetTransactionSlowThreshold() throws Exception {
        // given
        // when
        container.executeNoExpectedTrace(SetTransactionSlowThreshold.class);
        // then
    }

    public static class SetTransactionType implements AppUnderTest, TransactionMarker {

        @Override
        public void executeApp() throws Exception {
            transactionMarker();
        }

        @Override
        public void transactionMarker() throws Exception {
            Glowroot.setTransactionType("a type");
        }
    }

    public static class SetTransactionName implements AppUnderTest, TransactionMarker {

        @Override
        public void executeApp() throws Exception {
            transactionMarker();
        }

        @Override
        public void transactionMarker() throws Exception {
            Glowroot.setTransactionName("a name");
        }
    }

    public static class SetTransactionErrorWithThrowable
            implements AppUnderTest, TransactionMarker {

        @Override
        public void executeApp() throws Exception {
            transactionMarker();
        }

        @Override
        public void transactionMarker() throws Exception {
            Glowroot.setTransactionError(new IllegalStateException("abc"));
        }
    }

    public static class SetTransactionErrorWithMessage implements AppUnderTest, TransactionMarker {

        @Override
        public void executeApp() throws Exception {
            transactionMarker();
        }

        @Override
        public void transactionMarker() throws Exception {
            Glowroot.setTransactionError("xyz");
        }
    }

    public static class SetTransactionErrorWithMessageAndThrowable
            implements AppUnderTest, TransactionMarker {

        @Override
        public void executeApp() throws Exception {
            transactionMarker();
        }

        @Override
        public void transactionMarker() throws Exception {
            Glowroot.setTransactionError("efg", new IllegalStateException("tuv"));
        }
    }

    public static class SetTransactionUser implements AppUnderTest, TransactionMarker {

        @Override
        public void executeApp() throws Exception {
            transactionMarker();
        }

        @Override
        public void transactionMarker() throws Exception {
            Glowroot.setTransactionUser("a user");
        }
    }

    public static class AddTransactionAttribute implements AppUnderTest, TransactionMarker {

        @Override
        public void executeApp() throws Exception {
            transactionMarker();
        }

        @Override
        public void transactionMarker() throws Exception {
            Glowroot.addTransactionAttribute("an attr", "a val");
        }
    }

    public static class SetTransactionSlowThreshold implements AppUnderTest, TransactionMarker {

        @Override
        public void executeApp() throws Exception {
            transactionMarker();
        }

        @Override
        public void transactionMarker() throws Exception {
            Glowroot.setTransactionSlowThreshold(Long.MAX_VALUE, MILLISECONDS);
        }
    }
}
